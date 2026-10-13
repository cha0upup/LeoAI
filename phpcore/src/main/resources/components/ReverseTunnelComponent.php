<?php
/* Remote listener used by the shared reverse-tunnel engine. */
$rtGet = static function ($value, $key, $default = null) {
    return is_array($value) && array_key_exists($key, $value) ? $value[$key] : $default;
};
$rtAvailable = static function ($name) {
    return function_exists($name) && !in_array($name,
        array_map('trim', explode(',', (string)ini_get('disable_functions'))), true);
};
$rtScope = substr(hash('sha256', __FILE__ . '|state'), 0, 14);
$rtWorkerToken = substr(hash('sha256', __FILE__ . '|worker'), 0, 18);
$rtBase = rtrim((string)sys_get_temp_dir(), '/\\') . DIRECTORY_SEPARATOR . '.' . $rtScope;
$rtName = static function ($label) {
    return substr(hash('sha256', __FILE__ . '|file|' . $label), 0, 16) . '.dat';
};
$rtListenPath = static function ($listenId) use ($rtBase) {
    if (!is_string($listenId) || !preg_match('/^[A-Za-z0-9_-]{8,128}$/', $listenId)) {
        throw new InvalidArgumentException('invalid listenId');
    }
    return $rtBase . DIRECTORY_SEPARATOR . hash('sha256', $listenId);
};
$rtCancelPath = static function ($listenId) use ($rtListenPath) {
    return $rtListenPath($listenId) . '.cancel';
};
$rtConnPath = static function ($directory, $connId) use ($rtName) {
    if (!is_string($connId) || !preg_match('/^[A-Za-z0-9_-]{8,128}$/', $connId)) {
        throw new InvalidArgumentException('invalid connId');
    }
    return $directory . DIRECTORY_SEPARATOR . $rtName('connections') . DIRECTORY_SEPARATOR . hash('sha256', $connId);
};
$rtReadJson = static function ($path) {
    $value = json_decode((string)@file_get_contents($path), true);
    return is_array($value) ? $value : null;
};
$rtWriteJson = static function ($path, $value) {
    $encoded = json_encode($value);
    if (!is_string($encoded)) return false;
    $temporary = $path . '.' . getmypid() . '.tmp';
    if (@file_put_contents($temporary, $encoded, LOCK_EX) === false) return false;
    @chmod($temporary, 0600);
    return @rename($temporary, $path);
};
$rtAppend = static function ($path, $data) {
    if ($data === '') return 0;
    $limit = 8388608; $length = strlen($data);
    $stream = @fopen($path, 'c+b');
    if ($stream === false || !@flock($stream, LOCK_EX)) {
        if ($stream !== false) fclose($stream); throw new RuntimeException('reverse queue write failed');
    }
    fseek($stream, 0, SEEK_END); $current = (int)ftell($stream);
    if ($length > $limit || $current > $limit - $length) {
        flock($stream, LOCK_UN); fclose($stream); throw new RuntimeException('reverse queue limit exceeded');
    }
    $written = @fwrite($stream, $data); fflush($stream); flock($stream, LOCK_UN); fclose($stream);
    if ($written === false || $written !== $length) throw new RuntimeException('reverse queue write failed');
    return $written;
};
$rtTakeLines = static function ($path, $limit) {
    $raw = '';
    $stream = @fopen($path, 'c+b');
    if ($stream === false) return [];
    if (@flock($stream, LOCK_EX)) {
        $raw = (string)stream_get_contents($stream); ftruncate($stream, 0); rewind($stream);
        fflush($stream); flock($stream, LOCK_UN);
    }
    fclose($stream); $items = [];
    foreach (preg_split('/\r?\n/', $raw) as $line) {
        if ($line === '' || count($items) >= $limit) continue;
        $item = json_decode($line, true); if (is_array($item)) $items[] = $item;
    }
    return $items;
};
$rtRemoveTree = static function ($directory) use (&$rtRemoveTree) {
    if (!is_dir($directory)) { @unlink($directory); return; }
    foreach ((array)glob($directory . DIRECTORY_SEPARATOR . '*') as $path) {
        if (is_dir($path)) $rtRemoveTree($path); else @unlink($path);
    }
    @rmdir($directory);
};
$rtCleanup = static function () use ($rtBase, $rtName, $rtReadJson, $rtRemoveTree) {
    if (!is_dir($rtBase)) return;
    foreach ((array)glob($rtBase . DIRECTORY_SEPARATOR . '*.cancel') as $marker) {
        if (time() - (int)@filemtime($marker) > 600) @unlink($marker);
    }
    foreach ((array)glob($rtBase . DIRECTORY_SEPARATOR . '*') as $directory) {
        if (!is_dir($directory)) continue;
        $status = $rtReadJson($directory . DIRECTORY_SEPARATOR . $rtName('status'));
        $updated = is_array($status) && isset($status['updatedAt']) ? (int)$status['updatedAt'] : (int)@filemtime($directory);
        $heartbeat = (int)@filemtime($directory . DIRECTORY_SEPARATOR . $rtName('heartbeat'));
        $updated = max($updated, $heartbeat);
        $state = is_array($status) && isset($status['state']) ? (string)$status['state'] : '';
        $ttl = in_array($state, ['closed', 'failed'], true) ? 300 : 1800;
        if ($updated > 0 && time() - $updated > $ttl) { $rtRemoveTree($directory); continue; }
        $connections = $directory . DIRECTORY_SEPARATOR . $rtName('connections');
        foreach ((array)glob($connections . DIRECTORY_SEPARATOR . '*') as $connection) {
            $connectionStatus = $rtReadJson($connection . DIRECTORY_SEPARATOR . $rtName('status'));
            $connectionState = is_array($connectionStatus) && isset($connectionStatus['state'])
                ? (string)$connectionStatus['state'] : '';
            $connectionUpdated = is_array($connectionStatus) && isset($connectionStatus['updatedAt'])
                ? (int)$connectionStatus['updatedAt'] : (int)@filemtime($connection);
            if ($connectionState !== 'open' && $connectionUpdated > 0 && time() - $connectionUpdated > 300) {
                $rtRemoveTree($connection);
            }
        }
    }
};
$rtLaunch = static function ($directory) use ($rtAvailable, $rtWorkerToken) {
    $php = defined('PHP_BINARY') && PHP_BINARY !== '' ? PHP_BINARY : 'php';
    $runner = escapeshellarg($php) . ' ' . escapeshellarg(__FILE__)
        . ' ' . escapeshellarg($rtWorkerToken) . ' ' . escapeshellarg($directory);
    if (DIRECTORY_SEPARATOR === '\\') {
        if ($rtAvailable('popen')) {
            $handle = @popen('start /B "" ' . $runner . ' >NUL 2>&1', 'r');
            if (is_resource($handle)) pclose($handle);
            return true;
        }
        return false;
    }
    $command = '/bin/sh -c ' . escapeshellarg($runner . ' </dev/null >/dev/null 2>&1 & echo $!');
    if ($rtAvailable('shell_exec')) return (int)trim((string)@shell_exec($command)) > 0;
    if ($rtAvailable('exec')) {
        $lines = []; $code = 1; @exec($command, $lines, $code);
        return $code === 0 && (int)trim((string)end($lines)) > 0;
    }
    if ($rtAvailable('popen')) {
        $handle = @popen($command, 'r');
        if (!is_resource($handle)) return false;
        $pid = (int)trim((string)stream_get_contents($handle)); pclose($handle);
        return $pid > 0;
    }
    return false;
};

