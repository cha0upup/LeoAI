<?php
$get = static function ($value, $key, $default = null) {
    return is_array($value) && array_key_exists($key, $value) ? $value[$key] : $default;
};
$available = static function ($name) {
    return function_exists($name) && !in_array($name,
        array_map('trim', explode(',', (string)ini_get('disable_functions'))), true);
};
return [
    'id' => 'ExecCommandSimpleComponent',
    'version' => '1.1.0',
    'handle' => static function ($action, $params) use ($get, $available) {
        $command = (string)$get($params, 'cmd', '');
        if ($command === '') throw new InvalidArgumentException('cmd is required');
        $timeout = max(1, min(120, (int)$get($params, 'timeoutSeconds', 30)));
        if ($available('proc_open')) {
            $pipes = [];
            $process = proc_open($command, [0 => ['pipe', 'r'], 1 => ['pipe', 'w'], 2 => ['pipe', 'w']], $pipes);
            if (!is_resource($process)) throw new RuntimeException('proc_open failed');
            fclose($pipes[0]); stream_set_blocking($pipes[1], false); stream_set_blocking($pipes[2], false);
            $stdout = ''; $stderr = ''; $started = microtime(true);
            do {
                $stdout .= stream_get_contents($pipes[1]); $stderr .= stream_get_contents($pipes[2]);
                $status = proc_get_status($process);
                if (!$status['running']) break;
                if (microtime(true) - $started >= $timeout) {
                    proc_terminate($process, 9); throw new RuntimeException('command timed out');
                }
                usleep(10000);
            } while (true);
            $stdout .= stream_get_contents($pipes[1]); $stderr .= stream_get_contents($pipes[2]);
            fclose($pipes[1]); fclose($pipes[2]); $exitCode = proc_close($process);
            return ['code' => 200, 'data' => $stdout . $stderr, 'exitCode' => $exitCode];
        }
        if ($available('exec')) {
            $lines = []; $code = 0; exec($command . ' 2>&1', $lines, $code); $output = implode("\n", $lines);
            return ['code' => 200, 'data' => $output, 'exitCode' => $code];
        }
        throw new RuntimeException('command execution functions are disabled');
    }
];
