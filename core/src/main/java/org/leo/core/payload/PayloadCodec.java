package org.leo.core.payload;

import org.leo.core.net.TransportLimits;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Fixed Java payload pipeline:
 * HashMap serialization -> GZIP -> AES-GCM.
 *
 * <p>The user supplied key is never put on the wire. Both endpoints must be
 * configured with the same key. The encoded format is versioned and contains
 * a fresh nonce for every payload.</p>
 */
public final class PayloadCodec {
    private static final byte[] MAGIC = new byte[]{'L', 'P', 1};
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final int AES_KEY_BYTES = 16;
    private static final int BUFFER_BYTES = 8192;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKeySpec key;

    public PayloadCodec(String userKey) {
        if (userKey == null || userKey.trim().isEmpty()) {
            throw new IllegalArgumentException("AES 密钥不能为空");
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(userKey.getBytes(StandardCharsets.UTF_8));
            this.key = new SecretKeySpec(Arrays.copyOf(digest, AES_KEY_BYTES), "AES");
        } catch (Exception e) {
            throw new IllegalStateException("初始化 PayloadCodec 失败", e);
        }
    }

    public byte[] encode(Map<String, Object> payload) throws Exception {
        if (payload == null) throw new IllegalArgumentException("payload 不能为空");
        byte[] serialized = serialize(payload);
        byte[] compressed = gzip(serialized);
        TransportLimits.requireMessageSize(compressed);

        byte[] nonce = new byte[NONCE_BYTES];
        RANDOM.nextBytes(nonce);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
        byte[] encrypted = cipher.doFinal(compressed);

        ByteArrayOutputStream output = new ByteArrayOutputStream(
                MAGIC.length + nonce.length + encrypted.length);
        output.write(MAGIC);
        output.write(nonce);
        output.write(encrypted);
        byte[] result = output.toByteArray();
        TransportLimits.requireMessageSize(result);
        return result;
    }

    public Map<String, Object> decode(byte[] encoded) throws Exception {
        if (encoded == null || encoded.length < MAGIC.length + NONCE_BYTES + 16) {
            throw new IllegalArgumentException("PayloadCodec 数据长度无效");
        }
        TransportLimits.requireMessageSize(encoded);
        for (int i = 0; i < MAGIC.length; i++) {
            if (encoded[i] != MAGIC[i]) throw new IllegalArgumentException("PayloadCodec 版本不匹配");
        }

        byte[] nonce = Arrays.copyOfRange(encoded, MAGIC.length, MAGIC.length + NONCE_BYTES);
        byte[] encrypted = Arrays.copyOfRange(encoded, MAGIC.length + NONCE_BYTES, encoded.length);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
        byte[] compressed = cipher.doFinal(encrypted);
        byte[] serialized = gunzip(compressed);

        ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(serialized));
        Object value = input.readObject();
        input.close();
        if (!(value instanceof Map<?, ?>)) {
            throw new IllegalArgumentException("PayloadCodec 根对象必须是 Map");
        }
        Map<String, Object> result = new HashMap<>();
        for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
            if (entry.getKey() != null) result.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return result;
    }

    private byte[] serialize(Map<String, Object> payload) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ObjectOutputStream stream = new ObjectOutputStream(output);
        stream.writeObject(new HashMap<>(payload));
        stream.close();
        return output.toByteArray();
    }

    private byte[] gzip(byte[] value) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        GZIPOutputStream stream = new GZIPOutputStream(output);
        stream.write(value);
        stream.close();
        return output.toByteArray();
    }

    private byte[] gunzip(byte[] value) throws Exception {
        GZIPInputStream input = new GZIPInputStream(new ByteArrayInputStream(value));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[BUFFER_BYTES];
        int length;
        while ((length = input.read(buffer)) != -1) {
            if (length > TransportLimits.MAX_MESSAGE_BYTES - output.size()) {
                throw new IllegalArgumentException("PayloadCodec 解压结果超过限制");
            }
            output.write(buffer, 0, length);
        }
        input.close();
        return output.toByteArray();
    }
}
