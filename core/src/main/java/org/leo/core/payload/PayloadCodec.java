package org.leo.core.payload;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Fixed Java payload pipeline:
 * HashMap serialization -> GZIP -> AES-CBC with a random IV.
 *
 * <p>The user supplied key is never put on the wire. Both endpoints must be
 * configured with the same key. The encoded payload is the IV followed by the
 * ciphertext.</p>
 */
public final class PayloadCodec {
    private final PayloadFrameCodec frameCodec;

    public PayloadCodec(String userKey) {
        if (userKey == null || userKey.trim().isEmpty()) {
            throw new IllegalArgumentException("AES 密钥不能为空");
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(userKey.getBytes(StandardCharsets.UTF_8));
            this.frameCodec = new PayloadFrameCodec(Arrays.copyOf(digest, PayloadFrameCodec.KEY_BYTES), "PayloadCodec");
        } catch (Exception e) {
            throw new IllegalStateException("初始化 PayloadCodec 失败", e);
        }
    }

    public byte[] encode(Map<String, Object> payload) throws Exception {
        if (payload == null) throw new IllegalArgumentException("payload 不能为空");
        return frameCodec.encode(serialize(payload));
    }

    public Map<String, Object> decode(byte[] encoded) throws Exception {
        Object value;
        try (ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(frameCodec.decode(encoded)))) {
            value = input.readObject();
        }
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
        try (ObjectOutputStream stream = new ObjectOutputStream(output)) {
            stream.writeObject(new HashMap<>(payload));
        }
        return output.toByteArray();
    }
}
