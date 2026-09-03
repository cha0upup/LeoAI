<?php
/* Unified node-side network probe runtime. The service submits a bounded plan
 * and receives observations; rule evaluation remains on the service side. */
$get = static function ($value, $key, $default = null) {
    return is_array($value) && array_key_exists($key, $value) ? $value[$key] : $default;
};
$now = static function () { return (int)round(microtime(true) * 1000); };
$scope = substr(hash('sha256', __FILE__ . '|state'), 0, 14);
$workerToken = substr(hash('sha256', __FILE__ . '|worker'), 0, 18);
$base = rtrim((string)sys_get_temp_dir(), '/\\') . DIRECTORY_SEPARATOR . '.' . $scope;
$stateName = static function ($label) { return substr(hash('sha256', __FILE__ . '|file|' . $label), 0, 16) . '.dat'; };
$path = static function ($taskId) use ($base) {
    if (!is_string($taskId) || !preg_match('/^[A-Za-z0-9_-]{8,128}$/', $taskId)) {
        throw new InvalidArgumentException('invalid taskId');
    }
    return $base . DIRECTORY_SEPARATOR . $taskId;
};
$read = static function ($file) {
    $value = json_decode((string)@file_get_contents($file), true);
    return is_array($value) ? $value : null;
};
$write = static function ($file, $value) {
    $encoded = json_encode($value);
    if (!is_string($encoded)) return false;
    $temporary = $file . '.' . getmypid() . '.tmp';
    if (@file_put_contents($temporary, $encoded, LOCK_EX) === false) return false;
    @chmod($temporary, 0600);
    return @rename($temporary, $file);
};
$launch = static function ($directory) use ($workerToken) {
    $php = defined('PHP_BINARY') && PHP_BINARY !== '' ? PHP_BINARY : 'php';
    $runner = escapeshellarg($php) . ' ' . escapeshellarg(__FILE__) . ' ' .
        escapeshellarg($workerToken) . ' ' . escapeshellarg($directory);
    if (DIRECTORY_SEPARATOR === '\\') {
        if (!function_exists('popen')) return false;
        $handle = @popen('start /B "" ' . $runner . ' >NUL 2>&1', 'r');
        if (is_resource($handle)) { pclose($handle); return true; }
        return false;
    }
    $command = '/bin/sh -c ' . escapeshellarg($runner . ' </dev/null >/dev/null 2>&1 & echo $!');
    if (function_exists('shell_exec')) return (int)trim((string)@shell_exec($command)) > 0;
    if (function_exists('exec')) { $lines = []; $code = 1; @exec($command, $lines, $code); return $code === 0; }
    return false;
};
$sanitize = static function ($value, $limit = 4096) {
    $value = (string)$value;
    if (strlen($value) > $limit) $value = substr($value, 0, $limit);
    return preg_replace('/[\x00-\x08\x0B\x0C\x0E-\x1F\x7F]/', '', $value);
};
$readBytes = static function ($stream, $max) use ($sanitize) {
    $data = '';
    while (strlen($data) < $max && !feof($stream)) {
        $chunk = @fread($stream, min(2048, $max - strlen($data)));
        if ($chunk === false || $chunk === '') break;
        $data .= $chunk;
    }
    return $sanitize($data, $max);
};
$observation = static function ($target, $stage, $state, $started, $error = '', $evidence = []) use ($sanitize) {
    $result = [
        'target' => (string)($target['host'] ?? '') . ':' . (int)($target['port'] ?? 0),
        'host' => $target['host'] ?? '', 'port' => (int)($target['port'] ?? 0),
        'protocol' => $target['protocol'] ?? 'tcp', 'transport' => 'tcp',
        'stage' => $stage, 'state' => $state,
        'latencyMs' => max(0, (int)round(microtime(true) * 1000) - $started)
    ];
    foreach (['targetId', 'probeId', 'ruleId', 'requestIndex'] as $field) {
        if (array_key_exists($field, $target)) $result[$field] = $target[$field];
    }
    if ($error !== '') $result['error'] = $sanitize($error);
    if (is_array($evidence) && $evidence) $result['evidence'] = $evidence;
    return $result;
};
$probe = static function ($target, $stage, $timeout, $maxRead) use ($observation, $readBytes, $sanitize) {
    $started = (int)round(microtime(true) * 1000);
    $host = (string)($target['host'] ?? ''); $port = (int)($target['port'] ?? 0);
    $scheme = strtolower((string)($target['protocol'] ?? 'tcp')) === 'https' ? 'ssl' : 'tcp';
    $address = $scheme . '://' . $host . ':' . $port;
    $context = stream_context_create(['ssl' => ['verify_peer' => false, 'verify_peer_name' => false]]);
    $errno = 0; $error = '';
    $stream = @stream_socket_client($address, $errno, $error, max(0.05, $timeout / 1000.0), STREAM_CLIENT_CONNECT, $context);
    if (!is_resource($stream)) return $observation($target, $stage, 'closed', $started, $error ?: 'connection failed');
    stream_set_timeout($stream, max(1, (int)ceil($timeout / 1000)));
    try {
        if ($stage === 'tcp-connect' || $stage === 'tls-handshake') {
            if ($stage === 'tls-handshake' && substr($scheme, 0, 3) !== 'ssl') {
                $crypto = @stream_socket_enable_crypto($stream, true, STREAM_CRYPTO_METHOD_TLS_CLIENT);
                if ($crypto !== true) return $observation($target, $stage, 'error', $started, 'TLS handshake failed');
            }
            $evidence = $stage === 'tls-handshake' ? ['protocol' => 'TLS'] : [];
            return $observation($target, $stage, 'open', $started, '', $evidence);
        }
        if ($stage === 'tcp-exchange') {
            $request = (string)($target['request'] ?? '');
            if ($request !== '') { @fwrite($stream, $request); }
            $banner = $readBytes($stream, $maxRead);
            return $observation($target, $stage, 'open', $started, '',
                ['bytes' => strlen($banner), 'banner' => $banner]);
        }
        $request = is_array($target['httpRequest'] ?? null) ? $target['httpRequest'] : [];
        $method = strtoupper((string)($request['method'] ?? ($stage === 'http-head' ? 'HEAD' : 'GET')));
        $pathValue = (string)($request['path'] ?? '/'); if ($pathValue === '') $pathValue = '/';
        $headers = is_array($request['headers'] ?? null) ? $request['headers'] : [];
        $headers['Host'] = $headers['Host'] ?? $host;
        $headers['Connection'] = $headers['Connection'] ?? 'close';
        $raw = $method . ' ' . $pathValue . " HTTP/1.1\r\n";
        foreach ($headers as $name => $value) {
            if (strpos((string)$name, "\r") !== false || strpos((string)$name, "\n") !== false) continue;
            $raw .= $name . ': ' . $value . "\r\n";
        }
        $body = (string)($request['body'] ?? '');
        if ($body !== '') $raw .= 'Content-Length: ' . strlen($body) . "\r\n";
        $raw .= "\r\n" . $body;
        @fwrite($stream, $raw);
        $response = $readBytes($stream, $maxRead);
        $separator = strpos($response, "\r\n\r\n");
        $separatorLength = 4;
        if ($separator === false) {
            $separator = strpos($response, "\n\n");
            $separatorLength = 2;
        }
        $headerBlock = $separator === false ? $response : substr($response, 0, $separator);
        $bodyBlock = $separator === false ? '' : substr($response, $separator + $separatorLength);
        $status = 0; if (preg_match('/^HTTP\/[^ ]+\s+(\d{3})/i', $headerBlock, $match)) $status = (int)$match[1];
        $evidence = [
            'statusCode' => $status,
            'headers' => $sanitize($headerBlock),
            'body' => $stage === 'http-request' ? $sanitize($bodyBlock) : '',
            'bodyLength' => strlen($bodyBlock),
            'truncated' => strlen($response) >= $maxRead
        ];
        if (preg_match('/(?:^|\r?\n)Server:\s*([^\r\n]+)/i', $headerBlock, $match)) $evidence['server'] = $sanitize($match[1], 512);
        if (preg_match('/(?:^|\r?\n)Content-Type:\s*([^\r\n]+)/i', $headerBlock, $match)) $evidence['contentType'] = $sanitize($match[1], 512);
        if (preg_match('/(?:^|\r?\n)Location:\s*([^\r\n]+)/i', $headerBlock, $match)) $evidence['location'] = $sanitize($match[1], 512);
        return $observation($target, $stage, $status > 0 ? 'open' : 'error', $started, '', $evidence);
    } finally { fclose($stream); }
};
$worker = static function ($directory) use ($read, $write, $stateName, $probe, $now) {
    $config = $read($directory . DIRECTORY_SEPARATOR . $stateName('config'));
    $statusFile = $directory . DIRECTORY_SEPARATOR . $stateName('status');
    if (!is_array($config)) return 2;
    $status = $read($statusFile); if (!is_array($status)) return 3;
    $stages = is_array($config['stages'] ?? null) ? $config['stages'] : ['tcp-connect'];
    $timeout = max(100, min(300000, (int)($config['timeout'] ?? 3000)));
    $maxRead = max(256, min(8192, (int)($config['maxReadBytes'] ?? 8192)));
    foreach ((array)($config['targets'] ?? []) as $target) {
        while (true) {
            $latest = $read($statusFile); if (is_array($latest)) $status = $latest;
            if (($status['status'] ?? '') === 'STOPPED') return 0;
            if (($status['status'] ?? '') !== 'PAUSED') break;
            usleep(250000);
        }
        $targetStages = !empty($target['stage']) ? [(string)$target['stage']] : $stages;
        foreach ($targetStages as $stage) {
            $status['observations'][] = $probe($target, $stage, (int)($target['timeout'] ?? $timeout),
                (int)($target['maxReadBytes'] ?? $maxRead));
        }
        $status['completed'] = (int)($status['completed'] ?? 0) + 1;
        $status['updatedAt'] = $now(); $write($statusFile, $status);
    }
    $status['status'] = 'STOPPED'; $status['finishedAt'] = $now(); $status['updatedAt'] = $status['finishedAt'];
    $write($statusFile, $status); return 0;
};
if (PHP_SAPI === 'cli' && isset($argv[1]) && hash_equals($workerToken, (string)$argv[1])) {
    exit($worker(isset($argv[2]) ? $argv[2] : ''));
}
$cleanup = static function () use ($base, $read, $stateName) {
    if (!is_dir($base)) return 0; $count = 0;
    foreach ((array)glob($base . DIRECTORY_SEPARATOR . '*') as $directory) {
        if (!is_dir($directory)) { @unlink($directory); continue; }
        $status = $read($directory . DIRECTORY_SEPARATOR . $stateName('status'));
        $time = (int)($status['finishedAt'] ?? $status['updatedAt'] ?? @filemtime($directory));
        if ($time > 0 && (int)round(microtime(true) * 1000) - $time > 1800000) {
            foreach ((array)glob($directory . DIRECTORY_SEPARATOR . '*') as $file) @unlink($file);
            @rmdir($directory); continue;
        }
        $count++;
    }
    return $count;
};
return [
    'id' => 'NetworkProbeComponent', 'version' => '1.0.0',
    'handle' => static function ($action, $params) use ($get, $base, $path, $stateName, $read, $write, $launch, $cleanup, $now) {
        $cleanup();
        if ($action === 'capabilities') return ['code' => 200, 'resultVersion' => 1,
            'component' => 'NetworkProbeComponent', 'transports' => ['tcp'],
            'stages' => ['tcp-connect', 'tcp-exchange', 'http-head', 'http-request', 'tls-handshake'],
            'maxTargets' => 128, 'maxThreads' => 64, 'maxReadBytes' => 8192];
        if ($action === 'startTask') {
            $plan = $get($params, 'plan', $params); $targets = $get($plan, 'targets', []);
            if (!is_array($targets) || !$targets || count($targets) > 128) return ['code' => 400, 'msg' => 'plan.targets required'];
            $stages = array_values(array_unique(array_map('strtolower', (array)$get($plan, 'stages', ['tcp-connect']))));
            $allowed = ['tcp-connect', 'tcp-exchange', 'http-head', 'http-request', 'tls-handshake'];
            foreach ($stages as $stage) if (!in_array($stage, $allowed, true)) return ['code' => 400, 'msg' => 'unsupported probe stage'];
            $taskId = substr(hash('sha256', uniqid('', true) . '|' . mt_rand()), 0, 32);
            $directory = $path($taskId); if (!is_dir($base) && !@mkdir($base, 0700, true) && !is_dir($base)) return ['code' => 500, 'msg' => 'state directory unavailable'];
            if (!@mkdir($directory, 0700, true)) return ['code' => 500, 'msg' => 'task directory unavailable'];
            $normalized = []; foreach ($targets as $target) {
                if (!is_array($target) || empty($target['host'])) return ['code' => 400, 'msg' => 'invalid target'];
                $target['port'] = (int)($target['port'] ?? 0); if ($target['port'] < 1 || $target['port'] > 65535) return ['code' => 400, 'msg' => 'invalid target port'];
                $normalized[] = $target;
            }
            $time = $now(); $config = ['targets' => $normalized, 'stages' => $stages,
                'timeout' => max(100, min(300000, (int)$get($get($plan, 'limits', []), 'timeout', 3000))),
                'maxReadBytes' => max(256, min(8192, (int)$get($get($plan, 'limits', []), 'maxReadBytes', 8192)))];
            $status = ['taskId' => $taskId, 'resultVersion' => 1, 'scanKind' => 'network-probe', 'status' => 'RUNNING',
                'total' => count($normalized), 'completed' => 0, 'targets' => $normalized, 'plan' => $config,
                'observations' => [], 'errors' => [], 'createdAt' => $time, 'updatedAt' => $time];
            $write($directory . DIRECTORY_SEPARATOR . $stateName('config'), $config); $write($directory . DIRECTORY_SEPARATOR . $stateName('status'), $status);
            if (!$launch($directory)) { foreach ((array)glob($directory . DIRECTORY_SEPARATOR . '*') as $file) @unlink($file); @rmdir($directory); return ['code' => 503, 'msg' => 'worker unavailable']; }
            return ['code' => 200, 'taskId' => $taskId];
        }
        $taskId = (string)$get($params, 'taskId', ''); $directory = $path($taskId); $statusFile = $directory . DIRECTORY_SEPARATOR . $stateName('status');
        $status = $read($statusFile); if (!is_array($status)) return ['code' => 404, 'msg' => 'task not found'];
        if ($action === 'queryTask') return ['code' => 200, 'result' => $status];
        if ($action === 'pauseTask' || $action === 'resumeTask') {
            $expected = $action === 'pauseTask' ? 'RUNNING' : 'PAUSED'; if (($status['status'] ?? '') !== $expected) return ['code' => 409, 'msg' => 'invalid task state'];
            $status['status'] = $action === 'pauseTask' ? 'PAUSED' : 'RUNNING'; $status['updatedAt'] = $now(); $write($statusFile, $status); return ['code' => 200, 'status' => $status['status']];
        }
        if ($action === 'stopTask') { $status['status'] = 'STOPPED'; $status['finishedAt'] = $now(); $status['updatedAt'] = $status['finishedAt']; $write($statusFile, $status); return ['code' => 200, 'status' => 'STOPPED']; }
        return ['code' => 400, 'msg' => 'unsupported network probe action'];
    }
];
