package org.leo.service.audit;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Common request-parameter redaction for web and AI audit records; never mutates the input. */
public final class AuditParameterSanitizer {

    private static final Set<String> SENSITIVE_FIELDS = Set.of(
            "password", "pwd", "passwd", "secret", "token", "key", "credential",
            "content", "data", "filedata", "base64", "access_token", "api_key");
    private static final Pattern INLINE_SECRET = Pattern.compile(
            "(?i)(^|[?&;])((?:password|passwd|pwd|token|access_token|secret|api_key)=)([^&;\\s]*)");
    private static final Pattern AUTHORITY_PASSWORD =
            Pattern.compile("(://[^:/?#\\s]+:)[^@/?#\\s]+(@)");
    private static final int MAX_DEPTH = 32;

    private AuditParameterSanitizer() {
    }

    public static Map<String, Object> sanitize(Map<String, Object> params) {
        Map<String, Object> sanitized = new LinkedHashMap<>();
        if (params != null) {
            Set<Object> ancestors = Collections.newSetFromMap(new IdentityHashMap<>());
            ancestors.add(params);
            params.forEach((key, value) -> sanitized.put(key, sanitizeValue(key, value, 0, ancestors)));
        }
        return sanitized;
    }

    private static Object sanitizeValue(String fieldName, Object value, int depth, Set<Object> ancestors) {
        if (value == null) return null;
        if (fieldName != null && SENSITIVE_FIELDS.contains(fieldName.trim().toLowerCase(Locale.ROOT))) {
            return "***";
        }
        if (value instanceof String text) {
            String sanitized = INLINE_SECRET.matcher(text).replaceAll("$1$2***");
            return AUTHORITY_PASSWORD.matcher(sanitized).replaceAll("$1***$2");
        }
        if (!(value instanceof Map<?, ?>) && !(value instanceof List<?>)) return value;
        // Bound deep or cyclic in-process parameters without truncating repeated sibling values.
        if (depth >= MAX_DEPTH || !ancestors.add(value)) return "[truncated]";
        try {
            if (value instanceof Map<?, ?> map) {
                Map<String, Object> nested = new LinkedHashMap<>();
                map.forEach((key, item) -> {
                    String name = key == null ? "" : key.toString();
                    nested.put(name, sanitizeValue(name, item, depth + 1, ancestors));
                });
                return nested;
            }
            return ((List<?>) value).stream()
                    .map(item -> sanitizeValue(fieldName, item, depth + 1, ancestors)).toList();
        } finally {
            ancestors.remove(value);
        }
    }
}
