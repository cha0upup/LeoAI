package org.leo.service.discovery;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Mutable snapshots of scan payload maps and collections, preserving order and set semantics. */
public final class NetworkProbePayloads {

    private NetworkProbePayloads() {}

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
