<?php
/* Stateful TCP forwarder for the platform-side SOCKS5/HTTP/local-forward engines. */
$pfGet = static function ($value, $key, $default = null) {
    return is_array($value) && array_key_exists($key, $value) ? $value[$key] : $default;
};
$pfAvailable = static function ($name) {
    return function_exists($name) && !in_array($name,
        array_map('trim', explode(',', (string)ini_get('disable_functions'))), true);
};
$pfScope = substr(hash('sha256', __FILE__ . '|state'), 0, 14);
$pfWorkerToken = substr(hash('sha256', __FILE__ . '|worker'), 0, 18);
$pfBase = rtrim((string)sys_get_temp_dir(), '/\\') . DIRECTORY_SEPARATOR . '.' . $pfScope;
$pfName = static function ($label) {
    return substr(hash('sha256', __FILE__ . '|file|' . $label), 0, 16) . '.dat';
};
$pfPath = static function ($connId) use ($pfBase) {
    if (!is_string($connId) || !preg_match('/^[A-Za-z0-9_-]{8,128}$/', $connId)) {
        throw new InvalidArgumentException('invalid connId');
    }
    return $pfBase . DIRECTORY_SEPARATOR . hash('sha256', $connId);
};
$pfReadJson = static function ($path) {
    $value = json_decode((string)@file_get_contents($path), true);
    return is_array($value) ? $value : null;
};
$pfWriteJson = static function ($path, $value) {
    $encoded = json_encode($value);
    if (!is_string($encoded)) return false;
    $temporary = $path . '.' . getmypid() . '.tmp';
    if (@file_put_contents($temporary, $encoded, LOCK_EX) === false) return false;
    @chmod($temporary, 0600);
    return @rename($temporary, $path);
};
$pfRemove = static function ($directory) {
    if (!is_dir($directory)) return;
    foreach ((array)glob($directory . DIRECTORY_SEPARATOR . '*') as $path) @unlink($path);
    @rmdir($directory);
};
$pfCleanup = static function () use ($pfBase, $pfName, $pfReadJson, $pfRemove) {
    if (!is_dir($pfBase)) return;
    foreach ((array)glob($pfBase . DIRECTORY_SEPARATOR . '*') as $directory) {
        if (!is_dir($directory)) continue;
        $status = $pfReadJson($directory . DIRECTORY_SEPARATOR . $pfName('status'));
        $updated = is_array($status) && isset($status['updatedAt']) ? (int)$status['updatedAt'] : (int)@filemtime($directory);
        $state = is_array($status) && isset($status['state']) ? (string)$status['state'] : '';
        $ttl = in_array($state, ['closed', 'failed'], true) ? 300 : 1800;
        if ($updated > 0 && time() - $updated > $ttl) $pfRemove($directory);
    }
};
$pfLaunch = static function ($directory) use ($pfAvailable, $pfWorkerToken) {
    $php = defined('PHP_BINARY') && PHP_BINARY !== '' ? PHP_BINARY : 'php';
    $runner = escapeshellarg($php) . ' ' . escapeshellarg(__FILE__)
        . ' ' . escapeshellarg($pfWorkerToken) . ' ' . escapeshellarg($directory);
    if (DIRECTORY_SEPARATOR === '\\') {
        if ($pfAvailable('popen')) {
            $handle = @popen('start /B "" ' . $runner . ' >NUL 2>&1', 'r');
            if (is_resource($handle)) pclose($handle);
            return true;
        }
        return false;
    }
    $command = '/bin/sh -c ' . escapeshellarg($runner . ' </dev/null >/dev/null 2>&1 & echo $!');
    if ($pfAvailable('shell_exec')) return (int)trim((string)@shell_exec($command)) > 0;
    if ($pfAvailable('exec')) {
        $lines = []; $code = 1; @exec($command, $lines, $code);
        return $code === 0 && (int)trim((string)end($lines)) > 0;
    }
    if ($pfAvailable('popen')) {
        $handle = @popen($command, 'r');
        if (!is_resource($handle)) return false;
        $pid = (int)trim((string)stream_get_contents($handle)); pclose($handle);
        return $pid > 0;
    }
    return false;
};


