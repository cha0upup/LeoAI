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
// Control publishes an immutable, bounded manifest. Segment data/index tails are
// append-only; only byte ranges recorded in that manifest are visible to readers.
$emptyResults = ['segments' => [], 'errors' => [], 'nextSegment' => 0];
$resultPath = static function ($statusFile, $version) use ($stateName) {
    return dirname($statusFile) . DIRECTORY_SEPARATOR . $stateName('results') . '.' . $version;
};
$segmentPath = static function ($statusFile, $id) use ($stateName) {
    return dirname($statusFile) . DIRECTORY_SEPARATOR . $stateName('segment') . '.' . $id;
};
$appendFile = static function ($file, $committedBytes, $content, $existing) {
    $stream = @fopen($file, $existing ? 'r+b' : 'x+b');
    if (!is_resource($stream)) throw new RuntimeException('segment open failed');
    try {
        if (!$existing) @chmod($file, 0600);
        $stat = @fstat($stream); if (!is_array($stat)) throw new RuntimeException('segment stat failed');
        $size = $stat['size'];
        if ($size < $committedBytes || ($size > $committedBytes && !@ftruncate($stream, $committedBytes))
            || @fseek($stream, $committedBytes) !== 0) throw new RuntimeException('segment length mismatch');
        $length = strlen($content); $written = 0;
        while ($written < $length) {
            $count = @fwrite($stream, substr($content, $written));
            if ($count === false || $count === 0) throw new RuntimeException('segment write failed');
            $written += $count;
        }
        if ($length > 0 && !@fflush($stream)) throw new RuntimeException('segment flush failed');
    } finally { fclose($stream); }
};
$readSlice = static function ($stream, $offset, $length) {
    if (@fseek($stream, $offset) !== 0) throw new RuntimeException('segment seek failed');
    $bytes = '';
    while (strlen($bytes) < $length) {
        $part = @fread($stream, $length - strlen($bytes));
        if ($part === false || $part === '') throw new RuntimeException('incomplete committed segment');
        $bytes .= $part;
    }
    return $bytes;
};
$segmentEntries = static function ($file, $segment, $first, $count) use ($readSlice) {
    $stream = @fopen($file . '.idx', 'rb');
    if (!is_resource($stream)) throw new RuntimeException('segment index unavailable');
    try {
        $raw = $readSlice($stream, ($first - $segment['first']) * 8, $count * 8); $entries = [];
        for ($i = 0; $i < $count; $i++) {
            $entry = unpack('Noffset/Nlength', substr($raw, $i * 8, 8));
            if ($entry['length'] < 3 || $entry['offset'] + $entry['length'] > $segment['bytes'])
                throw new RuntimeException('invalid segment index');
            $entries[] = $entry;
        }
        return $entries;
    } finally { fclose($stream); }
};
$collectSegments = static function ($file, $segments) use ($segmentPath) {
    $keep = [];
    foreach ($segments as $segment) {
        $name = $segmentPath($file, $segment['id']); $keep[$name] = true; $keep[$name . '.idx'] = true;
    }
    foreach ((array)glob($segmentPath($file, '*')) as $name) {
        if (!isset($keep[$name]) && !@unlink($name)) throw new RuntimeException('segment collection failed');
    }
};
$validateResults = static function ($status, $data) {
    if (!isset($status['observationOffset'], $status['pendingItems'], $status['pendingBytes'])
        || !is_int($status['observationOffset']) || !is_int($status['pendingItems']) || !is_int($status['pendingBytes'])
        || !is_array($data) || count($data) !== 3 || !isset($data['segments'], $data['errors'], $data['nextSegment'])
        || !is_array($data['segments']) || !is_array($data['errors']) || !is_int($data['nextSegment'])
        || count($data['segments']) > 128 || count($data['errors']) > 128) return false;
    $end = null; $lastId = -1;
    foreach ($data['segments'] as $segment) {
        foreach (['id', 'first', 'count', 'bytes'] as $key) if (!isset($segment[$key]) || !is_int($segment[$key])) return false;
        if ($segment['id'] <= $lastId || $segment['first'] < 0 || $segment['count'] < 1 || $segment['count'] > 128
            || $segment['bytes'] < $segment['count'] * 3 || $segment['bytes'] > 4194304
            || ($end !== null && $segment['first'] !== $end)) return false;
        $lastId = $segment['id']; $end = $segment['first'] + $segment['count'];
    }
    $base = (int)($status['observationOffset'] ?? 0); $pending = (int)($status['pendingItems'] ?? 0);
    return $data['nextSegment'] > $lastId && $base >= 0 && $pending >= 0 && $pending <= 4096
        && ($end === null ? $pending === 0 : $pending > 0 && $end === $base + $pending
            && $data['segments'][0]['first'] <= $base && $data['segments'][0]['first'] + $data['segments'][0]['count'] > $base);
};
$appendRows = static function ($file, &$status, $encodedRows) use ($segmentPath, $appendFile, $collectSegments) {
    if (!$encodedRows) return;
    $collectSegments($file, $status['segments']);
    // Repair an uncommitted tail left by a failed publication before appending.
    if ($status['segments']) {
        $tail = $status['segments'][count($status['segments']) - 1]; $name = $segmentPath($file, $tail['id']);
        $appendFile($name, $tail['bytes'], '', true); $appendFile($name . '.idx', $tail['count'] * 8, '', true);
    }
    $cursor = $status['observationOffset'] + $status['pendingItems']; $position = 0;
    while ($position < count($encodedRows)) {
        $last = count($status['segments']) - 1; $nextLength = strlen($encodedRows[$position]) + 1;
        if ($last < 0 || $status['segments'][$last]['count'] >= 128
            || $status['segments'][$last]['bytes'] + $nextLength > 262144) {
            $status['segments'][] = ['id' => $status['nextSegment']++, 'first' => $cursor, 'count' => 0, 'bytes' => 0]; $last++;
        }
        $segment = &$status['segments'][$last]; $oldCount = $segment['count']; $oldBytes = $segment['bytes'];
        $payload = ''; $index = '';
        do {
            $row = $encodedRows[$position]; $length = strlen($row) + 1;
            if ($segment['count'] > 0 && ($segment['count'] >= 128 || $segment['bytes'] + $length > 262144)) break;
            $index .= pack('NN', $segment['bytes'], $length); $payload .= $row . "\n";
            $segment['bytes'] += $length; $segment['count']++; $position++; $cursor++;
        } while ($position < count($encodedRows));
        $name = $segmentPath($file, $segment['id']);
        $appendFile($name, $oldBytes, $payload, $oldCount > 0);
        $appendFile($name . '.idx', $oldCount * 8, $index, $oldCount > 0);
        unset($segment);
    }
    $status['pendingItems'] += count($encodedRows);
};
$readPage = static function ($file, $status, $cursor, $maxItems, $maxBytes, $includeEvidence) use ($segmentPath, $segmentEntries, $readSlice) {
    $end = $status['observationOffset'] + $status['pendingItems'];
    $next = max($status['observationOffset'], min($cursor, $end)); $page = []; $bytes = 0;
    $segments = $status['segments']; $low = 0; $high = count($segments);
    while ($low < $high) {
        $middle = (int)(($low + $high) / 2); $segment = $segments[$middle];
        if ($segment['first'] + $segment['count'] <= $next) $low = $middle + 1; else $high = $middle;
    }
    for ($i = $low; $i < count($segments) && count($page) < $maxItems; $i++) {
        $segment = $segments[$i]; $name = $segmentPath($file, $segment['id']);
        $count = min($segment['first'] + $segment['count'] - $next, $maxItems - count($page));
        $entries = $segmentEntries($name, $segment, $next, $count);
        $stream = @fopen($name, 'rb'); if (!is_resource($stream)) throw new RuntimeException('segment data unavailable');
        try {
            foreach ($entries as $entry) {
                $estimate = $entry['length'] - 1;
                if ($includeEvidence && $page && $bytes + $estimate > $maxBytes) break 2;
                $raw = $readSlice($stream, $entry['offset'], $entry['length']);
                $row = json_decode(substr($raw, 0, -1), true);
                if (substr($raw, -1) !== "\n" || !is_array($row)) throw new RuntimeException('invalid committed record');
                if (!$includeEvidence) { unset($row['evidence']); $estimate = strlen((string)json_encode($row)); }
                if ($page && $bytes + $estimate > $maxBytes) break 2;
                $page[] = $row; $bytes += $estimate; $next++;
            }
        } finally { fclose($stream); }
    }
    return [$page, $next, $next < $end];
};
$statusUpdate = static function ($file, $callback, $withResults = false) use ($read, $write, $emptyResults, $resultPath, $collectSegments, $validateResults) {
    $lock = @fopen($file . '.lock', 'c'); if (!is_resource($lock)) return false;
    try {
        if (!@flock($lock, LOCK_EX)) return false;
        $status = $read($file); if (!is_array($status)) return false;
        $previous = $status; $dataKeys = array_fill_keys(array_keys($emptyResults), true);
        $reserved = $dataKeys + ['observations' => true, 'observationSizes' => true];
        if (array_intersect_key($status, $reserved)) return false;
        if ($withResults) {
            if (!isset($status['resultVersion']) || !is_int($status['resultVersion']) || $status['resultVersion'] < 0) return false;
            $dataFile = $resultPath($file, $status['resultVersion']); $data = $read($dataFile);
            if (!$validateResults($status, $data)) return false;
            $status += $data;
        }
        $result = $callback($status); if ($result === false) return false;
        $nextFile = null;
        if ($withResults) {
            $nextData = array_intersect_key($status, $dataKeys); $status = array_diff_key($status, $dataKeys);
            if (array_intersect_key($status, $reserved) || !$validateResults($status, $nextData)) return false;
            if ($nextData !== $data) {
                foreach ((array)glob($resultPath($file, '*')) as $old) if ($old !== $dataFile && !@unlink($old)) return false;
                $nextFile = $resultPath($file, ++$status['resultVersion']);
                if (!$write($nextFile, $nextData)) return false;
            }
        } elseif (array_intersect_key($status, $reserved)) return false;
        if ($status !== $previous && !$write($file, $status)) {
            if ($nextFile !== null) @unlink($nextFile); return false;
        }
        if ($nextFile !== null) @unlink($dataFile);
        if ($withResults && $status !== $previous) {
            try { $collectSegments($file, $nextData['segments']); } catch (Throwable $ignored) { }
        }
        return $result;
    } catch (Throwable $error) { return false; }
    finally { @flock($lock, LOCK_UN); @fclose($lock); }
};
$bufferLimits = ['maxItems' => 4096, 'maxBytes' => 4194304, 'highItems' => 3072,
    'lowItems' => 2048, 'highBytes' => 3145728, 'lowBytes' => 2097152];