/* Atomic queue records keep the ACK and its bytes in the same commit. The stable lock survives rename. */
$rtQueue = static function ($path, $operation, $argument, $offset = null) {
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
$rtDataAppend = static function ($path, $data) use ($rtQueue) { return $rtQueue($path, 'append', $data); };
$rtDataTake = static function ($path, $limit) use ($rtQueue) { return $rtQueue($path, 'take', $limit); };

$rtBatch = static function ($frames) use ($rtQueue, $rtGet, $rtName, $rtReadJson, $rtBase) {
    if (!is_array($frames) || count($frames) < 1 || count($frames) > 16) return ['code' => 400, 'msg' => 'BATCH requires 1..16 frames'];
    $ids = []; $bytes = 0;
    foreach ($frames as $frame) {
        if (!is_array($frame)) return ['code' => 400, 'msg' => 'invalid BATCH frame'];
        $id = $rtGet($frame, 'connId'); $data = $rtGet($frame, 'data');
        $limit = $rtGet($frame, 'maxBytes'); $writeOffset = $rtGet($frame, 'writeOffset'); $readAck = $rtGet($frame, 'readAck');
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
            $matches = (array)glob($rtBase . DIRECTORY_SEPARATOR . '*' . DIRECTORY_SEPARATOR . $rtName('connections')
                . DIRECTORY_SEPARATOR . hash('sha256', $id));
            $directory = count($matches) > 0 ? $matches[0] : '';
            if (!is_dir($directory)) { $reply['code'] = 404; $replies[] = $reply; continue; }
            $lock = @fopen($directory . DIRECTORY_SEPARATOR . $rtName('batch-lock'), 'c+b');
            if ($lock === false || !@flock($lock, LOCK_EX)) throw new RuntimeException('proxy batch lock failed');
            $out = $directory . DIRECTORY_SEPARATOR . $rtName('output');
            $input = $directory . DIRECTORY_SEPARATOR . $rtName('input');
            $writeState = $rtQueue($out, 'state', 0); $readState = $rtQueue($input, 'state', 0);
            if ($frame['writeOffset'] > $writeState['end'] || $frame['readAck'] > $readState['base'] + $readState['retained']) {
                $reply['code'] = 409; $replies[] = $reply; continue;
            }
            @touch($directory . DIRECTORY_SEPARATOR . $rtName('heartbeat'));
            $status = $rtReadJson($directory . DIRECTORY_SEPARATOR . $rtName('status'));
            $open = is_array($status) && $status['state'] === 'open' && !is_file($directory . DIRECTORY_SEPARATOR . $rtName('stop'));
            // Closed peers can still replay their last write ACK and unread tail, but accept no new bytes.
            $writeAck = $rtQueue($out, 'append', $open ? $frame['data'] : '', $frame['writeOffset']);
            $read = $rtQueue($directory . DIRECTORY_SEPARATOR . $rtName('input'), 'take', $frame['maxBytes'], $frame['readAck']);
            $reply += ['code' => 200, 'writeAck' => $writeAck, 'readOffset' => $read['readOffset'],
                'readCode' => $read['data'] !== '' ? 200 : ($open ? 204 : 404), 'data' => leo_binary($read['data'])];
        } catch (Exception $error) { $reply['code'] = 500; $reply['msg'] = $error->getMessage(); }
        finally { if (is_resource($lock)) { flock($lock, LOCK_UN); fclose($lock); } }
        $replies[] = $reply;
    }
    return ['code' => 200, 'frames' => $replies];
};

