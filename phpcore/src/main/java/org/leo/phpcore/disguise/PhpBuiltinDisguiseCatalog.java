package org.leo.phpcore.disguise;

import org.leo.core.entity.Disguise;
import org.leo.core.disguise.DisguiseProtocol;
import org.leo.core.manager.DisguiseManager;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Registers PHP traffic-only profiles. Payload serialization and encryption are separate. */
@Component
public final class PhpBuiltinDisguiseCatalog {

    public static final String JSON_API_ID = "inner_PHP_JSON_API_1.0.0";
    public static final String FORM_SYNC_ID = "inner_PHP_FORM_SYNC_1.0.0";

    public PhpBuiltinDisguiseCatalog(DisguiseManager disguiseManager) {
        for (Disguise disguise : createPresets()) {
            if (!disguiseManager.installDisguise(disguise)) {
                throw new IllegalStateException("PHP built-in disguise registration failed: "
                        + disguise.getDisguiseId());
            }
        }
    }

    public static List<Disguise> createPresets() {
        return List.of(jsonApiEnvelope(), formSync());
    }

    static Disguise jsonApiEnvelope() {
        Disguise disguise = base(
                JSON_API_ID,
                "inner_PHP_JSON_API",
                "PHP JSON API 流量画像：data 字段只承载不透明 PayloadCodec 字节",
                "application/json;charset=utf-8");
        disguise.setTrafficEncodeBody("public byte[] encodeTraffic(byte[] payload) throws Exception {\n"
                + "    if (payload == null) throw new IllegalArgumentException(\"payload不能为空\");\n"
                + "    String token = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(payload);\n"
                + "    java.util.LinkedHashMap envelope = new java.util.LinkedHashMap();\n"
                + "    envelope.put(\"status\", \"ok\"); envelope.put(\"version\", \"1.0\");\n"
                + "    envelope.put(\"timestamp\", java.lang.Long.valueOf(System.currentTimeMillis() / 1000L));\n"
                + "    envelope.put(\"data\", token);\n"
                + "    return org.leo.core.util.json.PortableJsonCodec.encode(envelope);\n"
                + "}");
        disguise.setTrafficDecodeBody("public byte[] decodeTraffic(byte[] data) throws Exception {\n"
                + "    if (data == null || data.length > 22371674) throw new IllegalArgumentException(\"JSON API envelope too large\");\n"
                + "    java.util.Map envelope = org.leo.core.util.json.PortableJsonCodec.decode(data);\n"
                + "    if (!\"1.0\".equals(String.valueOf(envelope.get(\"version\")))) throw new IllegalArgumentException(\"JSON API version mismatch\");\n"
                + "    Object raw = envelope.get(\"data\");\n"
                + "    if (!(raw instanceof String) || ((String) raw).length() == 0) throw new IllegalArgumentException(\"JSON API data field missing\");\n"
                + "    return java.util.Base64.getUrlDecoder().decode((String) raw);\n"
                + "}");
        disguise.setPhpTrafficEncodeBody("if (!is_string($payload)) { throw new InvalidArgumentException('Payload must be binary'); }\n"
                + "$token = rtrim(strtr(base64_encode($payload), '+/', '-_'), '=');\n"
                + "$result = json_encode(array('status' => 'ok', 'version' => '1.0', 'timestamp' => time(), 'data' => $token), JSON_UNESCAPED_SLASHES);\n"
                + "if ($result === false) { throw new RuntimeException('Envelope encode failed: ' . json_last_error_msg()); }\n"
                + "return $result;");
        disguise.setPhpTrafficDecodeBody("if (!is_string($body) || strlen($body) > 22371674) { throw new InvalidArgumentException('Invalid JSON API envelope size'); }\n"
                + "$envelope = json_decode($body, true);\n"
                + "if (!is_array($envelope) || !isset($envelope['version']) || (string)$envelope['version'] !== '1.0' || !isset($envelope['data']) || !is_string($envelope['data']) || $envelope['data'] === '') { throw new InvalidArgumentException('Invalid JSON API envelope'); }\n"
                + "$token = strtr($envelope['data'], '-_', '+/'); $remainder = strlen($token) % 4;\n"
                + "if ($remainder !== 0) { $token .= str_repeat('=', 4 - $remainder); }\n"
                + "$decoded = base64_decode($token, true);\n"
                + "if ($decoded === false) { throw new InvalidArgumentException('Invalid JSON API data'); }\n"
                + "return $decoded;");
        return disguise;
    }

