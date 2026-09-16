<?php
$get = static function ($value, $key, $default = null) {
    return is_array($value) && array_key_exists($key, $value) ? $value[$key] : $default;
};
$isWindows = static function () {
    return defined('PHP_OS_FAMILY')
        ? constant('PHP_OS_FAMILY') === 'Windows' : strpos(strtoupper(PHP_OS), 'WIN') === 0;
};
$fileEntry = static function ($path) {
    $stat = @stat($path); $name = basename($path); if ($name === '') $name = $path;
    return ['name' => $name, 'path' => $path, 'isDirectory' => is_dir($path), 'isFile' => is_file($path),
        'size' => is_file($path) ? (int)filesize($path) : 0, 'modified' => $stat ? (int)$stat['mtime'] * 1000 : 0,
        'permissions' => substr(sprintf('%o', @fileperms($path)), -4), 'canRead' => is_readable($path),
        'canWrite' => is_writable($path), 'canExecute' => is_executable($path), 'exists' => file_exists($path),
        'extension' => is_file($path) ? pathinfo($path, PATHINFO_EXTENSION) : ''];
};
$deletePath = null;
$deletePath = static function ($path) use (&$deletePath) {
    if (is_link($path) || is_file($path)) return @unlink($path);
    if (!is_dir($path)) return false;
    $items = scandir($path); if ($items === false) return false;
    foreach ($items as $item) {
        if ($item === '.' || $item === '..') continue;
        if (!$deletePath($path . DIRECTORY_SEPARATOR . $item)) return false;
    }
    return @rmdir($path);
};
return [
    'id' => 'FileComponent',
    'version' => '1.0.0',
    'handle' => static function ($action, $params) use ($fileEntry, $deletePath, $get, $isWindows) {
        $path = (string)$get($params, 'path', '');
        if ($action === 'profile') {
            $windows = $isWindows();
            $roots = [DIRECTORY_SEPARATOR];
            if ($windows) {
                $roots = [];
                foreach (range('A', 'Z') as $drive) {
                    if (is_dir($drive . ':\\')) $roots[] = $drive . ':\\';
                }
            }
            return [
                'code' => 200,
                'osFamily' => $windows ? 'WINDOWS' : 'POSIX',
                'pathStyle' => $windows ? 'WINDOWS' : 'POSIX',
                'separator' => DIRECTORY_SEPARATOR,
                'caseSensitivity' => $windows ? 'INSENSITIVE' : 'SENSITIVE',
                'roots' => $roots,
                'capabilities' => [
                    'posixMode' => !$windows,
                    'windowsAttributes' => $windows,
                    'transactionalUpload' => true,
                    'rangeRead' => true,
                    'checksum' => true,
                    'grep' => false, 'touch' => false, 'pack' => false, 'rename' => false, 'chmod' => false,
                    'copyDirectory' => false,
                    'compressionFormats' => class_exists('ZipArchive') ? ['zip'] : [],
                    'extractionFormats' => class_exists('ZipArchive') ? ['zip'] : [],
                    'maxUploadChunkBytes' => 1048576
                ]
            ];
        }
        if ($path === '') throw new InvalidArgumentException('path is required');
        if ($action === 'list') {
            $items = scandir($path); if ($items === false) throw new RuntimeException('directory cannot be read');
            $result = [];
            foreach ($items as $item) {
                if ($item === '.' || $item === '..') continue;
                $result[] = $fileEntry(rtrim($path, '/\\') . DIRECTORY_SEPARATOR . $item);
            }
            return ['code' => 200, 'absolutePath' => realpath($path) ?: $path,
                'fileList' => $result, 'count' => count($result)];
        }
        if ($action === 'checksum') {
            if (!is_file($path)) return ['code' => 500, 'msg' => 'file not found: ' . $path];
            $checksum = md5_file($path);
            if ($checksum === false) return ['code' => 500, 'msg' => 'checksum failed: ' . $path];
            return ['code' => 200, 'md5' => $checksum, 'filePath' => $path, 'fileSize' => filesize($path)];
        }
        if ($action === 'createDirectory') { $ok = is_dir($path) || mkdir($path, 0777, true); return ['code' => $ok ? 200 : 500, 'success' => $ok, 'absolutePath' => $path]; }
        if ($action === 'delete') { $ok = $deletePath($path); return ['code' => $ok ? 200 : 500, 'success' => $ok]; }
        if ($action === 'createFile') {
            if (file_exists($path)) return ['code' => 500, 'msg' => 'file already exists: ' . $path];
            $parent = dirname($path);
            if (!is_dir($parent) && !mkdir($parent, 0777, true)) {
                return ['code' => 500, 'msg' => 'cannot create parent directory: ' . $parent];
            }
            $ok = file_put_contents($path, (string)$get($params, 'content', '')) !== false;
            return ['code' => $ok ? 200 : 500, 'success' => $ok, 'absolutePath' => $path];
        }
        if ($action === 'edit') {
            $ok = file_put_contents($path, (string)$get($params, 'content', '')) !== false;
            return ['code' => $ok ? 200 : 500, 'success' => $ok, 'absolutePath' => $path];
        }
        if ($action === 'copy') {
            $target = (string)$get($params, 'destPath', '');
            $strategy = (string)$get($params, 'conflictStrategy', '');
            if ($target === '') return ['code' => 500, 'msg' => 'destPath is required'];
            if (!in_array($strategy, ['overwrite', 'autorename', 'skip'], true)) {
                return ['code' => 500, 'msg' => 'unsupported conflictStrategy: ' . $strategy];
            }
            if (!is_file($path)) return ['code' => 500, 'msg' => 'source is not a file: ' . $path];
            if (file_exists($target) && $strategy === 'skip') {
                return ['code' => 200, 'skipped' => true, 'newPath' => $target];
            }
            if (file_exists($target) && $strategy === 'autorename') {
                $info = pathinfo($target);
                $base = $info['dirname'] . DIRECTORY_SEPARATOR . $info['filename'];
                $extension = isset($info['extension']) ? '.' . $info['extension'] : '';
                for ($i = 1; $i <= 1000; $i++) {
                    $candidate = $base . ' (' . $i . ')' . $extension;
                    if (!file_exists($candidate)) { $target = $candidate; break; }
                }
            }
            $parent = dirname($target);
            if (!is_dir($parent) && !mkdir($parent, 0777, true)) {
                return ['code' => 500, 'msg' => 'cannot create target directory: ' . $parent];
            }
            $backup = null;
            if (file_exists($target)) {
                if ($strategy !== 'overwrite') return ['code' => 500, 'msg' => 'target exists: ' . $target];
                if (is_dir($target)) return ['code' => 500, 'msg' => 'cannot overwrite directory: ' . $target];
                $backup = $target . '.leo-backup-' . uniqid('', true);
                if (!@rename($target, $backup)) {
                    return ['code' => 500, 'msg' => 'cannot prepare target backup: ' . $target];
                }
            }
            $ok = @copy($path, $target);
            if ($ok) {
                if ($backup !== null) @unlink($backup);
                return ['code' => 200, 'success' => true, 'newPath' => $target];
            }
            if (file_exists($target)) @unlink($target);
            if ($backup !== null) @rename($backup, $target);
            return ['code' => 500, 'success' => false, 'msg' => 'copy failed', 'newPath' => $target];
        }
        if ($action === 'move') {
            $target = (string)$get($params, 'newPath', '');
            $strategy = (string)$get($params, 'conflictStrategy', '');
            if ($target === '') return ['code' => 500, 'msg' => 'newPath is required'];
            if (!in_array($strategy, ['overwrite', 'autorename', 'skip'], true)) {
                return ['code' => 500, 'msg' => 'unsupported conflictStrategy: ' . $strategy];
            }
            if (!file_exists($path)) return ['code' => 500, 'msg' => 'source not found: ' . $path];
            if (file_exists($target) && $strategy === 'skip') {
                return ['code' => 200, 'skipped' => true, 'newPath' => $target];
            }
            if (file_exists($target) && $strategy === 'autorename') {
                $info = pathinfo($target);
                $base = $info['dirname'] . DIRECTORY_SEPARATOR . $info['filename'];
                $extension = isset($info['extension']) ? '.' . $info['extension'] : '';
                for ($i = 1; $i <= 1000; $i++) {
                    $candidate = $base . ' (' . $i . ')' . $extension;
                    if (!file_exists($candidate)) { $target = $candidate; break; }
                }
            }
            $backup = null;
            if (file_exists($target)) {
                if ($strategy !== 'overwrite') {
                    return ['code' => 500, 'msg' => 'target exists: ' . $target];
                }
                if (is_dir($target)) return ['code' => 500, 'msg' => 'cannot overwrite directory: ' . $target];
                $backup = $target . '.leo-backup-' . uniqid('', true);
                if (!@rename($target, $backup)) {
                    return ['code' => 500, 'msg' => 'cannot prepare target backup: ' . $target];
                }
            }
            $ok = @rename($path, $target);
            if (!$ok && is_file($path)) {
                $ok = @copy($path, $target) && @unlink($path);
            }
            if ($ok) {
                if ($backup !== null) @unlink($backup);
                return ['code' => 200, 'success' => true, 'newPath' => $target];
            }
            if (file_exists($target)) @unlink($target);
            if ($backup !== null) @rename($backup, $target);
            return ['code' => 500, 'success' => false, 'msg' => 'move failed', 'newPath' => $target];
        }
        throw new InvalidArgumentException('unsupported file action: ' . $action);
    }
];