$rtCloseClient = static function ($connId, &$clients, $paths, $rtWriteJson, $rtName) {
    if (isset($clients[$connId]) && is_resource($clients[$connId])) @fclose($clients[$connId]);
    unset($clients[$connId]);
    if (isset($paths[$connId])) {
        $rtWriteJson($paths[$connId] . DIRECTORY_SEPARATOR . $rtName('status'),
            ['state' => 'closed', 'updatedAt' => time()]);
        unset($paths[$connId]);
    }
};
$rtWorker = static function ($directory) use (
    $rtName, $rtReadJson, $rtWriteJson, $rtAppend, $rtDataAppend, $rtDataTake, $rtQueue, $rtCloseClient, $rtCancelPath
) {
    $config = $rtReadJson($directory . DIRECTORY_SEPARATOR . $rtName('config'));
    if (!is_array($config)) return 2;
    $cancel = $rtCancelPath((string)$config['listenId']);
    if (is_file($cancel)) {
        $rtWriteJson($directory . DIRECTORY_SEPARATOR . $rtName('status'), ['state' => 'closed', 'updatedAt' => time()]);
        return 0;
    }
    $bindAddr = (string)$config['bindAddr']; $port = (int)$config['listenPort'];
    $uriHost = strpos($bindAddr, ':') !== false && $bindAddr[0] !== '[' ? '[' . $bindAddr . ']' : $bindAddr;
    $errno = 0; $error = '';
    $server = @stream_socket_server('tcp://' . $uriHost . ':' . $port, $errno, $error,
        STREAM_SERVER_BIND | STREAM_SERVER_LISTEN);
    $statusPath = $directory . DIRECTORY_SEPARATOR . $rtName('status');
    if (!is_resource($server)) {
        $rtWriteJson($statusPath, ['state' => 'failed', 'msg' => $error !== '' ? $error : (string)$errno,
            'updatedAt' => time()]);
        return 3;
    }
    stream_set_blocking($server, false);
    $name = (string)stream_socket_get_name($server, false); $colon = strrpos($name, ':');
    $actualPort = $colon === false ? $port : (int)substr($name, $colon + 1);
    $rtWriteJson($statusPath, ['state' => 'open', 'pid' => getmypid(), 'listenPort' => $actualPort,
        'bindAddr' => $bindAddr, 'updatedAt' => time()]);
    $clients = []; $paths = []; $outgoing = []; $offsets = []; $deadlines = []; $lastActivity = time();
    $stop = $directory . DIRECTORY_SEPARATOR . $rtName('stop');
    $heartbeat = $directory . DIRECTORY_SEPARATOR . $rtName('heartbeat');
    $failureMessage = null;
    try {
        while (!is_file($stop) && !is_file($cancel)) {
            clearstatcache(true, $heartbeat);
            $heartbeatAt = is_file($heartbeat) ? (int)@filemtime($heartbeat) : 0;
            if (time() - max($lastActivity, $heartbeatAt) >= 600) break;
            $read = [$server]; $write = [];
            foreach ($clients as $connId => $socket) {
                $connDirectory = $paths[$connId];
                if (is_file($connDirectory . DIRECTORY_SEPARATOR . $rtName('stop'))) {
                    $rtCloseClient($connId, $clients, $paths, $rtWriteJson, $rtName); continue;
                }
                if (!isset($outgoing[$connId]) || $outgoing[$connId] === '') {
                    $outgoing[$connId] = $rtDataTake($connDirectory . DIRECTORY_SEPARATOR . $rtName('output'), 65536);
                    $offsets[$connId] = 0; $deadlines[$connId] = microtime(true) + 5.0;
                }
                if ($outgoing[$connId] !== '') $write[] = $socket;
                if ($rtQueue($connDirectory . DIRECTORY_SEPARATOR . $rtName('input'), 'room', 0) > 0) $read[] = $socket;
            }
            foreach (array_keys($outgoing) as $connId) {
                if (!isset($clients[$connId])) unset($outgoing[$connId], $offsets[$connId], $deadlines[$connId]);
            }
            $except = []; $selected = @stream_select($read, $write, $except, 0, 100000);
            if ($selected === false) throw new RuntimeException('listener select failed');
            foreach ($read as $ready) {
                if ($ready === $server) {
                    do {
                        $peer = ''; $client = @stream_socket_accept($server, 0, $peer);
                        if (!is_resource($client)) break;
                        if (count($clients) >= 256) { fclose($client); continue; }
                        stream_set_blocking($client, false);
                        $connId = md5(uniqid((string)mt_rand(), true) . $peer);
                        $connDirectory = $directory . DIRECTORY_SEPARATOR . $rtName('connections') . DIRECTORY_SEPARATOR . hash('sha256', $connId);
                        @mkdir($connDirectory, 0700, true);
                        @file_put_contents($connDirectory . DIRECTORY_SEPARATOR . $rtName('input'), '', LOCK_EX);
                        @file_put_contents($connDirectory . DIRECTORY_SEPARATOR . $rtName('output'), '', LOCK_EX);
                        $rtWriteJson($connDirectory . DIRECTORY_SEPARATOR . $rtName('status'), ['state' => 'open', 'updatedAt' => time()]);
                        $clients[$connId] = $client; $paths[$connId] = $connDirectory;
                        $clientAddr = $peer; $clientPort = 0; $lastColon = strrpos($peer, ':');
                        if ($lastColon !== false) { $clientAddr = trim(substr($peer, 0, $lastColon), '[]'); $clientPort = (int)substr($peer, $lastColon + 1); }
                        $rtAppend($directory . DIRECTORY_SEPARATOR . $rtName('accept'), json_encode([
                            'connId' => $connId, 'clientAddr' => $clientAddr, 'clientPort' => $clientPort
                        ]) . "\n");
                        $lastActivity = time();
                    } while (true);
                    continue;
                }
                foreach ($clients as $connId => $socket) {
                    if ($socket !== $ready) continue;
                    $room = min(65536, $rtQueue($paths[$connId] . DIRECTORY_SEPARATOR . $rtName('input'), 'room', 0));
                    if ($room === 0) break;
                    $data = @fread($socket, $room);
                    if ($data === false || ($data === '' && feof($socket))) {
                        $rtCloseClient($connId, $clients, $paths, $rtWriteJson, $rtName);
                    } elseif ($data !== '') {
                        $rtDataAppend($paths[$connId] . DIRECTORY_SEPARATOR . $rtName('input'), $data); $lastActivity = time();
                    }
                    break;
                }
            }
            foreach (array_keys($clients) as $connId) {
                $connDirectory = $paths[$connId];
                if (is_file($connDirectory . DIRECTORY_SEPARATOR . $rtName('stop'))) {
                    $rtCloseClient($connId, $clients, $paths, $rtWriteJson, $rtName); continue;
                }
                if (!isset($outgoing[$connId]) || $outgoing[$connId] === '') continue;
                $written = @fwrite($clients[$connId], substr($outgoing[$connId], $offsets[$connId]));
                if ($written === false || ($written === 0 && microtime(true) >= $deadlines[$connId])) {
                    $rtCloseClient($connId, $clients, $paths, $rtWriteJson, $rtName); continue;
                }
                if ($written > 0) { $offsets[$connId] += $written; $deadlines[$connId] = microtime(true) + 5.0; $lastActivity = time(); }
                if ($offsets[$connId] === strlen($outgoing[$connId])) $outgoing[$connId] = '';
            }
        }
    } catch (Exception $failure) {
        $failureMessage = $failure->getMessage();
    } finally {
        foreach (array_keys($clients) as $connId) {
            $rtCloseClient($connId, $clients, $paths, $rtWriteJson, $rtName);
        }
        if (is_resource($server)) fclose($server);
    }
    $finalStatus = ['state' => $failureMessage === null ? 'closed' : 'failed',
        'listenPort' => $actualPort, 'bindAddr' => $bindAddr, 'updatedAt' => time()];
    if ($failureMessage !== null) $finalStatus['msg'] = $failureMessage;
    $rtWriteJson($statusPath, $finalStatus);
    return 0;
};

