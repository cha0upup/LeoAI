package org.leo.core.util.json;

import com.alibaba.fastjson.JSON;

import java.lang.reflect.Array;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runtime-neutral JSON codec used by portable component and wire payloads.
 *
 * <p>JSON has no binary type, so byte arrays are represented as
 * {@code {"$leoBinary":"base64..."}} and restored recursively on decode.
 */
public final class PortableJsonCodec {

    public static final String BINARY_MARKER = "$leoBinary";

    private PortableJsonCodec() {
    }

    public static byte[] encode(Map<String, Object> value) {
        return JSON.toJSONString(toWireValue(value)).getBytes(StandardCharsets.UTF_8);
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> decode(byte[] value) {
        if (value == null || value.length == 0) {
            return new LinkedHashMap<>();
        }
        Object parsed = JSON.parse(new String(value, StandardCharsets.UTF_8));
        Object restored = fromWireValue(parsed);
        if (!(restored instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("portable JSON root必须是对象");
        }
        return (Map<String, Object>) map;
    }

    public static Object toWireValue(Object value) {
        if (value == null || value instanceof String || value instanceof Number || value instanceof Boolean) {
            return value;
        }
        if (value instanceof byte[] bytes) {
            Map<String, Object> tagged = new LinkedHashMap<>();
            tagged.put(BINARY_MARKER, Base64.getEncoder().encodeToString(bytes));
            return tagged;
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                result.put(String.valueOf(entry.getKey()), toWireValue(entry.getValue()));
            }
            return result;
        }
        if (value instanceof Iterable<?> iterable) {
            List<Object> result = new ArrayList<>();
            for (Object item : iterable) {
                result.add(toWireValue(item));
            }
            return result;
        }
        if (value.getClass().isArray()) {
            List<Object> result = new ArrayList<>();
            for (int i = 0; i < Array.getLength(value); i++) {
                result.add(toWireValue(Array.get(value, i)));
            }
            return result;
        }
        return String.valueOf(value);
    }

    public static Object fromWireValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            if (map.size() == 1 && map.containsKey(BINARY_MARKER)) {
                Object encoded = map.get(BINARY_MARKER);
                return Base64.getDecoder().decode(String.valueOf(encoded));
            }
            Map<String, Object> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                result.put(String.valueOf(entry.getKey()), fromWireValue(entry.getValue()));
            }
            return result;
        }
        if (value instanceof Iterable<?> iterable) {
            List<Object> result = new ArrayList<>();
            for (Object item : iterable) {
                result.add(fromWireValue(item));
            }
            return result;
        }
        return value;
    }
}
