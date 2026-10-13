package org.leo.core.payload;

import org.junit.jupiter.api.Test;
import java.io.ByteArrayInputStream;
import java.io.ObjectInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PayloadCodecTest {
    private static final String KEY = "payload-test-key";

    @Test
    void preservesJavaSerializationAndSha256KeyDerivation() throws Exception {
        PayloadCodec codec = new PayloadCodec(KEY);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("operation", "PING");
        payload.put("params", Map.of("count", 3));

        byte[] first = codec.encode(payload);
        assertEquals(payload, codec.decode(first));
        byte[] key = Arrays.copyOf(MessageDigest.getInstance("SHA-256").digest(KEY.getBytes(StandardCharsets.UTF_8)), 16);
        byte[] serialized = new PayloadFrameCodec(key, "reference").decode(first);
        try (ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(serialized))) {
            assertEquals(payload, input.readObject());
        }
    }
}
