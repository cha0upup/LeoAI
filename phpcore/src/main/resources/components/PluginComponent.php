<?php
$get = static function ($value, $key, $default = null) {
    return is_array($value) && array_key_exists($key, $value) ? $value[$key] : $default;
};
return [
    'id' => 'PluginComponent', 'version' => '1.0.0',
    'handle' => static function ($action, $params) use ($get) {
        $source = (string)$get($params, 'source', '');
        $pluginParams = $get($params, 'pluginParams', []);
        if ($source === '') throw new InvalidArgumentException('plugin source is required');
        $callable = eval('return function(array $params) {' . $source . "\n};");
        $result = $callable(is_array($pluginParams) ? $pluginParams : []);
        if (!is_array($result)) return ['code' => 200, 'result' => $result];
        if (!array_key_exists('code', $result)) $result['code'] = 200;
        return $result;
    }
];
