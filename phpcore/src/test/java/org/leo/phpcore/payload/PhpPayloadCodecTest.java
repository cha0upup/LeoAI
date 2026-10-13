package org.leo.phpcore.payload;

import org.junit.jupiter.api.Test;
import org.leo.core.payload.PayloadFrameCodec;
import org.leo.core.util.json.PortableJsonCodec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

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
        byte[] key = Arrays.copyOf(MessageDigest.getInstance("SHA-512")
                .digest("54ikun".getBytes(StandardCharsets.UTF_8)), 16);
        Map<String, Object> json = PortableJsonCodec.decode(new PayloadFrameCodec(key, "reference").decode(codec.encode(payload)));
        assertEquals(payload.get("text"), json.get("text"));
        assertArrayEquals((byte[]) payload.get("binary"), (byte[]) json.get("binary"));
    }

    @Test
    void emitsPhpSourceWithoutPost56Syntax() {
        String source = PhpPayloadSource.functions("54ikun");
        org.junit.jupiter.api.Assertions.assertFalse(source.contains("Throwable"));
        org.junit.jupiter.api.Assertions.assertFalse(source.contains("??"));
    }
}
