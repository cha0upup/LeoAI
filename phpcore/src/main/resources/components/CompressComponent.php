<?php
$get = static function ($value, $key, $default = null) {
    return is_array($value) && array_key_exists($key, $value) ? $value[$key] : $default;
};
return [
    'id' => 'CompressComponent', 'version' => '1.0.0',
    'handle' => static function ($action, $params) use ($get) {
        if (!class_exists('ZipArchive')) throw new RuntimeException('ZipArchive extension is missing');
        $source = realpath((string)$get($params, 'src', ''));
        $destination = (string)$get($params, 'des', '');
        if ($source === false || $destination === '') throw new InvalidArgumentException('valid src and des are required');
        if (!is_file($source) && !is_dir($source)) throw new RuntimeException('source is not a regular file or directory');
        $parent = dirname($destination);
        if (!is_dir($parent) && !mkdir($parent, 0777, true)) throw new RuntimeException('destination directory cannot be created');
        $destination = realpath($parent) . DIRECTORY_SEPARATOR . basename($destination);
        if ($source === $destination || is_link($destination) || (file_exists($destination) && !is_file($destination))) {
            throw new RuntimeException('invalid zip destination');
        }
        $exclude = trim((string)$get($params, 'exclude', ''));
        $pattern = $exclude === '' ? null : '~^(?:' . str_replace('~', '\\~', $exclude) . ')$~i';
        if ($pattern !== null && @preg_match($pattern, '') === false) throw new InvalidArgumentException('invalid exclude pattern');
        $temp = tempnam(dirname($destination), '.leo-compress-');
        if ($temp === false) throw new RuntimeException('temporary archive cannot be created');
        $zip = new ZipArchive(); $opened = false; $backup = null;
        try {
            if ($zip->open($temp, ZipArchive::OVERWRITE) !== true) throw new RuntimeException('zip destination cannot be opened');
            $opened = true;
            $include = static function ($path, $name) use ($destination, $temp, $pattern) {
                if (is_link($path) || $path === $destination || $path === $temp) return false;
                return $pattern === null || (!preg_match($pattern, $name) && !preg_match($pattern, basename($path)));
            };
            $add = static function ($path, $name, $directory) use ($zip, $include) {
                if (!$include($path, $name)) return;
                $ok = $directory ? $zip->addEmptyDir($name) : $zip->addFile($path, $name);
                if (!$ok) throw new RuntimeException('cannot add archive entry: ' . $name);
            };
            if (is_file($source)) {
                $add($source, basename($source), false);
            } else {
                $base = rtrim($source, DIRECTORY_SEPARATOR) . DIRECTORY_SEPARATOR;
                // Prune excluded directories and links before traversing their children.
                $iterator = new RecursiveCallbackFilterIterator(
                    new RecursiveDirectoryIterator($source, FilesystemIterator::SKIP_DOTS),
                    static function ($file) use ($base, $include) {
                        $path = $file->getPathname();
                        $name = str_replace(DIRECTORY_SEPARATOR, '/', substr($path, strlen($base)));
                        return $include($path, $name);
                    }
                );
                foreach (new RecursiveIteratorIterator($iterator, RecursiveIteratorIterator::SELF_FIRST) as $file) {
                    if (!$file->isDir() && !$file->isFile()) throw new RuntimeException('unsupported source entry');
                    $name = str_replace(DIRECTORY_SEPARATOR, '/', substr($file->getPathname(), strlen($base)));
                    $add($file->getPathname(), $name, $file->isDir());
                }
            }
            $closed = $zip->close(); $opened = false;
            if (!$closed) throw new RuntimeException('zip finalization failed');
            // libzip may remove an empty archive; produce a valid empty ZIP explicitly.
            if (!file_exists($temp) && file_put_contents($temp, "PK\x05\x06" . str_repeat("\0", 18)) !== 22) {
                throw new RuntimeException('empty zip write failed');
            }
            if (file_exists($destination)) {
                if (!chmod($temp, fileperms($destination) & 0777)) throw new RuntimeException('cannot preserve destination permissions');
                $backup = tempnam(dirname($destination), '.leo-compress-backup-');
                if ($backup === false || !unlink($backup) || !rename($destination, $backup)) throw new RuntimeException('cannot back up destination');
            }
            if (!rename($temp, $destination)) {
                if ($backup !== null && !rename($backup, $destination)) throw new RuntimeException('commit failed; original retained at ' . $backup);
                throw new RuntimeException('archive commit failed');
            }
            if ($backup !== null) unlink($backup);
            clearstatcache(true, $destination);
            return ['code' => 200, 'success' => true, 'path' => $destination, 'size' => filesize($destination), 'format' => 'zip'];
        } finally {
            if ($opened) $zip->close();
            if (file_exists($temp)) unlink($temp);
        }
    }
];
