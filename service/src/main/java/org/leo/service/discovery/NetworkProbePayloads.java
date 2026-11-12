package org.leo.service.discovery;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Scan payload conversion and mutable snapshots, preserving order and set semantics. */
public final class NetworkProbePayloads {

    private NetworkProbePayloads() {}

    public static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    /** Accepts numeric values or trimmed decimal strings; malformed values retain the fallback. */
    static int integer(Object value, int fallback) {
        if (value instanceof Number number) return number.intValue();
        try {
            return value == null ? fallback : Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    /** Plan/evaluator integers deliberately retain the non-trimming parsing contract. */
    static int boundedInt(Object value, int fallback, int min, int max) {
        int result = fallback;
        try {
            if (value instanceof Number number) result = number.intValue();
            else if (value != null && !String.valueOf(value).isBlank()) result = Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            result = fallback;
        }
        return Math.max(min, Math.min(max, result));
    }

    static String defaultText(Object value, String fallback) {
        String result = text(value);
        return result.isEmpty() ? fallback : result;
    }

    static String endpointKey(Map<String, Object> endpoint) {
        return text(endpoint.get("host")) + ":" + integer(endpoint.get("port"), -1);
    }

    static List<Map<String, Object>> mapRows(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : list) if (item instanceof Map<?, ?> raw) result.add(copyMapOrEmpty(raw));
        return result;
    }

    public static Map<String, Object> copyMapOrEmpty(Object value) {
        return value instanceof Map<?, ?> source ? copyMap(source) : new LinkedHashMap<>();
    }

    /** Normalizes non-null keys to strings, as required by the scan wire protocol. */
    public static Map<String, Object> copyMap(Map<?, ?> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            if (entry.getKey() != null) result.put(String.valueOf(entry.getKey()), copyValue(entry.getValue()));
        }
        return result;
    }

    /** Copies nested containers; scalars and other values retain their existing representation. */
    public static Object copyValue(Object value) {
        if (value instanceof Map<?, ?> source) return copyMap(source);
        if (value instanceof Set<?> source) {
            Set<Object> result = new LinkedHashSet<>();
            for (Object item : source) result.add(copyValue(item));
            return result;
        }
        if (value instanceof Collection<?> source) {
            List<Object> result = new ArrayList<>();
            for (Object item : source) result.add(copyValue(item));
            return result;
        }
        return value;
    }
}
