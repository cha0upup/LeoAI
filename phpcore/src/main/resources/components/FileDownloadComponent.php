<?php
$get = static function ($value, $key, $default = null) {
    return is_array($value) && array_key_exists($key, $value) ? $value[$key] : $default;
};
return [
    'id' => 'FileDownloadComponent', 'version' => '1.0.0',
    'handle' => static function ($action, $params) use ($get) {
        $path = (string)$get($params, 'path', '');
        $offset = filter_var($get($params, 'offset', 0), FILTER_VALIDATE_INT);
        $size = filter_var($get($params, 'size', 1048576), FILTER_VALIDATE_INT);
        if ($offset === false || $offset < 0 || $size === false || $size <= 0) return ['code' => 400, 'msg' => 'invalid offset or size'];
        if (!is_file($path)) return ['code' => 400, 'msg' => 'path is not a regular file'];
        $size = min(1048576, $size);
        $handle = fopen($path, 'rb'); if ($handle === false) throw new RuntimeException('file cannot be opened');
        try {
            $stat = fstat($handle); $total = (int)$stat['size'];
            if ($offset >= $total && !($offset === 0 && $total === 0)) return ['code' => 416, 'msg' => 'offset exceeds file size'];
            if (fseek($handle, $offset) !== 0) throw new RuntimeException('invalid offset');
            $data = fread($handle, $size); if ($data === false) throw new RuntimeException('file read failed');
            $nextOffset = $offset + strlen($data); $complete = $nextOffset >= $total;
            return ['code' => $complete ? 200 : 100, 'data' => leo_binary($data), 'offset' => $offset,
                'length' => $total, 'bytesRead' => strlen($data), 'nextOffset' => $nextOffset, 'isComplete' => $complete];
        } finally { fclose($handle); }
    }
];
