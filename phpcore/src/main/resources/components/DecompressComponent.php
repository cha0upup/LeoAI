<?php
$get = static function ($value, $key, $default = null) {
    return is_array($value) && array_key_exists($key, $value) ? $value[$key] : $default;
};
return [
    'id' => 'DecompressComponent', 'version' => '1.0.0',
    'handle' => static function ($action, $params) use ($get) {
        $format = strtolower(trim((string)$get($params, 'format', 'zip')));
        if ($format !== '' && $format !== 'zip') return ['code' => 400, 'msg' => 'supported extraction format: zip'];
        if (!class_exists('ZipArchive')) throw new RuntimeException('ZipArchive extension is missing');
        $source = (string)$get($params, 'src', ''); $destination = (string)$get($params, 'des', '');
        if ($source === '' || $destination === '') throw new InvalidArgumentException('src and des are required');
        $zip = new ZipArchive();
        if ($zip->open($source) !== true) throw new RuntimeException('zip source cannot be opened');
        try {
            if (!is_dir($destination) && !mkdir($destination, 0777, true)) throw new RuntimeException('destination cannot be created');
            $root = realpath($destination);
            if ($root === false) throw new RuntimeException('invalid destination');
            $prefix = rtrim($root, DIRECTORY_SEPARATOR) . DIRECTORY_SEPARATOR;
            $resolve = static function ($name) use ($root, $prefix) {
                $normalized = str_replace('\\', '/', $name);
                if ($normalized === '' || strpos($normalized, "\0") !== false || strpos($normalized, ':') !== false ||
                    substr($normalized, 0, 1) === '/' || preg_match('~(^|/)\.\.($|/)~', $normalized)) {
                    throw new RuntimeException('unsafe zip entry');
                }
                $path = $root;
                foreach (explode('/', rtrim($normalized, '/')) as $part) {
                    if ($part === '' || $part === '.') continue;
                    $path = rtrim($path, DIRECTORY_SEPARATOR) . DIRECTORY_SEPARATOR . $part;
                    if (is_link($path)) throw new RuntimeException('zip target contains a symbolic link');
                    if (file_exists($path)) {
                        $real = realpath($path);
                        if ($real === false || ($real !== $root && strpos($real, $prefix) !== 0)) throw new RuntimeException('zip target escapes destination');
                    }
                }
                if ($path === $root) throw new RuntimeException('zip entry targets destination root');
                return $path;
            };
            $maxEntry = 268435456; $maxTotal = 1073741824;
            if ($zip->numFiles > 10000) throw new RuntimeException('archive exceeds 10000 entries');
            $entries = []; $seen = []; $declaredTotal = 0;
            // Validate the complete manifest before writing any archive contents.
            for ($index = 0; $index < $zip->numFiles; $index++) {
                $stat = $zip->statIndex($index);
                if ($stat === false) throw new RuntimeException('zip entry metadata unavailable');
                $name = $stat['name']; $target = $resolve($name);
                $key = DIRECTORY_SEPARATOR === '\\' ? strtolower($target) : $target;
                if (isset($seen[$key])) throw new RuntimeException('duplicate zip target');
                $seen[$key] = true;
                $directory = substr(str_replace('\\', '/', $name), -1) === '/';
                if (method_exists($zip, 'getExternalAttributesIndex')) {
                    $system = 0; $attributes = 0;
                    if ($zip->getExternalAttributesIndex($index, $system, $attributes) && (($attributes >> 16) & 0170000) === 0120000) {
                        throw new RuntimeException('symbolic link entries are unsupported');
                    }
                }
                $size = (int)$stat['size'];
                if ($size < 0 || $size > $maxEntry) throw new RuntimeException('archive entry exceeds 256MB');
                if (!$directory) $declaredTotal += $size;
                if ($declaredTotal > $maxTotal) throw new RuntimeException('archive exceeds 1GB expanded size');
                $entries[] = ['name' => $name, 'directory' => $directory, 'size' => $size, 'crc' => $stat['crc']];
            }
            $total = 0; $fileCount = 0;
            foreach ($entries as $entry) {
                $target = $resolve($entry['name']);
                if ($entry['directory']) {
                    if (!is_dir($target) && !mkdir($target, 0777, true)) throw new RuntimeException('directory cannot be created');
                    continue;
                }
                if (file_exists($target) && !is_file($target)) throw new RuntimeException('zip target is not a regular file');
                $parent = dirname($target);
                if (!is_dir($parent) && !mkdir($parent, 0777, true)) throw new RuntimeException('parent directory cannot be created');
                $input = $zip->getStream($entry['name']);
                if ($input === false) throw new RuntimeException('zip entry cannot be opened');
                $temp = null; $output = null; $backup = null;
                try {
                    $temp = tempnam($parent, '.leo-decompress-');
                    if ($temp === false) throw new RuntimeException('temporary output cannot be created');
                    $output = fopen($temp, 'wb');
                    if ($output === false) throw new RuntimeException('temporary output cannot be opened');
                    $written = 0; $checksum = hash_init('crc32b');
                    while (!feof($input)) {
                        $data = fread($input, 65536);
                        if ($data === false || ($data === '' && !feof($input))) throw new RuntimeException('zip entry read failed');
                        $length = strlen($data);
                        if ($written + $length > $maxEntry || $total + $length > $maxTotal) throw new RuntimeException('archive expansion limit exceeded');
                        for ($offset = 0; $offset < $length;) {
                            $count = fwrite($output, substr($data, $offset));
                            if ($count === false || $count === 0) throw new RuntimeException('incomplete output write');
                            $offset += $count;
                        }
                        hash_update($checksum, $data); $written += $length; $total += $length;
                    }
                    if ($written !== $entry['size'] || hash_final($checksum) !== sprintf('%08x', $entry['crc'])) throw new RuntimeException('zip entry integrity check failed');
                    if (!fflush($output)) throw new RuntimeException('output flush failed');
                    fclose($output); $output = null;
                    $target = $resolve($entry['name']);
                    if (file_exists($target)) {
                        if (!chmod($temp, fileperms($target) & 0777)) throw new RuntimeException('cannot preserve output permissions');
                        $backup = tempnam($parent, '.leo-decompress-backup-');
                        if ($backup === false || !unlink($backup) || !rename($target, $backup)) throw new RuntimeException('cannot back up output');
                    }
                    if (!rename($temp, $target)) {
                        if ($backup !== null && !rename($backup, $target)) throw new RuntimeException('commit failed; original retained at ' . $backup);
                        throw new RuntimeException('output commit failed');
                    }
                    if ($backup !== null) unlink($backup);
                    $fileCount++;
                } finally {
                    fclose($input);
                    if (is_resource($output)) fclose($output);
                    if (is_string($temp) && file_exists($temp)) unlink($temp);
                }
            }
            return ['code' => 200, 'success' => true, 'path' => $root, 'format' => 'zip', 'fileCount' => $fileCount, 'totalSize' => $total];
        } finally { $zip->close(); }
    }
];