$refreshPressure = static function (&$status) use ($bufferLimits) {
    $count = (int)$status['pendingItems']; $bytes = (int)($status['pendingBytes'] ?? 0);
    if ($count >= $bufferLimits['highItems'] || $bytes >= $bufferLimits['highBytes']) $status['backpressured'] = true;
    else if ($count <= $bufferLimits['lowItems'] && $bytes <= $bufferLimits['lowBytes']) $status['backpressured'] = false;
};
$acknowledge = static function (&$status, $cursor, $file) use ($now, $refreshPressure, $segmentPath, $segmentEntries, $collectSegments) {
    $base = $status['observationOffset']; $end = $base + $status['pendingItems'];
    $accepted = max($base, min($cursor, $end));
    if ($accepted > $base) {
        $collectSegments($file, $status['segments']); $bytes = 0;
        foreach ($status['segments'] as $segment) {
            $first = max($base, $segment['first']); $last = min($accepted, $segment['first'] + $segment['count']);
            if ($last <= $first) continue;
            if ($first === $segment['first'] && $last === $segment['first'] + $segment['count']) $bytes += $segment['bytes'] - $segment['count'];
            else foreach ($segmentEntries($segmentPath($file, $segment['id']), $segment, $first, $last - $first) as $entry) $bytes += $entry['length'] - 1;
        }
        $status['segments'] = array_values(array_filter($status['segments'], static function ($segment) use ($accepted) {
            return $segment['first'] + $segment['count'] > $accepted;
        }));
        $status['pendingItems'] -= $accepted - $base; $status['pendingBytes'] = max(0, $status['pendingBytes'] - $bytes);
        $status['observationOffset'] = $accepted; $status['updatedAt'] = $now(); $refreshPressure($status);
    }
    return $accepted;
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
// One process budget per component runtime, shared across tasks and HTTP requests.
$processLimit = 16;
$poolFile = $base . DIRECTORY_SEPARATOR . $stateName('pool');
$poolUpdate = static function ($callback) use ($base, $poolFile, $read, $write) {
    if (!is_dir($base) && !@mkdir($base, 0700, true) && !is_dir($base)) return false;
    $lock = @fopen($poolFile . '.lock', 'c');
    if (!is_resource($lock)) return false;
    try {
        if (!@flock($lock, LOCK_EX)) return false;
        $pool = $read($poolFile) ?? ['queue' => [], 'slots' => []];
        $previous = $pool; $result = $callback($pool);
        return $result !== false && ($pool === $previous || $write($poolFile, $pool)) ? $result : false;
    } finally { @flock($lock, LOCK_UN); @fclose($lock); }
};
$slotLock = static function ($slot) use ($base, $stateName) {
    $lock = @fopen($base . DIRECTORY_SEPARATOR . $stateName('slot-' . $slot) . '.lock', 'c');
    if (!is_resource($lock)) return false;
    if (@flock($lock, LOCK_EX | LOCK_NB)) return $lock;
    @fclose($lock); return false;
};
$jobKey = static function ($job) { return $job[0] . ':' . $job[1]; };
$eligible = static function ($job) use ($path, $stateName, $read) {
    $status = $read($path($job[0]) . DIRECTORY_SEPARATOR . $stateName('status'));
    return is_array($status) && ($status['status'] ?? '') === 'RUNNING'
        && empty($status['backpressured']) && empty($status['doneWorkers'][$job[1]]);
};
$enqueue = static function (&$pool, $job) use ($jobKey, $eligible) {
    $key = $jobKey($job);
    if (isset($pool['queue'][$key]) || !$eligible($job)) return;
    foreach ($pool['slots'] as $entry) if (($entry['job'] ?? null) === $job) return;
    $pool['queue'][$key] = $job;
};
$pick = static function (&$pool) use ($eligible) {
    foreach ($pool['queue'] as $key => $job) {
        unset($pool['queue'][$key]);
        if ($eligible($job)) return $job;
    }
    return null;
};
$failJob = static function ($job, $message) use ($path, $stateName, $statusUpdate, $now) {
    if ($job === null) return;
    $statusUpdate($path($job[0]) . DIRECTORY_SEPARATOR . $stateName('status'), static function (&$status) use ($message, $now) {
        if (($status['status'] ?? '') === 'STOPPED') return true;
        $status['status'] = 'STOPPED'; $status['outcome'] = 'FAILED';
        $status['backpressured'] = false; $status['finishedAt'] = $now();
        $status['error'] = substr($message, 0, 4096);
        return true; // Do not replay an interrupted network operation whose outcome is unknown.
    });
};
$launch = static function ($slot, $token) use ($workerToken) {
    $php = defined('PHP_BINARY') && PHP_BINARY !== '' ? PHP_BINARY : 'php';
    $runner = escapeshellarg($php) . ' ' . escapeshellarg(__FILE__) . ' ' .
        escapeshellarg($workerToken) . ' ' . escapeshellarg((string)$slot) . ' ' . escapeshellarg($token);
    if (DIRECTORY_SEPARATOR === '\\') {
        if (!function_exists('popen')) return false;
        $handle = @popen('start /B "" ' . $runner . ' >NUL 2>&1', 'r');
        if (is_resource($handle)) { pclose($handle); return true; }
        return false;
    }
    $command = '/bin/sh -c ' . escapeshellarg($runner . ' </dev/null >/dev/null 2>&1 & echo $!');
    if (function_exists('shell_exec')) return (int)trim((string)@shell_exec($command)) > 0;
    if (function_exists('exec')) { $lines = []; $code = 1; @exec($command, $lines, $code); return $code === 0 && !empty($lines); }
    return false;
};
$pump = static function () use ($poolUpdate, $base, $stateName, $read, $slotLock, $enqueue, $pick, $eligible, $failJob, $launch, $processLimit, $now) {
    return $poolUpdate(static function (&$pool) use ($base, $stateName, $read, $slotLock, $enqueue, $pick, $eligible, $failJob, $launch, $processLimit, $now) {
        foreach ($pool['slots'] as $slot => $entry) {
            $lock = $slotLock($slot);
            if ($lock === false) continue; // A live process still owns the OS lease; never reclaim its capacity early.
            @flock($lock, LOCK_UN); @fclose($lock);
            if (!empty($entry['claimed']) || $now() >= $entry['deadline']) {
                unset($pool['slots'][$slot]);
                $failJob($entry['job'], empty($entry['claimed']) ? 'worker startup timed out' : 'worker exited unexpectedly');
            }
        }
        foreach ((array)glob($base . DIRECTORY_SEPARATOR . '*', GLOB_ONLYDIR) as $directory) {
            $status = $read($directory . DIRECTORY_SEPARATOR . $stateName('status'));
            if (!is_array($status) || ($status['status'] ?? '') !== 'RUNNING' || !empty($status['backpressured'])) continue;
            for ($i = 0; $i < min($processLimit, (int)($status['workers'] ?? 0)); $i++) $enqueue($pool, [$status['taskId'], $i]);
        }
        foreach ($pool['queue'] as $key => $job) if (!$eligible($job)) unset($pool['queue'][$key]);
        for ($slot = 0; $slot < $processLimit; $slot++) {
            if (isset($pool['slots'][$slot])) continue;
            $lock = $slotLock($slot); if ($lock === false) continue;
            @flock($lock, LOCK_UN); @fclose($lock);
            $job = $pick($pool); if ($job === null) break;
            $token = bin2hex(random_bytes(16));
            $pool['slots'][$slot] = ['token' => $token, 'job' => $job, 'claimed' => false, 'deadline' => $now() + 10000];
            if (!$launch($slot, $token)) { unset($pool['slots'][$slot]); $failJob($job, 'worker launch failed'); }
        }
        return true;
    });
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
$observation = static function ($target, $stage, $state, $started, $error = '', $evidence = [], $errorCode = '') use ($sanitize) {
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
    if ($errorCode !== '') $result['errorCode'] = $errorCode;
    if (is_array($evidence) && $evidence) $result['evidence'] = $evidence;
    return $result;
};
// HTTP header and decoded-body budgets are independent, matching the Java node.
$readHttpLine = static function ($stream) {
    $line = @fgets($stream, 8194);
    if (!is_string($line) || substr($line, -1) !== "\n" || strlen($line) > 8192)
        throw new RuntimeException('incomplete or oversized HTTP line');
    return rtrim($line, "\r\n");
};
$readExact = static function ($stream, $count) {
    $data = '';
    while (strlen($data) < $count) {
        $part = @fread($stream, min(2048, $count - strlen($data)));
        if ($part === false || $part === '') throw new RuntimeException('incomplete HTTP response body');
        $data .= $part;
    }
    return $data;
};
$readHttpResponse = static function ($stream, $method, $limit) use ($readHttpLine, $readExact, $readBytes) {
    $headerBytes = 0;
    for ($interim = 0; $interim < 8; $interim++) {
        $first = $readHttpLine($stream);
        $headerBytes += strlen($first) + 2;
        if (!preg_match('/^HTTP\/1\.[01] ([0-9]{3})(?: .*)?$/', $first, $match))
            throw new RuntimeException('invalid HTTP status line');
        $status = (int)$match[1]; $fields = []; $lines = [$first];
        while (($line = $readHttpLine($stream)) !== '') {
            $headerBytes += strlen($line) + 2;
            if ($headerBytes > 8192) throw new RuntimeException('HTTP response headers exceed limit');
            $colon = strpos($line, ':');
            if ($colon === false || $colon === 0) throw new RuntimeException('invalid HTTP response header');
            $name = strtolower(substr($line, 0, $colon));
            if (!preg_match("/^[!#$%&'*+.^_`|~0-9A-Za-z-]+$/", $name))
                throw new RuntimeException('invalid HTTP response header name');
            if (!isset($fields[$name])) $fields[$name] = [];
            $fields[$name][] = trim(substr($line, $colon + 1)); $lines[] = $line;
        }
        $headerBytes += 2;
        if ($headerBytes > 8192) throw new RuntimeException('HTTP response headers exceed limit');
        if ($status >= 200 || $status === 101) break;
    }
    if ($interim === 8) throw new RuntimeException('too many interim HTTP responses');
    $header = implode("\r\n", $lines); $body = '';
    $transfer = strtolower($fields['transfer-encoding'][0] ?? '');
    if ($method === 'HEAD' || $status < 200 || $status === 204 || $status === 304) {
        return [$status, $header, $fields, '', false];
    }
    if ($transfer !== '' && $transfer !== 'chunked') throw new RuntimeException('unsupported HTTP transfer encoding');
    if ($transfer === 'chunked') {
        while (true) {
            $hex = explode(';', $readHttpLine($stream), 2)[0];
            if (!preg_match('/^[0-9a-fA-F]{1,15}$/', $hex)) throw new RuntimeException('invalid HTTP chunk size');
            $chunk = hexdec($hex);
            if ($chunk === 0) {
                $trailerBytes = 0;
                while (($line = $readHttpLine($stream)) !== '') {
                    $trailerBytes += strlen($line) + 2;
                    if ($trailerBytes > 8192) throw new RuntimeException('HTTP trailers exceed limit');
                }
                break;
            }
            $body .= $readExact($stream, min($chunk, $limit + 1 - strlen($body)));
            if (strlen($body) > $limit) break;
            if ($readExact($stream, 2) !== "\r\n") throw new RuntimeException('invalid HTTP chunk terminator');
        }
    } else if (isset($fields['content-length'][0])) {
        $length = $fields['content-length'][0];
        if (!preg_match('/^[0-9]+$/', $length)) throw new RuntimeException('invalid HTTP content length');
        $body = $readExact($stream, min((float)$length, $limit + 1));
    } else {
        $body = $readBytes($stream, $limit + 1);
    }
    $metadata = stream_get_meta_data($stream);
    $truncated = strlen($body) > $limit || !empty($metadata['timed_out']);
    $body = substr($body, 0, $limit);
    $encoding = strtolower($fields['content-encoding'][0] ?? '');
    if ($encoding === 'gzip' && function_exists('inflate_init')) {
        $inflater = @inflate_init(ZLIB_ENCODING_GZIP); $decoded = '';
        for ($offset = 0; $offset < strlen($body) && strlen($decoded) <= $limit; $offset += 128) {
            $part = @inflate_add($inflater, substr($body, $offset, 128), ZLIB_SYNC_FLUSH);
            if ($part === false) throw new RuntimeException('invalid gzip HTTP body');
            $decoded .= $part;
        }
        $truncated = $truncated || strlen($decoded) > $limit;
        if (!$truncated && function_exists('inflate_get_status') && inflate_get_status($inflater) !== ZLIB_STREAM_END)
            throw new RuntimeException('incomplete gzip HTTP body');
        $body = substr($decoded, 0, $limit);
    } else if ($encoding !== '' && $encoding !== 'identity') {
        $body = ''; $truncated = true;
    }
    return [$status, $header, $fields, $body, $truncated];
};
$probe = static function ($target, $stage, $timeout, $maxRead) use ($observation, $readBytes, $writeBytes, $sanitize, $hostForUrl, $bounded, $readHttpResponse) {
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
        $url = parse_url((string)($target['baseUrl'] ?? '')) ?: [];
        $httpHost = (string)($url['host'] ?? $host);
        $context = stream_context_create(['ssl' => ['verify_peer' => false, 'verify_peer_name' => false,
            'peer_name' => trim($httpHost, '[]'), 'SNI_enabled' => true]]);
        $errno = 0; $error = '';
        $stream = @stream_socket_client($address, $errno, $error, $timeout / 1000.0, STREAM_CLIENT_CONNECT, $context);
        if (!is_resource($stream)) return $observation($target, $stage, $stage === 'tcp-connect' ? 'closed' : 'error',
            $started, $error ?: 'connection failed', [], in_array($errno, [61, 111, 10061], true) ? 'REFUSED' : 'IO');
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
        $headers = array_change_key_case($headers, CASE_LOWER);
        $headers['host'] = $headers['host'] ?? ($hostForUrl($httpHost) . ':' . $port);
        $headers['accept-encoding'] = $headers['accept-encoding'] ?? 'identity';
        $headers['connection'] = 'close'; unset($headers['content-length'], $headers['transfer-encoding']);
        $raw = $method . ' ' . $pathValue . " HTTP/1.1\r\n";
        if (!preg_match('/^[A-Z]{1,16}$/', $method) || preg_match('/[\r\n ]/', $pathValue))
            throw new InvalidArgumentException('invalid HTTP request line');
        foreach ($headers as $name => $value) {
            if (!preg_match("/^[!#$%&'*+.^_`|~0-9A-Za-z-]+$/", (string)$name) || preg_match('/[\r\n]/', (string)$value))
                throw new InvalidArgumentException('invalid HTTP header');
            $raw .= ucwords($name, '-') . ': ' . $value . "\r\n";
        }
        $body = (string)($request['body'] ?? '');
        $charset = (string)($request['charset'] ?? 'UTF-8');
        if ($charset !== 'UTF-8' && function_exists('iconv')) {
            $converted = @iconv('UTF-8', $charset, $body);
            if ($converted === false) throw new InvalidArgumentException('invalid HTTP request charset');
            $body = $converted;
        }
        if ($body !== '') $raw .= 'Content-Length: ' . strlen($body) . "\r\n";
        if (strlen($raw) > 8192) throw new InvalidArgumentException('HTTP request headers exceed limit');
        $writeBytes($stream, $raw . "\r\n" . $body);
        list($status, $headerBlock, $responseHeaders, $bodyBlock, $truncated) = $readHttpResponse($stream, $method, $maxRead);
        $responseCharset = $stage === 'http-request' ? $charset : 'UTF-8';
        if ($stage !== 'http-request' && preg_match('/charset=["\']?([^;\s"\']+)/i', $responseHeaders['content-type'][0] ?? '', $charsetMatch))
            $responseCharset = $charsetMatch[1];
        $textBody = $bodyBlock;
        if (strcasecmp($responseCharset, 'UTF-8') !== 0 && function_exists('iconv')) {
            $converted = @iconv($responseCharset, 'UTF-8//IGNORE', $bodyBlock);
            if ($converted !== false) $textBody = $converted;
        }
        $evidence = ['statusCode' => $status, 'bodyLength' => strlen($bodyBlock), 'truncated' => $truncated];
        if ($stage === 'http-request') {
            $evidence['headers'] = $sanitize($headerBlock, 32768);
            $evidence['headersTruncated'] = false;
            $evidence['responseHeaders'] = array_map(static function ($values) use ($sanitize) {
                return $sanitize(implode(', ', $values), 32768);
            }, $responseHeaders);
            $evidence['body'] = $sanitize($textBody, $maxRead * 4);
        }
        if (preg_match('/(?:^|\r?\n)Content-Length:\s*(\d+)/i', $headerBlock, $match)) {
            $evidence['responseSize'] = (int)$match[1];
        } else {
            $evidence['responseSize'] = strlen($bodyBlock);
        }
        if (preg_match('/(?:^|\r?\n)Server:\s*([^\r\n]+)/i', $headerBlock, $match)) $evidence['server'] = $sanitize($match[1], 512);
        if (preg_match('/(?:^|\r?\n)Content-Type:\s*([^\r\n]+)/i', $headerBlock, $match)) $evidence['contentType'] = $sanitize($match[1], 512);
        if (preg_match('/(?:^|\r?\n)Location:\s*([^\r\n]+)/i', $headerBlock, $match)) $evidence['location'] = $sanitize($match[1], 512);
        if (preg_match('/<title\b[^>]*>(.*?)<\/title\s*>/is', $textBody, $match)) {
            $title = trim(preg_replace('/\s+/', ' ', strip_tags(html_entity_decode($match[1], ENT_QUOTES, 'UTF-8'))));
            if ($title !== '') $evidence['title'] = $sanitize($title, 512);
        }
        return $observation($target, $stage, $status > 0 ? 'open' : 'error', $started, '', $evidence);
    } catch (Throwable $error) {
        $metadata = is_resource($stream) ? stream_get_meta_data($stream) : [];
        return $observation($target, $stage, 'error', $started, $error->getMessage(), [], !empty($metadata['timed_out']) ? 'TIMEOUT' : 'IO');
    } finally { if (is_resource($stream)) fclose($stream); }
};
// A logical shard retains only its cursor and bounded unpublished batch. A pool
// process runs finite turns across shards/tasks; paused shards consume no process.
$runTurn = static function ($job) use ($path, $read, $write, $statusUpdate, $stateName, $probe, $now, $bufferLimits, $refreshPressure, $appendRows) {
    list($id, $index) = $job; $directory = $path($id);
    $statusFile = $directory . DIRECTORY_SEPARATOR . $stateName('status');
    $checkpoint = $directory . DIRECTORY_SEPARATOR . $stateName('worker-' . $index);
    $config = $read($directory . DIRECTORY_SEPARATOR . $stateName('config'));
    if (!is_array($config)) return;
    $state = $read($checkpoint) ?? ['next' => $index, 'step' => 0, 'rows' => [], 'bytes' => 0,
        'completed' => 0, 'connectAttempted' => false, 'connected' => false];
    $publish = static function () use (&$state, $statusFile, $statusUpdate, $now, $bufferLimits, $refreshPressure, $appendRows) {
        if (!$state['rows'] && !$state['completed']) return true;
        $rows = $state['rows']; $completed = $state['completed'];
        $encoded = array_map('json_encode', $rows);
        if (in_array(false, $encoded, true)) throw new RuntimeException('result encoding failed');
        $bytes = array_sum(array_map('strlen', $encoded));
        $result = $statusUpdate($statusFile, static function (&$latest) use ($rows, $completed, $encoded, $bytes, $bufferLimits, $refreshPressure, $now, $statusFile, $appendRows) {
            if (($latest['status'] ?? '') === 'STOPPED') return 'STOP';
            if (($latest['status'] ?? '') === 'PAUSED') return 'PARK';
            if (count($rows) > $bufferLimits['maxItems'] || $bytes > $bufferLimits['maxBytes'] - 262144) {
                $latest['status'] = 'STOPPED'; $latest['outcome'] = 'FAILED'; $latest['finishedAt'] = $now();
                $latest['backpressured'] = false; $latest['error'] = 'single batch exceeds buffer capacity'; return 'STOP';
            }
            if (!empty($latest['backpressured']) || $latest['pendingItems'] + count($rows) > $bufferLimits['maxItems']
                || (int)$latest['pendingBytes'] + $bytes > $bufferLimits['maxBytes']) {
                $latest['backpressured'] = true; return 'PARK';
            }
            $appendRows($statusFile, $latest, $encoded);
            $latest['pendingBytes'] += $bytes;
            foreach ($rows as $row) if (($row['state'] ?? '') === 'error') {
                $latest['errorCount'] = (int)($latest['errorCount'] ?? 0) + 1;
                $error = array_intersect_key($row, array_flip(['target', 'stage', 'errorCode', 'error']));
                $size = strlen((string)json_encode($error));
                if (count($latest['errors']) < 128 && (int)($latest['errorBytes'] ?? 0) + $size <= 262144
                    && $latest['pendingBytes'] + $size <= $bufferLimits['maxBytes']) {
                    $latest['errors'][] = $error; $latest['errorBytes'] = (int)($latest['errorBytes'] ?? 0) + $size;
                    $latest['pendingBytes'] += $size;
                } else $latest['errorsTruncated'] = true;
            }
            $latest['completed'] += $completed; $latest['updatedAt'] = $now(); $refreshPressure($latest); return true;
        }, true);
        if ($result === false) throw new RuntimeException('result publication failed');
        if ($result !== true) return false;
        $state['rows'] = []; $state['bytes'] = 0; $state['completed'] = 0; return true;
    };
    $started = $now();
    try {
        // Publish a cached response before performing any new I/O after resumption.
        if (!$publish()) return;
        do {
            $status = $read($statusFile);
            if (!is_array($status) || ($status['status'] ?? '') !== 'RUNNING' || !empty($status['backpressured'])) return;
            if ($state['next'] >= count($config['targets'])) {
                if (!$publish()) return;
                $done = $statusUpdate($statusFile, static function (&$latest) use ($index, $now) {
                    if (($latest['status'] ?? '') === 'STOPPED') return true;
                    $latest['doneWorkers'][$index] = true;
                    $latest['workersDone'] = count($latest['doneWorkers']);
                    if ($latest['workersDone'] >= $latest['workers']) {
                        $latest['status'] = 'STOPPED'; $latest['outcome'] = 'COMPLETED';
                        $latest['backpressured'] = false; $latest['finishedAt'] = $now();
                    }
                    $latest['updatedAt'] = $now(); return true;
                });
                if ($done === false) throw new RuntimeException('worker completion failed');
                return;
            }
            $target = $config['targets'][$state['next']];
            $stages = !empty($target['stage']) ? [(string)$target['stage']] : $config['stages'];
            $stage = $stages[$state['step']]; $row = null;
            if ($stage !== 'tcp-exchange' || !$state['connectAttempted'] || $state['connected']) {
                $row = $probe($target, $stage, (int)($target['timeout'] ?? $config['timeout']),
                    (int)($target['maxReadBytes'] ?? $config['maxReadBytes']));
            }
            if ($stage === 'tcp-connect') { $state['connectAttempted'] = true; $state['connected'] = ($row['state'] ?? '') === 'open'; }
            if ($row !== null) {
                $state['rows'][] = $row; $state['bytes'] += strlen((string)json_encode($row));
            }
            if (++$state['step'] >= count($stages)) {
                $state['next'] += $config['workers']; $state['step'] = 0; $state['completed']++;
                $state['connectAttempted'] = false; $state['connected'] = false;
            }
            if ($state['completed'] >= 32 || $state['bytes'] >= 262144 || $now() - $started >= 250) {
                $publish(); return;
            }
        } while (true);
    } finally {
        if (is_dir($directory) && !$write($checkpoint, $state)) throw new RuntimeException('worker checkpoint failed');
    }
};
$worker = static function ($slot, $token) use ($slotLock, $poolUpdate, $runTurn, $enqueue, $pick, $failJob) {
    $lock = $slotLock($slot); if ($lock === false) return 2;
    $job = null;
    try {
        $job = $poolUpdate(static function (&$pool) use ($slot, $token) {
            if (($pool['slots'][$slot]['token'] ?? '') !== $token) return null;
            $pool['slots'][$slot]['claimed'] = true; return $pool['slots'][$slot]['job'];
        });
        if (!is_array($job)) return 3;
        while ($job !== null) {
            $runTurn($job);
            $next = $poolUpdate(static function (&$pool) use ($slot, $token, $job, $enqueue, $pick) {
                if (($pool['slots'][$slot]['token'] ?? '') !== $token) return null;
                $pool['slots'][$slot]['job'] = null; $enqueue($pool, $job);
                $next = $pick($pool); $pool['slots'][$slot]['job'] = $next; return $next;
            });
            if ($next === false) throw new RuntimeException('worker dispatch failed');
            $job = $next;
        }
    } catch (Throwable $error) { $failJob(is_array($job) ? $job : null, $error->getMessage()); }
    finally {
        $poolUpdate(static function (&$pool) use ($slot, $token) {
            if (($pool['slots'][$slot]['token'] ?? '') === $token) unset($pool['slots'][$slot]);
            return true;
        });
        @flock($lock, LOCK_UN); @fclose($lock);
    }
    return 0;
};
if (PHP_SAPI === 'cli' && isset($argv[1]) && hash_equals($workerToken, (string)$argv[1])) {
    exit($worker((int)($argv[2] ?? -1), (string)($argv[3] ?? '')));
}
$cleanup = static function () use ($base, $read, $stateName, $releaseState, $now) {
    if (!is_dir($base)) return 0; $count = 0;
    foreach ((array)glob($base . DIRECTORY_SEPARATOR . '*') as $directory) {
        if (!is_dir($directory)) continue; // Pool/slot lock files must retain their inode across requests.
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
$handle = static function ($action, $params) use ($get, $base, $path, $stateName, $read, $write, $cleanup, $now, $statusUpdate, $releaseState, $bounded, $acknowledge, $poolUpdate, $processLimit, $enqueue, $emptyResults, $resultPath, $readPage) {
        $cleanup();
        if ($action === 'startTask') {
            $plan = $get($params, 'plan');
            if (!is_array($plan)) return ['code' => 400, 'msg' => 'plan must be a map'];
            $targets = $get($plan, 'targets', []);
            if (!is_array($targets) || count($targets) > 4096) return ['code' => 400, 'msg' => 'require at most 4096 targets'];
            foreach ($targets as $target) {
                if (!is_array($target) || empty($target['host']) || (int)($target['port'] ?? 0) < 1 || (int)$target['port'] > 65535) {
                    return ['code' => 400, 'msg' => 'target requires a host and valid port'];
                }
            }
            $stages = array_values(array_map('strtolower', (array)$get($plan, 'stages', ['tcp-connect'])));
            if (!$stages) $stages = ['tcp-connect'];
            if (count($stages) > 8 || strlen((string)json_encode($plan)) > 4194304) return ['code' => 400, 'msg' => 'plan exceeds retention limit'];
            $limits = $get($plan, 'limits', []);
            $threads = max(1, min($processLimit, (int)$get($limits, 'threads', $processLimit)));
            $workerCount = min($threads, count($targets));
            $taskId = substr(hash('sha256', uniqid('', true) . '|' . mt_rand()), 0, 32);
            $directory = $path($taskId);
            $normalized = array_values($targets);
            $time = $now(); $config = ['targets' => $normalized, 'stages' => $stages,
                'timeout' => $bounded($get($limits, 'timeout', 3000), 100, 300000),
                'maxReadBytes' => $bounded($get($limits, 'maxReadBytes', 8192), 256, 8192), 'workers' => $workerCount];
            $status = ['taskId' => $taskId, 'scanKind' => 'network-probe', 'status' => 'RUNNING', 'outcome' => 'RUNNING',
                'total' => count($normalized), 'completed' => 0,
                'resultVersion' => 0, 'observationOffset' => 0, 'pendingItems' => 0,
                'pendingBytes' => 0, 'backpressured' => false,
                'createdAt' => $time, 'updatedAt' => $time, 'workers' => $workerCount, 'workersDone' => 0, 'doneWorkers' => []];
            if (!$normalized) {
                $status['status'] = 'STOPPED'; $status['outcome'] = 'COMPLETED'; $status['finishedAt'] = $time;
            }
            $created = $poolUpdate(static function (&$pool) use ($directory, $base, $write, $config, $status, $stateName, $releaseState, $enqueue, $taskId, $workerCount, $emptyResults, $resultPath) {
                if (count((array)glob($base . DIRECTORY_SEPARATOR . '*', GLOB_ONLYDIR)) >= 32)
                    return ['code' => 429, 'msg' => 'too many network probe tasks, max=32'];
                if (!@mkdir($directory, 0700, true)) return ['code' => 500, 'msg' => 'task directory unavailable'];
                if (!$write($directory . DIRECTORY_SEPARATOR . $stateName('config'), $config)
                    || !$write($resultPath($directory . DIRECTORY_SEPARATOR . $stateName('status'), 0), $emptyResults)
                    || !$write($directory . DIRECTORY_SEPARATOR . $stateName('status'), $status)) {
                    $releaseState($directory, false); return ['code' => 500, 'msg' => 'task state initialization failed'];
                }
                for ($i = 0; $i < $workerCount; $i++) $enqueue($pool, [$taskId, $i]);
                return true;
            });
            if ($created !== true) {
                if ($created === false) $releaseState($directory, false);
                return is_array($created) ? $created : ['code' => 500, 'msg' => 'task admission failed'];
            }
            return ['code' => 200, 'taskId' => $taskId];
        }
        $taskId = (string)$get($params, 'taskId', ''); $directory = $path($taskId); $statusFile = $directory . DIRECTORY_SEPARATOR . $stateName('status');
        $status = $read($statusFile); if (!is_array($status)) return ['code' => 404, 'msg' => 'task not found'];
        if ($action === 'queryTask') {
            $cursor = $get($params, 'cursor', 0); $ackCursor = $get($params, 'ackCursor', 0);
            if (!is_int($cursor) || !is_int($ackCursor) || $cursor < 0 || $ackCursor < 0 || $ackCursor > $cursor) {
                return ['code' => 400, 'msg' => 'require integer 0 <= ackCursor <= cursor'];
            }
            $maxItems = max(1, min(512, (int)$get($params, 'maxItems', 128)));
            $maxBytes = max(4096, min(1048576, (int)$get($params, 'maxBytes', 524288)));
            $includeEvidence = $get($params, 'includeEvidence', true) !== false;
            $result = $statusUpdate($statusFile, static function (&$status) use ($cursor, $ackCursor, $maxItems, $maxBytes, $includeEvidence, $taskId, $acknowledge, $statusFile, $readPage) {
                $end = $status['observationOffset'] + $status['pendingItems'];
                if ($ackCursor > $end) return ['code' => 400, 'msg' => 'ackCursor exceeds available observations'];
                $acknowledge($status, $ackCursor, $statusFile);
                list($page, $next, $more) = $readPage($statusFile, $status, $cursor, $maxItems, $maxBytes, $includeEvidence);
                $base = $status['observationOffset'];
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
                    'ackCursor' => $base,
                    'nextCursor' => $next,
                    'hasMore' => $more,
                    'incremental' => true,
                    'observations' => $page,
                    'errors' => array_slice(is_array($status['errors'] ?? null) ? $status['errors'] : [], 0, $maxItems),
                    'backpressured' => !empty($status['backpressured']),
                    'pendingItems' => $status['pendingItems'], 'pendingBytes' => (int)($status['pendingBytes'] ?? 0),
                    'errorCount' => (int)($status['errorCount'] ?? 0), 'errorsTruncated' => !empty($status['errorsTruncated']),
                    'workers' => (int)($status['workers'] ?? 0), 'error' => $status['error'] ?? null,
                    'createdAt' => $status['createdAt'] ?? null,
                    'finishedAt' => $status['finishedAt'] ?? null
                ];
                return ['code' => 200, 'result' => $snapshot];
            }, true);
            return $result === false ? ['code' => 500, 'msg' => 'task query failed'] : $result;
        }
        if ($action === 'ackTask') {
            $cursor = max(0, (int)$get($params, 'cursor', 0));
            $result = $statusUpdate($statusFile, static function (&$latest) use ($cursor, $acknowledge, $statusFile) {
                return ['code' => 200, 'cursor' => $acknowledge($latest, $cursor, $statusFile)];
            }, true);
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
                    $latest['backpressured'] = false;
                    $latest['finishedAt'] = $now(); $latest['updatedAt'] = $latest['finishedAt'];
                }
                return ['code' => 200, 'status' => 'STOPPED'];
            });
            return $result === false ? ['code' => 500, 'msg' => 'task stop failed'] : $result;
        }
        if ($action === 'releaseTask') return $releaseState($directory);
        return ['code' => 400, 'msg' => 'unsupported network probe action'];
};
return ['id' => 'NetworkProbeComponent', 'version' => '1.3.0',
    'handle' => static function ($action, $params) use ($handle, $pump, $read, $poolFile, $processLimit, $path, $releaseState) {
        $result = $handle($action, $params);
        if (!$pump()) {
            if ($action === 'startTask' && isset($result['taskId'])) $releaseState($path($result['taskId']), false);
            return ['code' => 500, 'msg' => 'process scheduler unavailable'];
        }
        if (isset($result['result'])) {
            $pool = $read($poolFile);
            $result['result']['processLimit'] = $processLimit;
            $result['result']['activeProcesses'] = count($pool['slots'] ?? []);
            $result['result']['queuedWorkers'] = count($pool['queue'] ?? []);
        }
        return $result;
    }];
