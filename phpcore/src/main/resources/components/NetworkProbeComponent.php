<?php
/* Unified node-side network probe runtime. The service submits a bounded plan
 * and receives observations; rule evaluation remains on the service side. */
// Task storage: atomic snapshots and one lock per task.
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
    try {
        if (@file_put_contents($temporary, $encoded, LOCK_EX) !== strlen($encoded)) return false;
        @chmod($temporary, 0600);
        return @rename($temporary, $file);
    } finally {
        if (is_file($temporary)) @unlink($temporary);
    }
};
$statusUpdate = static function ($file, $callback) use ($read, $write) {
    $lock = @fopen($file . '.lock', 'c');
    if (!is_resource($lock)) return false;
    try {
        if (!@flock($lock, LOCK_EX)) return false;
        $status = $read($file);
        $result = is_array($status) ? $callback($status) : false;
        return $result !== false && $write($file, $status) ? $result : false;
    } finally {
        @flock($lock, LOCK_UN);
        @fclose($lock);
    }
};
$releaseState = static function ($directory, $requireStopped = true) use ($read, $stateName) {
    $statusFile = $directory . DIRECTORY_SEPARATOR . $stateName('status');
    $lock = @fopen($statusFile . '.lock', 'c');
    if (!is_resource($lock)) return ['code' => 500, 'msg' => 'task release lock unavailable'];
    try {
        if (!@flock($lock, LOCK_EX)) return ['code' => 500, 'msg' => 'task release lock unavailable'];
        $latest = $read($statusFile);
        if ($requireStopped && (!is_array($latest) || ($latest['status'] ?? '') !== 'STOPPED')) {
            return ['code' => 409, 'msg' => 'network probe task is still active'];
        }
        foreach ((array)glob($directory . DIRECTORY_SEPARATOR . '*') as $file) @unlink($file);
        $released = @rmdir($directory) || !is_dir($directory);
        return $released ? ['code' => 200] : ['code' => 500, 'msg' => 'task release failed'];
    } finally {
        @flock($lock, LOCK_UN);
        @fclose($lock);
    }
};
$bounded = static function ($value, $min, $max) { return max($min, min($max, (int)$value)); };
$waitRunning = static function ($statusFile) use ($read) {
    while (true) {
        $status = $read($statusFile);
        if (!is_array($status) || ($status['status'] ?? '') === 'STOPPED') return false;
        if (($status['status'] ?? '') !== 'PAUSED') return true;
        usleep(250000);
    }
};
$launch = static function ($directory, $workerIndex = 0, $workerCount = 1) use ($workerToken) {
    $php = defined('PHP_BINARY') && PHP_BINARY !== '' ? PHP_BINARY : 'php';
    $runner = escapeshellarg($php) . ' ' . escapeshellarg(__FILE__) . ' ' .
        escapeshellarg($workerToken) . ' ' . escapeshellarg($directory) . ' ' .
        escapeshellarg((string)$workerIndex) . ' ' . escapeshellarg((string)$workerCount);
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
// Probe I/O keeps raw bytes until text evidence is built; persisted text must be UTF-8.
$sanitize = static function ($value, $limit = 4096) {
    $value = (string)$value;
    if (preg_match('//u', $value) !== 1) {
        $value = preg_replace_callback('/[\x80-\xFF]/', static function ($match) {
            $byte = ord($match[0]);
            return chr(0xC0 | ($byte >> 6)) . chr(0x80 | ($byte & 0x3F));
        }, $value);
    }
    $value = substr($value, 0, $limit);
    while ($value !== '' && preg_match('//u', $value) !== 1) $value = substr($value, 0, -1);
    return preg_replace('/[\x00-\x08\x0B\x0C\x0E-\x1F\x7F]/', '', $value);
};
$hostForUrl = static function ($host) {
    return strpos($host, ':') !== false && strpos($host, '[') !== 0 ? '[' . $host . ']' : $host;
};
$readBytes = static function ($stream, $max) {
    $data = '';
    while (strlen($data) < $max && !feof($stream)) {
        $chunk = @fread($stream, min(2048, $max - strlen($data)));
        if ($chunk === false || $chunk === '') break;
        $data .= $chunk;
    }
    return $data;
};
$writeBytes = static function ($stream, $data) {
    $offset = 0;
    while ($offset < strlen($data)) {
        $written = @fwrite($stream, substr($data, $offset));
        if ($written === false || $written === 0) throw new RuntimeException('request write failed');
        $offset += $written;
    }
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
$probe = static function ($target, $stage, $timeout, $maxRead) use ($observation, $readBytes, $writeBytes, $sanitize, $hostForUrl, $bounded) {
    $started = (int)round(microtime(true) * 1000);
    $host = (string)($target['host'] ?? ''); $port = (int)($target['port'] ?? 0);
    if (!in_array($stage, ['tcp-connect', 'tcp-exchange', 'http-head', 'http-request'], true)) {
        return $observation($target, $stage, 'error', $started, 'unsupported probe stage');
    }
    $timeout = $bounded($timeout, 100, 300000);
    $maxRead = $bounded($maxRead, 256, 8192);
    $scheme = $stage !== 'tcp-connect' && strtolower((string)($target['protocol'] ?? 'tcp')) === 'https' ? 'ssl' : 'tcp';
    $address = $scheme . '://' . $hostForUrl($host) . ':' . $port;
    $stream = null;
    try {
        $context = stream_context_create(['ssl' => ['verify_peer' => false, 'verify_peer_name' => false]]);
        $errno = 0; $error = '';
        $stream = @stream_socket_client($address, $errno, $error, $timeout / 1000.0, STREAM_CLIENT_CONNECT, $context);
        if (!is_resource($stream)) return $observation($target, $stage, 'closed', $started, $error ?: 'connection failed');
        stream_set_timeout($stream, intdiv($timeout, 1000), ($timeout % 1000) * 1000);
        if ($stage === 'tcp-connect') {
            return $observation($target, $stage, 'open', $started);
        }
        if ($stage === 'tcp-exchange') {
            $request = (string)($target['request'] ?? '');
            if ($request !== '') $writeBytes($stream, $request);
            $banner = $readBytes($stream, $maxRead);
            return $observation($target, $stage, 'open', $started, '',
                ['bytes' => strlen($banner), 'banner' => $sanitize($banner)]);
        }
        if ($stage === 'http-request' && !is_array($target['httpRequest'] ?? null)) {
            throw new InvalidArgumentException('httpRequest is required');
        }
        $request = $stage === 'http-request' ? $target['httpRequest'] : [];
        $url = parse_url((string)($target['baseUrl'] ?? '')) ?: [];
        $defaultPath = ($url['path'] ?? '/') . (isset($url['query']) ? '?' . $url['query'] : '');
        // http-head is retained as the protocol stage identifier. Use a small
        // GET so service discovery can extract the document title.
        $method = strtoupper((string)($request['method'] ?? 'GET'));
        $pathValue = (string)($request['path'] ?? $defaultPath); if ($pathValue === '') $pathValue = '/';
        $headerSource = $stage === 'http-request' ? $request : $target;
        $headers = is_array($headerSource['headers'] ?? null) ? $headerSource['headers'] : [];
        $headers['Host'] = $headers['Host'] ?? ($hostForUrl($host) . ':' . $port);
        $headers['User-Agent'] = $headers['User-Agent'] ?? '';
        $headers['Connection'] = $headers['Connection'] ?? 'close';
        $raw = $method . ' ' . $pathValue . " HTTP/1.1\r\n";
        foreach ($headers as $name => $value) {
            if (strpos((string)$name, "\r") !== false || strpos((string)$name, "\n") !== false) continue;
            $raw .= $name . ': ' . $value . "\r\n";
        }
        $body = (string)($request['body'] ?? '');
        if ($body !== '') $raw .= 'Content-Length: ' . strlen($body) . "\r\n";
        $raw .= "\r\n" . $body;
        $writeBytes($stream, $raw);
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
            'bodyLength' => strlen($bodyBlock),
            'truncated' => strlen($response) >= $maxRead
        ];
        if ($stage === 'http-request') {
            $evidence['headers'] = $sanitize($headerBlock);
            $evidence['body'] = $sanitize($bodyBlock);
        }
        if (preg_match('/(?:^|\r?\n)Content-Length:\s*(\d+)/i', $headerBlock, $match)) {
            $evidence['responseSize'] = (int)$match[1];
        } else {
            $evidence['responseSize'] = strlen($bodyBlock);
        }
        if (preg_match('/(?:^|\r?\n)Server:\s*([^\r\n]+)/i', $headerBlock, $match)) $evidence['server'] = $sanitize($match[1], 512);
        if (preg_match('/(?:^|\r?\n)Content-Type:\s*([^\r\n]+)/i', $headerBlock, $match)) $evidence['contentType'] = $sanitize($match[1], 512);
        if (preg_match('/(?:^|\r?\n)Location:\s*([^\r\n]+)/i', $headerBlock, $match)) $evidence['location'] = $sanitize($match[1], 512);
        if (preg_match('/<title\b[^>]*>(.*?)<\/title\s*>/is', $bodyBlock, $match)) {
            $title = trim(preg_replace('/\s+/', ' ', strip_tags(html_entity_decode($match[1], ENT_QUOTES, 'UTF-8'))));
            if ($title !== '') $evidence['title'] = $sanitize($title, 512);
        }
        return $observation($target, $stage, $status > 0 ? 'open' : 'error', $started, '', $evidence);
    } catch (Throwable $error) {
        return $observation($target, $stage, 'error', $started, $error->getMessage());
    } finally { if (is_resource($stream)) fclose($stream); }
};
// Workers only execute plans and append bounded batches; missing state means released.
$worker = static function ($directory, $workerIndex = 0, $workerCount = 1) use ($read, $statusUpdate, $stateName, $probe, $now, $waitRunning) {
    $config = $read($directory . DIRECTORY_SEPARATOR . $stateName('config'));
    $statusFile = $directory . DIRECTORY_SEPARATOR . $stateName('status');
    if (!is_array($config)) return 2;
    $status = $read($statusFile); if (!is_array($status)) return 3;
    $stages = is_array($config['stages'] ?? null) ? $config['stages'] : ['tcp-connect'];
    $timeout = max(100, min(300000, (int)($config['timeout'] ?? 3000)));
    $maxRead = max(256, min(8192, (int)($config['maxReadBytes'] ?? 8192)));
    $workerIndex = max(0, (int)$workerIndex);
    $workerCount = max(1, (int)$workerCount);
    $pending = [];
    $processed = 0;
    $lastFlush = $now();
    $flush = static function () use (&$pending, &$processed, &$lastFlush, $statusFile, $statusUpdate, $now) {
        if ($processed < 1) return true;
        $batch = $pending;
        $count = $processed;
        $committed = $statusUpdate($statusFile, static function (&$latest) use ($batch, $count, $now) {
            if (($latest['status'] ?? '') === 'STOPPED') return false;
            $observations = is_array($latest['observations'] ?? null) ? $latest['observations'] : [];
            $errors = is_array($latest['errors'] ?? null) ? $latest['errors'] : [];
            foreach ($batch as $observation) {
                $observations[] = $observation;
                if (($observation['state'] ?? '') === 'error') {
                    $errors[] = [
                        'target' => $observation['target'] ?? '',
                        'stage' => $observation['stage'] ?? '',
                        'errorCode' => $observation['errorCode'] ?? '',
                        'error' => $observation['error'] ?? ''
                    ];
                }
            }
            $latest['observations'] = $observations;
            $latest['errors'] = $errors;
            $latest['completed'] = (int)($latest['completed'] ?? 0) + $count;
            $latest['updatedAt'] = $now();
            return true;
        });
        if ($committed === false) return false;
        $pending = [];
        $processed = 0;
        $lastFlush = $now();
        return true;
    };
    foreach ((array)($config['targets'] ?? []) as $index => $target) {
        if (((int)$index % $workerCount) !== $workerIndex) continue;
        if (!$waitRunning($statusFile)) return 0;
        $targetStages = !empty($target['stage']) ? [(string)$target['stage']] : $stages;
        $targetObservations = [];
        foreach ($targetStages as $stage) {
            if (!$waitRunning($statusFile)) return 0;
            $targetObservations[] = $probe($target, $stage, (int)($target['timeout'] ?? $timeout),
                (int)($target['maxReadBytes'] ?? $maxRead));
        }
        foreach ($targetObservations as $observation) $pending[] = $observation;
        $processed++;
        $latest = $read($statusFile);
        if (!is_array($latest) || ($latest['status'] ?? '') === 'STOPPED') return 0;
        if (($processed >= 32 || $now() - $lastFlush >= 250 || ($latest['status'] ?? '') === 'PAUSED') && !$flush()) return 0;
    }
    if (!$flush()) return 0;
    $statusUpdate($statusFile, static function (&$latest) use ($now) {
        if (($latest['status'] ?? '') === 'STOPPED') return true;
        $latest['workersDone'] = (int)($latest['workersDone'] ?? 0) + 1;
        if ((int)($latest['workersDone'] ?? 0) >= max(1, (int)($latest['workers'] ?? 1))) {
            $latest['status'] = 'STOPPED'; $latest['outcome'] = 'COMPLETED';
            $latest['finishedAt'] = $now();
        }
        $latest['updatedAt'] = $now();
        return true;
    });
    return 0;
};
if (PHP_SAPI === 'cli' && isset($argv[1]) && hash_equals($workerToken, (string)$argv[1])) {
    exit($worker(isset($argv[2]) ? $argv[2] : '', isset($argv[3]) ? $argv[3] : 0,
        isset($argv[4]) ? $argv[4] : 1));
}
$cleanup = static function () use ($base, $read, $stateName, $releaseState, $now) {
    if (!is_dir($base)) return 0; $count = 0;
    foreach ((array)glob($base . DIRECTORY_SEPARATOR . '*') as $directory) {
        if (!is_dir($directory)) { @unlink($directory); continue; }
        $status = $read($directory . DIRECTORY_SEPARATOR . $stateName('status'));
        $time = is_array($status) ? (int)($status['finishedAt'] ?? 0) : (int)@filemtime($directory) * 1000;
        if ((!is_array($status) || ($status['status'] ?? '') === 'STOPPED')
            && $time > 0 && $now() - $time > 1800000) {
            $releaseState($directory, false);
            continue;
        }
        $count++;
    }
    return $count;
};
// Action dispatch owns task creation, control, acknowledgement and release.
return [
    'id' => 'NetworkProbeComponent', 'version' => '1.0.0',
    'handle' => static function ($action, $params) use ($get, $base, $path, $stateName, $read, $write, $launch, $cleanup, $now, $statusUpdate, $releaseState, $bounded) {
        $cleanup();
        if ($action === 'startTask') {
            $plan = $get($params, 'plan', $params); $targets = $get($plan, 'targets', []);
            if (!is_array($targets)) return ['code' => 400, 'msg' => 'targets must be a list'];
            foreach ($targets as $target) {
                if (!is_array($target) || empty($target['host']) || (int)($target['port'] ?? 0) < 1 || (int)$target['port'] > 65535) {
                    return ['code' => 400, 'msg' => 'target requires a host and valid port'];
                }
            }
            $stages = array_values(array_unique(array_map('strtolower', (array)$get($plan, 'stages', ['tcp-connect']))));
            if (!$stages) $stages = ['tcp-connect'];
            $limits = $get($plan, 'limits', []);
            $threads = max(1, min(64, (int)$get($limits, 'threads', 64)));
            $workerCount = min($threads, count($targets));
            $taskId = substr(hash('sha256', uniqid('', true) . '|' . mt_rand()), 0, 32);
            $directory = $path($taskId); if (!is_dir($base) && !@mkdir($base, 0700, true) && !is_dir($base)) return ['code' => 500, 'msg' => 'state directory unavailable'];
            if (!@mkdir($directory, 0700, true)) return ['code' => 500, 'msg' => 'task directory unavailable'];
            $normalized = array_values($targets);
            $time = $now(); $config = ['targets' => $normalized, 'stages' => $stages,
                'timeout' => $bounded($get($limits, 'timeout', 3000), 100, 300000),
                'maxReadBytes' => $bounded($get($limits, 'maxReadBytes', 8192), 256, 8192), 'workers' => $workerCount];
            $status = ['taskId' => $taskId, 'scanKind' => 'network-probe', 'status' => 'RUNNING', 'outcome' => 'RUNNING',
                'total' => count($normalized), 'completed' => 0,
                'observations' => [], 'errors' => [], 'observationOffset' => 0,
                'createdAt' => $time, 'updatedAt' => $time, 'workers' => $workerCount, 'workersDone' => 0];
            if (!$normalized) {
                $status['status'] = 'STOPPED'; $status['outcome'] = 'COMPLETED'; $status['finishedAt'] = $time;
            }
            if (!$write($directory . DIRECTORY_SEPARATOR . $stateName('config'), $config)
                || !$write($directory . DIRECTORY_SEPARATOR . $stateName('status'), $status)) {
                $releaseState($directory, false);
                return ['code' => 500, 'msg' => 'task state initialization failed'];
            }
            for ($workerIndex = 0; $workerIndex < $workerCount; $workerIndex++) {
                if (!$launch($directory, $workerIndex, $workerCount)) {
                    // Already launched workers exit when the status file disappears.
                    $releaseState($directory, false);
                    return ['code' => 503, 'msg' => 'worker unavailable'];
                }
            }
            return ['code' => 200, 'taskId' => $taskId];
        }
        $taskId = (string)$get($params, 'taskId', ''); $directory = $path($taskId); $statusFile = $directory . DIRECTORY_SEPARATOR . $stateName('status');
        $status = $read($statusFile); if (!is_array($status)) return ['code' => 404, 'msg' => 'task not found'];
        if ($action === 'queryTask') {
            $cursor = max(0, (int)$get($params, 'cursor', 0));
            $maxItems = max(1, min(512, (int)$get($params, 'maxItems', 128)));
            $maxBytes = max(4096, min(1048576, (int)$get($params, 'maxBytes', 524288)));
            $includeEvidence = $get($params, 'includeEvidence', true) !== false;
            $base = max(0, (int)($status['observationOffset'] ?? 0));
            $observations = is_array($status['observations'] ?? null) ? $status['observations'] : [];
            $requested = max($cursor, $base);
            $start = max(0, min(count($observations), $requested - $base));
            $page = []; $bytes = 0; $index = $start;
            while ($index < count($observations) && count($page) < $maxItems) {
                $item = is_array($observations[$index]) ? $observations[$index] : [];
                if (!$includeEvidence) unset($item['evidence']);
                $estimate = strlen((string)json_encode($item));
                if ($page && $bytes + $estimate > $maxBytes) break;
                $page[] = $item; $bytes += $estimate; $index++;
            }
            $snapshot = [
                'taskId' => $status['taskId'] ?? $taskId,
                'scanKind' => $status['scanKind'] ?? 'network-probe',
                'status' => $status['status'] ?? 'RUNNING',
                'outcome' => $status['outcome'] ?? 'RUNNING',
                'total' => (int)($status['total'] ?? 0),
                'completed' => (int)($status['completed'] ?? 0),
                'progress' => (int)($status['total'] ?? 0) > 0
                    ? min(100, (int)($status['completed'] ?? 0) * 100 / (int)$status['total']) : 0,
                'cursor' => $cursor,
                'nextCursor' => $base + $index,
                'hasMore' => $index < count($observations),
                'incremental' => true,
                'observations' => $page,
                'errors' => array_slice(is_array($status['errors'] ?? null) ? $status['errors'] : [], 0, $maxItems),
                'createdAt' => $status['createdAt'] ?? null,
                'finishedAt' => $status['finishedAt'] ?? null
            ];
            return ['code' => 200, 'result' => $snapshot];
        }
        if ($action === 'ackTask') {
            $cursor = max(0, (int)$get($params, 'cursor', 0));
            $result = $statusUpdate($statusFile, static function (&$latest) use ($cursor, $now) {
                $base = max(0, (int)($latest['observationOffset'] ?? 0));
                $observations = is_array($latest['observations'] ?? null) ? $latest['observations'] : [];
                $bounded = max($base, min($cursor, $base + count($observations)));
                $remove = max(0, $bounded - $base);
                if ($remove > 0) {
                    $latest['observations'] = array_slice($observations, $remove);
                    $latest['observationOffset'] = $bounded;
                    $latest['updatedAt'] = $now();
                }
                return ['code' => 200, 'cursor' => $bounded];
            });
            return $result === false ? ['code' => 500, 'msg' => 'task acknowledgement failed'] : $result;
        }
        if ($action === 'pauseTask' || $action === 'resumeTask') {
            $expected = $action === 'pauseTask' ? 'RUNNING' : 'PAUSED';
            $result = $statusUpdate($statusFile, static function (&$latest) use ($expected, $action, $now) {
                if (($latest['status'] ?? '') !== $expected) return ['code' => 409, 'msg' => 'invalid task state'];
                $latest['status'] = $action === 'pauseTask' ? 'PAUSED' : 'RUNNING';
                $latest['updatedAt'] = $now();
                return ['code' => 200, 'status' => $latest['status']];
            });
            return $result === false ? ['code' => 500, 'msg' => 'task state update failed'] : $result;
        }
        if ($action === 'stopTask') {
            $result = $statusUpdate($statusFile, static function (&$latest) use ($now) {
                if (($latest['status'] ?? '') !== 'STOPPED') {
                    $latest['status'] = 'STOPPED'; $latest['outcome'] = 'CANCELLED';
                    $latest['finishedAt'] = $now(); $latest['updatedAt'] = $latest['finishedAt'];
                }
                return ['code' => 200, 'status' => 'STOPPED'];
            });
            return $result === false ? ['code' => 500, 'msg' => 'task stop failed'] : $result;
        }
        if ($action === 'releaseTask') return $releaseState($directory);
        return ['code' => 400, 'msg' => 'unsupported network probe action'];
    }
];
