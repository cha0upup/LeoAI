package org.leo.phpcore.payload;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Generates PHP 5.6-compatible payload codec source for generated endpoints. */
public final class PhpPayloadSource {
    private PhpPayloadSource() { }

    public static String functions(String userKey) {
        byte[] digest;
        try {
            digest = MessageDigest.getInstance("SHA-512")
                    .digest(userKey.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("派生 PHP PayloadCodec 密钥失败", e);
        }
        String encryptionKey = hex(digest, 0, 16);
        String authenticationKey = hex(digest, 16, 32);
        return "function leo_payload_encode($payload) {\n"
                + "    if (!is_array($payload)) { throw new InvalidArgumentException('Payload root must be an array'); }\n"
                + "    $json = json_encode(leo_wire_encode($payload), JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES);\n"
                + "    if ($json === false) { throw new RuntimeException('Payload JSON encode failed: ' . json_last_error_msg()); }\n"
                + "    $compressed = gzencode($json, 6, ZLIB_ENCODING_GZIP);\n"
                + "    if ($compressed === false || strlen($compressed) > 16777216) { throw new RuntimeException('Payload compression failed'); }\n"
                + "    $iv = openssl_random_pseudo_bytes(16, $strong);\n"
                + "    if ($iv === false || strlen($iv) !== 16 || !$strong) { throw new RuntimeException('Secure IV generation failed'); }\n"
                + "    $ciphertext = openssl_encrypt($compressed, 'AES-128-CBC', pack('H*', '" + encryptionKey + "'), OPENSSL_RAW_DATA, $iv);\n"
                + "    if ($ciphertext === false) { throw new RuntimeException('Payload encryption failed'); }\n"
                + "    $frame = 'LPH' . chr(1) . $iv . $ciphertext;\n"
                + "    $mac = hash_hmac('sha256', $frame, pack('H*', '" + authenticationKey + "'), true);\n"
                + "    return $frame . $mac;\n"
                + "}\n"
                + "function leo_payload_decode($encoded) {\n"
                + "    if (!is_string($encoded) || strlen($encoded) < 68 || strlen($encoded) > 16777216) { throw new InvalidArgumentException('Invalid payload size'); }\n"
                + "    if (substr($encoded, 0, 4) !== 'LPH' . chr(1)) { throw new InvalidArgumentException('Payload version mismatch'); }\n"
                + "    $macOffset = strlen($encoded) - 32;\n"
                + "    $signed = substr($encoded, 0, $macOffset);\n"
                + "    $actualMac = substr($encoded, $macOffset);\n"
                + "    $expectedMac = hash_hmac('sha256', $signed, pack('H*', '" + authenticationKey + "'), true);\n"
                + "    if (!hash_equals($expectedMac, $actualMac)) { throw new InvalidArgumentException('Payload authentication failed'); }\n"
                + "    $iv = substr($encoded, 4, 16);\n"
                + "    $ciphertext = substr($encoded, 20, $macOffset - 20);\n"
                + "    $compressed = openssl_decrypt($ciphertext, 'AES-128-CBC', pack('H*', '" + encryptionKey + "'), OPENSSL_RAW_DATA, $iv);\n"
                + "    if ($compressed === false) { throw new InvalidArgumentException('Payload decryption failed'); }\n"
                + "    $json = gzdecode($compressed);\n"
                + "    if ($json === false || strlen($json) > 16777216) { throw new InvalidArgumentException('Payload decompression failed'); }\n"
                + "    $decoded = json_decode($json, true);\n"
                + "    if (!is_array($decoded)) { throw new InvalidArgumentException('Payload JSON decode failed: ' . json_last_error_msg()); }\n"
                + "    return leo_wire_decode($decoded);\n"
                + "}\n";
    }

    private static String hex(byte[] value, int offset, int length) {
        StringBuilder result = new StringBuilder(length * 2);
        for (int i = offset; i < offset + length; i++) {
            result.append(String.format("%02x", value[i] & 0xff));
        }
        return result.toString();
    }
}