/* Atomic queue records keep the ACK and its bytes in the same commit. The stable lock survives rename. */
$pfQueue = static function ($path, $operation, $argument, $offset = null) {
    $lock = @fopen($path . '.lock', 'c+b');
    if ($lock === false || !@flock($lock, LOCK_EX)) {
        if ($lock !== false) fclose($lock);
        throw new RuntimeException('proxy queue lock failed');
    }
    try {
        $raw = (string)@file_get_contents($path);
        $end = 0; $base = 0; $retained = ''; $pending = '';
        if ($raw !== '') {
            if (substr($raw, 0, 6) !== "LEOQ2\n") throw new RuntimeException('proxy queue record invalid');
            $parts = explode("\n", $raw, 5);
            if (count($parts) !== 5 || !preg_match('/^[0-9]+$/D', $parts[1]) || !preg_match('/^[0-9]+$/D', $parts[2]) || !preg_match('/^[0-9]+$/D', $parts[3])
                || (int)$parts[3] > strlen($parts[4])) throw new RuntimeException('proxy queue record invalid');
            $end = $parts[1] + 0; $base = $parts[2] + 0;
            $retained = substr($parts[4], 0, (int)$parts[3]); $pending = (string)substr($parts[4], (int)$parts[3]);
        }
        $limit = 1048576;
        $room = max(0, $limit - strlen($pending) - strlen($retained));
        if ($operation === 'room') return $room;
        if ($operation === 'state') return ['end' => $end, 'base' => $base, 'retained' => strlen($retained)];
        if ($operation === 'append') {
            $skip = 0;
            if ($offset !== null) {
                if ($offset > $end) throw new RuntimeException('proxy write offset gap');
                $skip = (int)min(strlen($argument), $end - $offset);
            }
            $length = strlen($argument) - $skip;
            if ($offset === null && $length > $room) throw new RuntimeException('proxy queue limit exceeded');
            $length = min($length, $room);
            $pending .= substr($argument, $skip, $length); $end += $length;
            $result = $offset === null ? $length : $end;
        } elseif ($offset === null) {
            $result = substr($pending, 0, $argument);
            $pending = (string)substr($pending, strlen($result)); $base += strlen($result);
        } else {
            if ($offset > $base + strlen($retained)) throw new RuntimeException('proxy read ACK out of range');
            $drop = (int)max(0, $offset - $base);
            $retained = (string)substr($retained, $drop); $base += $drop;
            $chunk = substr($pending, 0, max(0, $argument - strlen($retained)));
            $retained .= $chunk; $pending = (string)substr($pending, strlen($chunk));
            $result = ['readOffset' => $base, 'data' => substr($retained, 0, $argument)];
        }
        $encoded = "LEOQ2\n" . sprintf('%.0f', $end) . "\n" . sprintf('%.0f', $base) . "\n"
            . strlen($retained) . "\n" . $retained . $pending;
        if ($encoded !== $raw) {
            $temporary = $path . '.' . getmypid() . '.tmp';
            if (@file_put_contents($temporary, $encoded) !== strlen($encoded) || !@rename($temporary, $path)) {
                @unlink($temporary); throw new RuntimeException('proxy queue commit failed');
            }
            @chmod($path, 0600);
        }
        return $result;
    } finally { flock($lock, LOCK_UN); fclose($lock); }
};
$pfDataAppend = static function ($path, $data) use ($pfQueue) { return $pfQueue($path, 'append', $data); };
$pfDataTake = static function ($path, $limit) use ($pfQueue) { return $pfQueue($path, 'take', $limit); };

