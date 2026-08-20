package org.leo.core.disguise;

import org.leo.core.entity.Disguise;

import java.util.HashMap;
import java.util.List;
import java.util.Set;

/** Built-in Java traffic profiles. Payload serialization and encryption stay outside this catalog. */
public final class JavaBuiltinDisguiseCatalog {
    public static final String BASE64_ID = "inner_Java_Base64_1.0.0";
    public static final String CUSTOM_BASE64_ID = "inner_Java_CustomBase64_1.0.0";
    private static final String CUSTOM_ALPHABET =
            "OPQRSTUVWXYZabcdefghijklmnopqrstuvCDEFGHIJKLMwxyz0ABN123456789@#";

    private JavaBuiltinDisguiseCatalog() {
    }

    public static List<Disguise> createPresets() {
        return List.of(createBase64(), createCustomBase64());
    }

    private static Disguise createBase64() {
        Disguise disguise = base(BASE64_ID, "inner_Java_Base64",
                "Java 流量伪装：使用标准 Base64 包装不透明载荷字节");
        disguise.setTrafficEncodeBody("public byte[] encodeTraffic(byte[] payload) throws Exception {\n"
                + "    return java.util.Base64.getEncoder().encode(payload);\n"
                + "}");
        disguise.setTrafficDecodeBody("public byte[] decodeTraffic(byte[] body) throws Exception {\n"
                + "    return java.util.Base64.getDecoder().decode(new String(body, java.nio.charset.StandardCharsets.UTF_8).replaceAll(\"\\\\s\", \"\"));\n"
                + "}");
        return disguise;
    }

    private static Disguise createCustomBase64() {
        Disguise disguise = base(CUSTOM_BASE64_ID, "inner_Java_CustomBase64",
                "Java 流量伪装：使用自定义字符集的 Base64 包装不透明载荷字节");
        disguise.setRemark("自定义字符集：" + CUSTOM_ALPHABET);
        disguise.setTrafficEncodeBody("public byte[] encodeTraffic(byte[] payload) throws Exception {\n"
                + "    if (payload == null) throw new IllegalArgumentException(\"payload不能为空\");\n"
                + "    String standardAlphabet = \"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/\";\n"
                + "    String customAlphabet = \"" + CUSTOM_ALPHABET + "\";\n"
                + "    char[] encoded = java.util.Base64.getEncoder().encodeToString(payload).toCharArray();\n"
                + "    for (int i = 0; i < encoded.length; i++) {\n"
                + "        if (encoded[i] == '=') continue;\n"
                + "        int index = standardAlphabet.indexOf(encoded[i]);\n"
                + "        if (index < 0) throw new IllegalArgumentException(\"标准 Base64 字符无效\");\n"
                + "        encoded[i] = customAlphabet.charAt(index);\n"
                + "    }\n"
                + "    return new String(encoded).getBytes(\"US-ASCII\");\n"
                + "}");
        disguise.setTrafficDecodeBody("public byte[] decodeTraffic(byte[] body) throws Exception {\n"
                + "    if (body == null) throw new IllegalArgumentException(\"body不能为空\");\n"
                + "    String standardAlphabet = \"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/\";\n"
                + "    String customAlphabet = \"" + CUSTOM_ALPHABET + "\";\n"
                + "    char[] encoded = new String(body, \"US-ASCII\").replaceAll(\"\\\\s\", \"\").toCharArray();\n"
                + "    for (int i = 0; i < encoded.length; i++) {\n"
                + "        if (encoded[i] == '=') continue;\n"
                + "        int index = customAlphabet.indexOf(encoded[i]);\n"
                + "        if (index < 0) throw new IllegalArgumentException(\"自定义 Base64 字符无效\");\n"
                + "        encoded[i] = standardAlphabet.charAt(index);\n"
                + "    }\n"
                + "    return java.util.Base64.getDecoder().decode(new String(encoded).getBytes(\"US-ASCII\"));\n"
                + "}");
        return disguise;
    }

    private static Disguise base(String id, String name, String description) {
        Disguise disguise = new Disguise();
        disguise.setDisguiseId(id);
        disguise.setDisguiseName(name);
        disguise.setSchemaVersion(DisguiseProtocol.SCHEMA_VERSION);
        disguise.setProtocolVersion(DisguiseProtocol.PROTOCOL_VERSION);
        disguise.setSupportedRuntimes(Set.of("java"));
        HashMap<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "text/plain;charset=utf-8");
        disguise.setHeaders(headers);
        disguise.setCreateTime(String.valueOf(System.currentTimeMillis()));
        disguise.setCreateUserId("system");
        disguise.setVersion("1.0.0");
        disguise.setUpdateTime(String.valueOf(System.currentTimeMillis()));
        disguise.setDescription(description);
        disguise.setRemark("");
        return disguise;
    }
}