if (PHP_SAPI === 'cli' && isset($argv[1]) && hash_equals($rtWorkerToken, (string)$argv[1])) {
    exit($rtWorker(isset($argv[2]) ? $argv[2] : ''));
}

return [
    'id' => 'ReverseTunnelComponent', 'version' => '1.0.0',
    'handle' => static function ($action, $params) use (
        $rtGet, $rtBase, $rtListenPath, $rtConnPath, $rtName, $rtReadJson, $rtWriteJson,
        $rtBatch, $rtTakeLines, $rtRemoveTree, $rtCleanup, $rtLaunch, $rtCancelPath
    ) {
        $rtCleanup();
        $op = $rtGet($params, 'op', -1);
        if (!is_int($op) || !in_array($op, [0, 1, 2, 5, 6, 8], true)) return ['code' => 400, 'msg' => 'unknown op'];
        if ($op === 8) return $rtBatch($rtGet($params, 'frames'));
        if ($op === 0) {
            $listenId = (string)$rtGet($params, 'listenId', ''); $directory = $rtListenPath($listenId);
            if (is_file($rtCancelPath($listenId))) return ['code' => 409, 'msg' => 'listener start cancelled'];
            $port = (int)$rtGet($params, 'listenPort', 0); $bindAddr = trim((string)$rtGet($params, 'bindAddr', '127.0.0.1'));
            if ($port < 0 || $port > 65535 || $bindAddr === '' || strpos($bindAddr, "\0") !== false) {
                return ['code' => 400, 'msg' => 'invalid listen address'];
            }
            if (!is_dir($rtBase) && !@mkdir($rtBase, 0700, true) && !is_dir($rtBase)) return ['code' => 500, 'msg' => 'listener state unavailable'];
            $entries = array_filter((array)glob($rtBase . DIRECTORY_SEPARATOR . '*'), 'is_dir');
            if (!is_dir($directory) && count($entries) >= 32) return ['code' => 429, 'msg' => 'listener limit reached'];
            if (is_dir($directory)) {
                $current = $rtReadJson($directory . DIRECTORY_SEPARATOR . $rtName('status'));
                if (is_array($current) && in_array($current['state'], ['starting', 'open'], true)) {
                    return ['code' => 409, 'msg' => 'listenId exists'];
                }
                $rtRemoveTree($directory);
            }
            @mkdir($directory . DIRECTORY_SEPARATOR . $rtName('connections'), 0700, true);
            @file_put_contents($directory . DIRECTORY_SEPARATOR . $rtName('accept'), '', LOCK_EX);
            @touch($directory . DIRECTORY_SEPARATOR . $rtName('heartbeat'));
            $rtWriteJson($directory . DIRECTORY_SEPARATOR . $rtName('config'), ['listenId' => $listenId, 'listenPort' => $port, 'bindAddr' => $bindAddr]);
            $rtWriteJson($directory . DIRECTORY_SEPARATOR . $rtName('status'), ['state' => 'starting', 'updatedAt' => time()]);
            if (is_file($rtCancelPath($listenId))) return ['code' => 409, 'msg' => 'listener start cancelled'];
            if (!$rtLaunch($directory)) { $rtRemoveTree($directory); return ['code' => 503, 'msg' => 'background worker unavailable']; }
            $deadline = microtime(true) + 5.0;
            do {
                if (is_file($rtCancelPath($listenId))) return ['code' => 409, 'msg' => 'listener start cancelled'];
                usleep(20000); $status = $rtReadJson($directory . DIRECTORY_SEPARATOR . $rtName('status'));
                if (is_array($status) && $status['state'] === 'open') return ['code' => 200, 'msg' => 'listening',
                    'listenPort' => (int)$status['listenPort'], 'bindAddr' => (string)$status['bindAddr']];
                if (is_array($status) && $status['state'] === 'failed') return ['code' => 500, 'msg' => $status['msg']];
            } while (microtime(true) < $deadline);
            @file_put_contents($directory . DIRECTORY_SEPARATOR . $rtName('stop'), '1', LOCK_EX);
            return ['code' => 504, 'msg' => 'listener startup timeout'];
        }
        if ($op === 1 || $op === 2) {
            $listenId = (string)$rtGet($params, 'listenId', ''); $directory = $rtListenPath($listenId);
            if ($op === 1) {
                @mkdir($rtBase, 0700, true);
                $markers = (array)glob($rtBase . DIRECTORY_SEPARATOR . '*.cancel');
                if (count($markers) >= 512) {
                    usort($markers, static function ($a, $b) { return (int)@filemtime($a) - (int)@filemtime($b); });
                    @unlink($markers[0]);
                }
                if (@file_put_contents($rtCancelPath($listenId), '1', LOCK_EX) === false) return ['code' => 500, 'msg' => 'cancel marker write failed'];
                @chmod($rtCancelPath($listenId), 0600);
            }
            $status = $rtReadJson($directory . DIRECTORY_SEPARATOR . $rtName('status'));
            if (!is_array($status) || !in_array($status['state'], $op === 1 ? ['starting', 'open'] : ['open'], true)) return ['code' => 404, 'msg' => 'listenId not found'];
            if ($op === 1) { @file_put_contents($directory . DIRECTORY_SEPARATOR . $rtName('stop'), '1', LOCK_EX); return ['code' => 200, 'msg' => 'stop requested', 'cleanupPending' => true]; }
            @touch($directory . DIRECTORY_SEPARATOR . $rtName('heartbeat'));
            return ['code' => 200, 'newConns' => $rtTakeLines($directory . DIRECTORY_SEPARATOR . $rtName('accept'), 256)];
        }
        if ($op === 5) {
            $connId = (string)$rtGet($params, 'connId', '');
            $matches = (array)glob($rtBase . DIRECTORY_SEPARATOR . '*' . DIRECTORY_SEPARATOR . $rtName('connections')
                . DIRECTORY_SEPARATOR . hash('sha256', $connId));
            if (count($matches) === 0) return ['code' => 404, 'msg' => 'connId not found'];
            @file_put_contents($matches[0] . DIRECTORY_SEPARATOR . $rtName('stop'), '1', LOCK_EX);
            return ['code' => 200, 'msg' => 'closed'];
        }
        if ($op === 6) {
            $listens = [];
            foreach ((array)glob($rtBase . DIRECTORY_SEPARATOR . '*') as $directory) {
                $status = $rtReadJson($directory . DIRECTORY_SEPARATOR . $rtName('status'));
                $config = $rtReadJson($directory . DIRECTORY_SEPARATOR . $rtName('config'));
                if (is_array($status) && is_array($config) && in_array($status['state'], ['starting', 'open'], true)) $listens[] = [
                    'listenId' => $config['listenId'], 'state' => $status['state'],
                    'listenPort' => isset($status['listenPort']) ? $status['listenPort'] : $config['listenPort'],
                    'bindAddr' => isset($status['bindAddr']) ? $status['bindAddr'] : $config['bindAddr']
                ];
            }
            return ['code' => 200, 'listens' => $listens];
        }
        return ['code' => 400, 'msg' => 'unknown op'];
    }
];
