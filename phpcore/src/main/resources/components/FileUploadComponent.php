<?php
$get = static function ($value, $key, $default = null) {
    return is_array($value) && array_key_exists($key, $value) ? $value[$key] : $default;
};
return [
    'id' => 'FileUploadComponent', 'version' => '1.0.0',
    'handle' => static function ($action, $params) use ($get) {
        $path = (string)$get($params, 'path', '');
        $rawOffset = $get($params, 'offset', 0);
        $offset = filter_var($rawOffset, FILTER_VALIDATE_INT);
        $data = $get($params, 'data', '');
        if ($path === '') return ['code' => 400, 'msg' => 'path is required'];
        if ($offset === false || $offset < 0) return ['code' => 400, 'msg' => 'offset must be a non-negative integer'];
        if (!is_string($data)) return ['code' => 400, 'msg' => 'data must be binary'];
        $length = strlen($data);
        if ($length > 1048576) return ['code' => 413, 'msg' => 'chunk exceeds 1MB'];
        if ($offset > PHP_INT_MAX - $length) return ['code' => 400, 'msg' => 'offset overflow'];
        $handle = fopen($path, 'c+b');
        if ($handle === false) throw new RuntimeException('file cannot be opened');
        try {
            if (!flock($handle, LOCK_EX)) throw new RuntimeException('file cannot be locked');
            if (fseek($handle, $offset) !== 0) throw new RuntimeException('invalid offset');
            $written = 0;
            while ($written < $length) {
                $count = fwrite($handle, substr($data, $written));
                if ($count === false || $count === 0) throw new RuntimeException('incomplete chunk write');
                $written += $count;
            }
            if (!fflush($handle)) throw new RuntimeException('file flush failed');
            $stat = fstat($handle);
            return ['code' => 200, 'success' => true, 'written' => $written, 'bytesWritten' => $written,
                'offset' => $offset, 'nextOffset' => $offset + $written, 'fileLength' => $stat['size']];
        } finally { fclose($handle); }
    }
];
