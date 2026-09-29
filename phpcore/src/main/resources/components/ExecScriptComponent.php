<?php
$get = static function ($value, $key, $default = null) {
    return is_array($value) && array_key_exists($key, $value) ? $value[$key] : $default;
};
return [
    'id' => 'ExecScriptComponent', 'version' => '1.0.0',
    'handle' => static function ($action, $params) use ($get) {
        $language = strtolower((string)$get($params, 'language', 'php'));
        if ($language !== 'php') throw new InvalidArgumentException('PHP runtime only accepts php scripts');
        $script = (string)$get($params, 'script', ''); ob_start();
        try { $returnValue = eval($script); return ['code' => 200, 'output' => (string)ob_get_contents(), 'returnValue' => $returnValue]; }
        finally { ob_end_clean(); }
    }
];
