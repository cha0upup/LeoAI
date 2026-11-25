package org.leo.core.util.json;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PortableJsonCodecTest {

    @Test
    void roundTripsMixedPayloadsIncludingNestedAndEmptyBinaryValues() {
        Map<String, Object> decoded = PortableJsonCodec.decode(PortableJsonCodec.encode(Map.of(
                "bytes", new byte[]{0, 1, 2},
                "nested", List.of(Map.of("payload", new byte[]{9, 8})),
                "empty", new byte[0],
                "numbers", new int[]{1, 2},
                "items", new Object[]{true, null, new byte[]{0, -1}},
                "map", Map.of(5, "number-key"),
                "fallback", new StringBuilder("text")
        )));

        assertArrayEquals(new byte[]{0, 1, 2}, (byte[]) decoded.get("bytes"));
        Map<?, ?> nested = (Map<?, ?>) ((List<?>) decoded.get("nested")).get(0);
        assertArrayEquals(new byte[]{9, 8}, (byte[]) nested.get("payload"));
        assertArrayEquals(new byte[0], (byte[]) decoded.get("empty"));
        assertEquals(List.of(1, 2), decoded.get("numbers"));
        List<?> items = (List<?>) decoded.get("items");
        assertEquals(true, items.get(0));
        assertNull(items.get(1));
        assertArrayEquals(new byte[]{0, -1}, (byte[]) items.get(2));
        assertEquals(Map.of("5", "number-key"), decoded.get("map"));
        assertEquals("text", decoded.get("fallback"));
    }

    @Test
    void acceptsMissingOrEmptyInput() {
        assertEquals(Map.of(), PortableJsonCodec.decode(null));
        assertEquals(Map.of(), PortableJsonCodec.decode(new byte[0]));
    }

    @Test
    void recognizesOnlySingleFieldBinaryEnvelopes() {
        String json = "{\"payload\":{\"$leoBinary\":\"AAEC\"},"
                + "\"literal\":{\"$leoBinary\":\"AAEC\",\"kind\":\"text\"}}";
        Map<String, Object> decoded = PortableJsonCodec.decode(json.getBytes(StandardCharsets.UTF_8));
        assertArrayEquals(new byte[]{0, 1, 2}, (byte[]) decoded.get("payload"));
        assertEquals(Map.of("$leoBinary", "AAEC", "kind", "text"), decoded.get("literal"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"[]", "42", "null"})
    void rejectsNonObjectRoots(String json) {
        assertThrows(IllegalArgumentException.class,
                () -> PortableJsonCodec.decode(json.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void rejectsMalformedBinaryEnvelopes() {
        byte[] json = "{\"payload\":{\"$leoBinary\":\"%%%\"}}".getBytes(StandardCharsets.UTF_8);
        assertThrows(IllegalArgumentException.class, () -> PortableJsonCodec.decode(json));
    }
}
