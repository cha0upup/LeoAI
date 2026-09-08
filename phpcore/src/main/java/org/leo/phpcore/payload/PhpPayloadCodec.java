package org.leo.phpcore.payload;

import org.leo.core.net.TransportLimits;
import org.leo.core.util.json.PortableJsonCodec;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/** PHP-compatible payload codec: portable JSON -> GZIP -> AES-CBC. */
public final class PhpPayloadCodec {
    private static final int IV_BYTES = 16;
    private static final int AES_BLOCK_BYTES = 16;
    private static final int AES_KEY_BYTES = 16;
    private static final int BUFFER_BYTES = 8192;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKeySpec encryptionKey;

    public PhpPayloadCodec(String userKey) {
        if (userKey == null || userKey.trim().isEmpty()) {
            throw new IllegalArgumentException("PHP PayloadCodec AES 密钥不能为空");
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-512")
                    .digest(userKey.getBytes(StandardCharsets.UTF_8));
            this.encryptionKey = new SecretKeySpec(Arrays.copyOfRange(digest, 0, AES_KEY_BYTES), "AES");
        } catch (Exception e) {
            throw new IllegalStateException("初始化 PHP PayloadCodec 失败", e);
        }
    }

    public byte[] encode(Map<String, Object> payload) throws Exception {
        if (payload == null) throw new IllegalArgumentException("payload 不能为空");
        byte[] json = PortableJsonCodec.encode(payload);
        byte[] compressed = gzip(json);
        TransportLimits.requireMessageSize(compressed);

        byte[] iv = new byte[IV_BYTES];
        RANDOM.nextBytes(iv);
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE, encryptionKey, new IvParameterSpec(iv));
        byte[] encrypted = cipher.doFinal(compressed);

        ByteArrayOutputStream frame = new ByteArrayOutputStream(iv.length + encrypted.length);
        frame.write(iv);
        frame.write(encrypted);
        byte[] result = frame.toByteArray();
        TransportLimits.requireMessageSize(result);
        return result;
    }

    public Map<String, Object> decode(byte[] encoded) throws Exception {
        if (encoded == null || encoded.length < IV_BYTES + AES_BLOCK_BYTES
                || (encoded.length - IV_BYTES) % AES_BLOCK_BYTES != 0) {
            throw new IllegalArgumentException("PHP PayloadCodec 数据长度无效");
        }
        TransportLimits.requireMessageSize(encoded);
        byte[] iv = Arrays.copyOfRange(encoded, 0, IV_BYTES);
        byte[] encrypted = Arrays.copyOfRange(encoded, IV_BYTES, encoded.length);
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.DECRYPT_MODE, encryptionKey, new IvParameterSpec(iv));
        byte[] compressed = cipher.doFinal(encrypted);
        byte[] json = gunzip(compressed);
        return PortableJsonCodec.decode(json);
    }

    private byte[] gzip(byte[] value) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        GZIPOutputStream gzip = new GZIPOutputStream(output);
        gzip.write(value);
        gzip.close();
        return output.toByteArray();
    }

    private byte[] gunzip(byte[] value) throws Exception {
        GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(value));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[BUFFER_BYTES];
        int length;
        while ((length = gzip.read(buffer)) != -1) {
            if (length > TransportLimits.MAX_MESSAGE_BYTES - output.size()) {
                throw new IllegalArgumentException("PHP PayloadCodec 解压结果超过限制");
            }
            output.write(buffer, 0, length);
        }
        gzip.close();
        return output.toByteArray();
    }
}
