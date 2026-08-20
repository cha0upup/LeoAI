package org.leo.phpcore.payload;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PhpPayloadCodecTest {
    @Test
    void roundTripsPortableJsonGzipAesPayload() throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("text", "hello世界");
        payload.put("binary", new byte[]{0, 1, 127, -1});
        payload.put("nested", Map.of("enabled", true));

        PhpPayloadCodec codec = new PhpPayloadCodec("54ikun");
        Map<String, Object> decoded = codec.decode(codec.encode(payload));

        assertEquals(payload.get("text"), decoded.get("text"));
        assertArrayEquals((byte[]) payload.get("binary"), (byte[]) decoded.get("binary"));
        assertEquals(payload.get("nested"), decoded.get("nested"));
    }

    @Test
    void rejectsTamperedPayloadAndWrongKey() throws Exception {
        PhpPayloadCodec codec = new PhpPayloadCodec("54ikun");
        byte[] encoded = codec.encode(Map.of("value", "test"));
        encoded[encoded.length - 1] ^= 1;
        assertThrows(IllegalArgumentException.class, () -> codec.decode(encoded));

        byte[] fresh = new PhpPayloadCodec("54ikun").encode(Map.of("value", "test"));
        assertThrows(IllegalArgumentException.class,
                () -> new PhpPayloadCodec("other-key").decode(fresh));
    }

    @Test
    void emitsPhpSourceWithoutPost56Syntax() {
        String source = PhpPayloadSource.functions("54ikun");
        org.junit.jupiter.api.Assertions.assertTrue(source.contains("AES-128-CBC"));
        org.junit.jupiter.api.Assertions.assertTrue(source.contains("hash_hmac"));
        org.junit.jupiter.api.Assertions.assertFalse(source.contains("Throwable"));
        org.junit.jupiter.api.Assertions.assertFalse(source.contains("??"));
    }
}