$pfBatch = static function ($frames) use ($pfQueue, $pfGet, $pfName, $pfReadJson, $pfPath) {
    if (!is_array($frames) || count($frames) < 1 || count($frames) > 16) return ['code' => 400, 'msg' => 'BATCH requires 1..16 frames'];
    $ids = []; $bytes = 0;
    foreach ($frames as $frame) {
        if (!is_array($frame)) return ['code' => 400, 'msg' => 'invalid BATCH frame'];
        $id = $pfGet($frame, 'connId'); $data = $pfGet($frame, 'data');
        $limit = $pfGet($frame, 'maxBytes'); $writeOffset = $pfGet($frame, 'writeOffset'); $readAck = $pfGet($frame, 'readAck');
        foreach ([$writeOffset, $readAck] as $offset) {
            if ((!is_int($offset) && !is_float($offset)) || $offset < 0 || $offset > 9007199254740991
                || floor($offset) != $offset) return ['code' => 400, 'msg' => 'invalid offset'];
        }
        if (!is_string($id) || !preg_match('/^[A-Za-z0-9_-]{8,128}$/', $id) || isset($ids[$id])
            || !is_string($data) || strlen($data) > 262144 || $writeOffset > 9007199254740991 - strlen($data)
            || !is_int($limit) || $limit < 1 || $limit > 262144) return ['code' => 400, 'msg' => 'invalid BATCH frame'];
        $ids[$id] = true; $bytes += strlen($data) + $limit;
    }
    if ($bytes > 1048576) return ['code' => 400, 'msg' => 'BATCH exceeds byte limit'];
    $replies = [];
    foreach ($frames as $frame) {
        $id = $frame['connId']; $reply = ['connId' => $id];
        $lock = null;
        try {
            $directory = $pfPath($id);
            if (!is_dir($directory)) { $reply['code'] = 404; $replies[] = $reply; continue; }
            $lock = @fopen($directory . DIRECTORY_SEPARATOR . $pfName('batch-lock'), 'c+b');
            if ($lock === false || !@flock($lock, LOCK_EX)) throw new RuntimeException('proxy batch lock failed');
            $out = $directory . DIRECTORY_SEPARATOR . $pfName('output');
            $input = $directory . DIRECTORY_SEPARATOR . $pfName('input');
            $writeState = $pfQueue($out, 'state', 0); $readState = $pfQueue($input, 'state', 0);
            if ($frame['writeOffset'] > $writeState['end'] || $frame['readAck'] > $readState['base'] + $readState['retained']) {
                $reply['code'] = 409; $replies[] = $reply; continue;
            }
            @touch($directory . DIRECTORY_SEPARATOR . $pfName('heartbeat'));
            $status = $pfReadJson($directory . DIRECTORY_SEPARATOR . $pfName('status'));
            $open = is_array($status) && $status['state'] === 'open' && !is_file($directory . DIRECTORY_SEPARATOR . $pfName('stop'));
            // Closed peers can still replay their last write ACK and unread tail, but accept no new bytes.
            $writeAck = $pfQueue($out, 'append', $open ? $frame['data'] : '', $frame['writeOffset']);
            $read = $pfQueue($directory . DIRECTORY_SEPARATOR . $pfName('input'), 'take', $frame['maxBytes'], $frame['readAck']);
            $reply += ['code' => 200, 'writeAck' => $writeAck, 'readOffset' => $read['readOffset'],
                'readCode' => $read['data'] !== '' ? 200 : ($open ? 204 : 404), 'data' => leo_binary($read['data'])];
        } catch (Exception $error) { $reply['code'] = 500; $reply['msg'] = $error->getMessage(); }
        finally { if (is_resource($lock)) { flock($lock, LOCK_UN); fclose($lock); } }
        $replies[] = $reply;
    }
    return ['code' => 200, 'frames' => $replies];
};

