package org.leo.core.fingerprint;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Keeps fingerprint files focused on identification and rule metadata. */
public final class FingerprintMetadata {

    private static final List<String> DEFINITION_FIELDS = List.of(
            "fingerprintId", "id", "name", "protocol", "tags", "rule");
    private static final List<String> INFO_FIELDS = List.of("version", "author", "description", "remark");

    private FingerprintMetadata() { }

    public static Map<String, Object> normalize(Map<?, ?> definition) {
        Map<String, Object> result = select(definition, DEFINITION_FIELDS);
        if (definition.containsKey("info")) result.put("info", normalizeInfo(definition.get("info")));
        return result;
    }

    public static Map<String, Object> normalizeInfo(Object value) {
        if (value == null) return new LinkedHashMap<>();
        if (!(value instanceof Map<?, ?> info)) throw new IllegalArgumentException("info 必须是 JSON 对象");
        return select(info, INFO_FIELDS);
    }

    private static Map<String, Object> select(Map<?, ?> source, List<String> fields) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String field : fields) {
            if (source.containsKey(field)) result.put(field, source.get(field));
        }
        return result;
    }
}