    static Disguise formSync() {
        Disguise disguise = base(
                FORM_SYNC_ID,
                "inner_PHP_FORM_SYNC",
                "PHP 表单同步流量画像：data 字段只承载不透明 PayloadCodec 字节",
                "application/x-www-form-urlencoded;charset=utf-8");
        disguise.setTrafficEncodeBody("public byte[] encodeTraffic(byte[] payload) throws Exception {\n"
                + "    if (payload == null) throw new IllegalArgumentException(\"payload不能为空\");\n"
                + "    String token = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(payload);\n"
                + "    String body = \"action=sync&v=1&ts=\" + (System.currentTimeMillis() / 1000L)\n"
                + "            + \"&data=\" + java.net.URLEncoder.encode(token, \"UTF-8\");\n"
                + "    return body.getBytes(java.nio.charset.StandardCharsets.UTF_8);\n"
                + "}");
        disguise.setTrafficDecodeBody("public byte[] decodeTraffic(byte[] data) throws Exception {\n"
                + "    if (data == null || data.length > 22371674) throw new IllegalArgumentException(\"form envelope too large\");\n"
                + "    String body = new String(data, java.nio.charset.StandardCharsets.UTF_8);\n"
                + "    String[] pairs = body.split(\"&\"); java.util.HashSet seen = new java.util.HashSet();\n"
                + "    String action = null, version = null, token = null;\n"
                + "    for (int i = 0; i < pairs.length; i++) {\n"
                + "        int separator = pairs[i].indexOf('='); String key = java.net.URLDecoder.decode(separator < 0 ? pairs[i] : pairs[i].substring(0, separator), \"UTF-8\");\n"
                + "        String value = java.net.URLDecoder.decode(separator < 0 ? \"\" : pairs[i].substring(separator + 1), \"UTF-8\");\n"
                + "        if (!seen.add(key)) throw new IllegalArgumentException(\"duplicate form field: \" + key);\n"
                + "        if (\"action\".equals(key)) action = value; else if (\"v\".equals(key)) version = value; else if (\"data\".equals(key)) token = value;\n"
                + "    }\n"
                + "    if (!\"sync\".equals(action) || !\"1\".equals(version) || token == null || token.length() == 0) throw new IllegalArgumentException(\"form envelope metadata mismatch\");\n"
                + "    return java.util.Base64.getUrlDecoder().decode(token);\n"
                + "}");
        disguise.setPhpTrafficEncodeBody("if (!is_string($payload)) { throw new InvalidArgumentException('Payload must be binary'); }\n"
                + "$token = rtrim(strtr(base64_encode($payload), '+/', '-_'), '=');\n"
                + "return http_build_query(array('action' => 'sync', 'v' => '1', 'ts' => time(), 'data' => $token), '', '&', PHP_QUERY_RFC3986);");
        disguise.setPhpTrafficDecodeBody("if (!is_string($body) || strlen($body) > 22371674) { throw new InvalidArgumentException('Invalid form envelope size'); }\n"
                + "$fields = array(); $seen = array();\n"
                + "foreach (explode('&', $body) as $pair) { $parts = explode('=', $pair, 2); $key = urldecode($parts[0]); $value = count($parts) === 2 ? urldecode($parts[1]) : '';\n"
                + "    if (isset($seen[$key])) { throw new InvalidArgumentException('Duplicate form field'); } $seen[$key] = true; $fields[$key] = $value; }\n"
                + "if (!isset($fields['action']) || $fields['action'] !== 'sync' || !isset($fields['v']) || $fields['v'] !== '1' || !isset($fields['data']) || $fields['data'] === '') { throw new InvalidArgumentException('Invalid form envelope'); }\n"
                + "$token = strtr($fields['data'], '-_', '+/'); $remainder = strlen($token) % 4; if ($remainder !== 0) { $token .= str_repeat('=', 4 - $remainder); }\n"
                + "$decoded = base64_decode($token, true); if ($decoded === false) { throw new InvalidArgumentException('Invalid form data'); } return $decoded;");
        return disguise;
    }

    private static Disguise base(String id, String name, String description, String contentType) {
        Disguise disguise = new Disguise();
        disguise.setDisguiseId(id);
        disguise.setDisguiseName(name);
        disguise.setSchemaVersion(DisguiseProtocol.SCHEMA_VERSION);
        disguise.setProtocolVersion(DisguiseProtocol.PROTOCOL_VERSION);
        disguise.setSupportedRuntimes(Set.of("php"));
        disguise.setHeaders(Map.of("Content-Type", contentType));
        disguise.setCreateTime(String.valueOf(System.currentTimeMillis()));
        disguise.setCreateUserId("system");
        disguise.setVersion("2.0.0");
        disguise.setDescription(description);
        disguise.setRequirements(Map.of("php", Map.of(
                "minVersion", "5.6",
                "extensions", Set.of("json"),
                "functions", Set.of("base64_encode", "base64_decode", "json_encode", "json_decode",
                        "strlen", "strtr", "str_repeat", "http_build_query", "urldecode"))));
        return disguise;
    }
}