$pfWorker = static function ($directory) use ($pfName, $pfReadJson, $pfWriteJson, $pfDataAppend, $pfDataTake, $pfQueue) {
    $config = $pfReadJson($directory . DIRECTORY_SEPARATOR . $pfName('config'));
    if (!is_array($config)) return 2;
    $statusPath = $directory . DIRECTORY_SEPARATOR . $pfName('status');
    $address = 'tcp://' . $config['targetHost'] . ':' . (int)$config['targetPort'];
    $errno = 0; $error = '';
    $timeout = max(1, min(30, ((int)$config['connectTimeout']) / 1000));
    $socket = @stream_socket_client($address, $errno, $error, $timeout, STREAM_CLIENT_CONNECT);
    if (!is_resource($socket)) {
        $pfWriteJson($statusPath, ['state' => 'failed', 'msg' => $error !== '' ? $error : (string)$errno,
            'updatedAt' => time()]);
        return 3;
    }
    stream_set_blocking($socket, false);
    $pfWriteJson($statusPath, ['state' => 'open', 'pid' => getmypid(), 'updatedAt' => time()]);
    $lastActivity = time(); $lastStatusWrite = time(); $stop = $directory . DIRECTORY_SEPARATOR . $pfName('stop');
    $input = $directory . DIRECTORY_SEPARATOR . $pfName('input');
    $output = $directory . DIRECTORY_SEPARATOR . $pfName('output');
    $heartbeat = $directory . DIRECTORY_SEPARATOR . $pfName('heartbeat');
    $outgoing = ''; $offset = 0; $writeDeadline = 0;
    try {
        while (!feof($socket)) {
            clearstatcache(true, $stop);
            if (is_file($stop)) break;
            clearstatcache(true, $heartbeat);
            $heartbeatAt = is_file($heartbeat) ? (int)@filemtime($heartbeat) : 0;
            if (time() - max($lastActivity, $heartbeatAt) >= 600) break;
            if ($outgoing === '') {
                $outgoing = $pfDataTake($output, 65536); $offset = 0;
                $writeDeadline = microtime(true) + 5.0;
            }
            if ($outgoing !== '') {
                $written = @fwrite($socket, substr($outgoing, $offset));
                if ($written === false) throw new RuntimeException('socket write failed');
                if ($written > 0) { $offset += $written; $writeDeadline = microtime(true) + 5.0; $lastActivity = time(); }
                elseif (microtime(true) >= $writeDeadline) throw new RuntimeException('socket write timeout');
                if ($offset === strlen($outgoing)) $outgoing = '';
            }
            $room = min(65536, $pfQueue($input, 'room', 0));
            $read = $room > 0 ? [$socket] : []; $write = $outgoing !== '' ? [$socket] : []; $except = [];
            $selected = 0;
            if ($read || $write) $selected = @stream_select($read, $write, $except, 0, 100000);
            else usleep(10000);
            if ($selected === false) throw new RuntimeException('socket select failed');
            if ($read) {
                $incoming = @fread($socket, $room);
                if ($incoming === false) throw new RuntimeException('socket read failed');
                if ($incoming !== '') { $pfDataAppend($input, $incoming); $lastActivity = time(); }
                elseif (feof($socket)) break;
            }
            if (time() - $lastStatusWrite >= 5) {
                $pfWriteJson($statusPath, ['state' => 'open', 'pid' => getmypid(), 'updatedAt' => time()]);
                $lastStatusWrite = time();
            }
        }
        $pfWriteJson($statusPath, ['state' => 'closed', 'pid' => getmypid(), 'updatedAt' => time()]);
    } catch (Exception $error) {
        $pfWriteJson($statusPath, ['state' => 'failed', 'msg' => $error->getMessage(), 'updatedAt' => time()]);
    } finally {
        if (is_resource($socket)) fclose($socket);
    }
    return 0;
};

if (PHP_SAPI === 'cli' && isset($argv[1]) && hash_equals($pfWorkerToken, (string)$argv[1])) {
    exit($pfWorker(isset($argv[2]) ? $argv[2] : ''));
}

