package org.leo.core.payload;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PayloadCodecTest {
    private static final String KEY = "payload-test-key";

    @Test
    void roundTripsStructuredPayloadWithFreshCiphertext() throws Exception {
        PayloadCodec codec = new PayloadCodec(KEY);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("operation", "PING");
        payload.put("params", Map.of("count", 3));

        byte[] first = codec.encode(payload);
        byte[] second = codec.encode(payload);

        assertTrue(first.length >= 32);
        assertEquals(0, (first.length - 16) % 16);
        org.junit.jupiter.api.Assertions.assertFalse(java.util.Arrays.equals(first, second));
        assertEquals(payload, codec.decode(first));
    }

    @Test
    void rejectsWrongKey() throws Exception {
        byte[] encoded = new PayloadCodec(KEY).encode(Map.of("value", "secret"));
        assertThrows(Exception.class, () -> new PayloadCodec("different-key").decode(encoded));
    }
}
