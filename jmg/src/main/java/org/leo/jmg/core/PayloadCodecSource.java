package org.leo.jmg.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Generates the fixed Java payload codec methods embedded in a target Core. */
final class PayloadCodecSource {
    private static final int MAX_MESSAGE_BYTES = 16 * 1024 * 1024;

    private PayloadCodecSource() { }

    static String encodeMethod(String methodName, String randomField, String secretField) {
        return "private byte[] " + methodName + "(java.util.HashMap params) throws Exception {\n"
                + "    java.io.ByteArrayOutputStream raw = new java.io.ByteArrayOutputStream();\n"
                + "    java.io.ObjectOutputStream object = new java.io.ObjectOutputStream(raw);\n"
                + "    object.writeObject(new java.util.HashMap(params)); object.close();\n"
                + "    java.io.ByteArrayOutputStream zipped = new java.io.ByteArrayOutputStream();\n"
                + "    java.util.zip.GZIPOutputStream gzip = new java.util.zip.GZIPOutputStream(zipped);\n"
                + "    gzip.write(raw.toByteArray()); gzip.close();\n"
                + "    if (zipped.size() > " + MAX_MESSAGE_BYTES + ") throw new IllegalArgumentException(\"Payload too large\");\n"
                + "    byte[] nonce = new byte[12]; this." + randomField + ".nextBytes(nonce);\n"
                + "    javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance(\"AES/GCM/NoPadding\");\n"
                + "    cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, this." + secretField + ", new javax.crypto.spec.GCMParameterSpec(128, nonce));\n"
                + "    byte[] encrypted = cipher.doFinal(zipped.toByteArray());\n"
                + "    java.io.ByteArrayOutputStream result = new java.io.ByteArrayOutputStream();\n"
                + "    result.write(new byte[]{76,80,1}); result.write(nonce); result.write(encrypted);\n"
                + "    byte[] resultBytes = result.toByteArray();\n"
                + "    if (resultBytes.length > " + MAX_MESSAGE_BYTES + ") throw new IllegalArgumentException(\"Payload too large\");\n"
                + "    return resultBytes;\n"
                + "}\n";
    }

    static String decodeMethod(String methodName, String secretField) {
        return "private java.util.HashMap " + methodName + "(byte[] encoded) throws Exception {\n"
                + "    if (encoded == null || encoded.length < 31 || encoded.length > " + MAX_MESSAGE_BYTES
                + " || encoded[0] != 76 || encoded[1] != 80 || encoded[2] != 1) throw new IllegalArgumentException(\"Invalid payload\");\n"
                + "    byte[] nonce = java.util.Arrays.copyOfRange(encoded, 3, 15);\n"
                + "    byte[] encrypted = java.util.Arrays.copyOfRange(encoded, 15, encoded.length);\n"
                + "    javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance(\"AES/GCM/NoPadding\");\n"
                + "    cipher.init(javax.crypto.Cipher.DECRYPT_MODE, this." + secretField + ", new javax.crypto.spec.GCMParameterSpec(128, nonce));\n"
                + "    byte[] zipped = cipher.doFinal(encrypted);\n"
                + "    if (zipped.length > " + MAX_MESSAGE_BYTES + ") throw new IllegalArgumentException(\"Payload too large\");\n"
                + "    java.util.zip.GZIPInputStream gzip = new java.util.zip.GZIPInputStream(new java.io.ByteArrayInputStream(zipped));\n"
                + "    java.io.ByteArrayOutputStream raw = new java.io.ByteArrayOutputStream(); byte[] buffer = new byte[8192]; int n;\n"
                + "    while ((n = gzip.read(buffer)) != -1) {\n"
                + "        if (n > " + MAX_MESSAGE_BYTES + " - raw.size()) throw new IllegalArgumentException(\"Payload too large\");\n"
                + "        raw.write(buffer, 0, n);\n"
                + "    } gzip.close();\n"
                + "    java.io.ObjectInputStream object = new java.io.ObjectInputStream(new java.io.ByteArrayInputStream(raw.toByteArray()));\n"
                + "    Object value = object.readObject(); object.close();\n"
                + "    if (!(value instanceof java.util.Map)) throw new IllegalArgumentException(\"Invalid payload root\");\n"
                + "    java.util.HashMap result = new java.util.HashMap();\n"
                + "    java.util.Iterator entries = ((java.util.Map)value).entrySet().iterator();\n"
                + "    while (entries.hasNext()) {\n"
                + "        java.util.Map.Entry entry = (java.util.Map.Entry) entries.next();\n"
                + "        if (entry.getKey() != null) result.put(String.valueOf(entry.getKey()), entry.getValue());\n"
                + "    }\n"
                + "    return result;\n"
                + "}\n";
    }

    static String secretKeySource(String value) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("payloadKey不能为空");
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder source = new StringBuilder("new javax.crypto.spec.SecretKeySpec(new byte[]{");
            for (int i = 0; i < 16; i++) {
                if (i > 0) source.append(',');
                source.append("(byte)0x");
                source.append(String.format("%02X", digest[i] & 0xff));
            }
            return source.append("}, \"AES\")").toString();
        } catch (Exception e) {
            throw new IllegalStateException("派生 PayloadCodec AES 密钥失败", e);
        }
    }
}