return [
    'id' => 'ProxyForwardComponent', 'version' => '1.0.0',
    'handle' => static function ($action, $params) use (
        $pfGet, $pfBase, $pfPath, $pfName, $pfReadJson, $pfWriteJson,
        $pfRemove, $pfCleanup, $pfLaunch, $pfBatch
    ) {
        $pfCleanup();
        $op = $pfGet($params, 'op', -1);
        if (!is_int($op) || !in_array($op, [0, 3, 5], true)) return ['code' => 400, 'msg' => 'unknown op'];
        if ($op === 5) return $pfBatch($pfGet($params, 'frames'));
        $connId = (string)$pfGet($params, 'connId', '');
        $directory = $pfPath($connId);
        $statusPath = $directory . DIRECTORY_SEPARATOR . $pfName('status');
        if ($op === 0) {
            $host = trim((string)$pfGet($params, 'targetHost', ''));
            $port = (int)$pfGet($params, 'targetPort', 0);
            if ($host === '' || strpos($host, "\0") !== false || $port < 1 || $port > 65535) {
                return ['code' => 400, 'msg' => 'targetHost and targetPort required'];
            }
            if (!is_dir($pfBase) && !@mkdir($pfBase, 0700, true) && !is_dir($pfBase)) {
                return ['code' => 500, 'msg' => 'proxy state directory unavailable'];
            }
            $entries = array_filter((array)glob($pfBase . DIRECTORY_SEPARATOR . '*'), 'is_dir');
            if (!is_dir($directory) && count($entries) >= 128) return ['code' => 429, 'msg' => 'connection limit reached'];
            if (is_dir($directory)) {
                $existing = $pfReadJson($statusPath);
                if (is_array($existing) && in_array($existing['state'], ['starting', 'open'], true)) {
                    return ['code' => 409, 'msg' => 'connId exists'];
                }
                $pfRemove($directory);
            }
            if (!@mkdir($directory, 0700, true) && !is_dir($directory)) return ['code' => 500, 'msg' => 'connection state unavailable'];
            @file_put_contents($directory . DIRECTORY_SEPARATOR . $pfName('input'), '', LOCK_EX);
            @file_put_contents($directory . DIRECTORY_SEPARATOR . $pfName('output'), '', LOCK_EX);
            @touch($directory . DIRECTORY_SEPARATOR . $pfName('heartbeat'));
            $timeout = max(100, min(30000, (int)$pfGet($params, 'connectTimeout', 5000)));
            $pfWriteJson($directory . DIRECTORY_SEPARATOR . $pfName('config'), [
                'targetHost' => $host, 'targetPort' => $port, 'connectTimeout' => $timeout
            ]);
            $pfWriteJson($statusPath, ['state' => 'starting', 'updatedAt' => time()]);
            if (!$pfLaunch($directory)) { $pfRemove($directory); return ['code' => 503, 'msg' => 'background worker unavailable']; }
            $deadline = microtime(true) + ($timeout / 1000) + 1.0;
            do {
                usleep(20000); $status = $pfReadJson($statusPath);
                if (is_array($status) && $status['state'] === 'open') return ['code' => 200, 'msg' => 'opened'];
                if (is_array($status) && $status['state'] === 'failed') {
                    return ['code' => 404, 'msg' => isset($status['msg']) ? $status['msg'] : 'connect failed'];
                }
            } while (microtime(true) < $deadline);
            @file_put_contents($directory . DIRECTORY_SEPARATOR . $pfName('stop'), '1', LOCK_EX);
            return ['code' => 504, 'msg' => 'connect timeout'];
        }
        if ($op === 3) {
            if (is_dir($directory)) @file_put_contents($directory . DIRECTORY_SEPARATOR . $pfName('stop'), '1', LOCK_EX);
            return ['code' => 200, 'msg' => 'closed'];
        }
        return ['code' => 400, 'msg' => 'unknown op'];
    }
];
