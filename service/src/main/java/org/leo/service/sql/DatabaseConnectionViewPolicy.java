package org.leo.service.sql;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** Public connection values and their edit round trip; independent of storage and encryption. */
final class DatabaseConnectionViewPolicy {
    private static final Pattern INLINE_SECRET = Pattern.compile(
            "(?i)(^|[?&;])((?:password|passwd|pwd|token|access_token|secret|api_key)=)([^&;]*)");
    private static final Pattern AUTHORITY_PASSWORD = Pattern.compile("(://[^:/?#]+:)[^@/?#]+(@)");
    private static final Pattern NON_KEY_CHARACTERS = Pattern.compile("[^A-Za-z0-9]");

    private DatabaseConnectionViewPolicy() { }

    static Map<String, Object> publicValues(Map<?, ?> source) {
        Map<String, Object> safe = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            String name = String.valueOf(key);
            if (!isSecretKey(name)) safe.put(name, sanitizeValue(value));
        });
        return safe;
    }

    /** Only omitted secret fields and exact, unchanged masked strings recover their stored values. */
    static Map<String, Object> restoreProtectedValues(Map<String, Object> stored, Map<String, Object> incoming) {
        Map<String, Object> merged = new LinkedHashMap<>(incoming);
        restoreInto(stored, merged);
        return merged;
    }

    @SuppressWarnings("unchecked")
    private static void restoreInto(Map<String, Object> stored, Map<String, Object> incoming) {
        stored.forEach((key, storedValue) -> {
            if (isSecretKey(key)) {
                if (!incoming.containsKey(key)) incoming.put(key, storedValue);
                return;
            }
            Object incomingValue = incoming.get(key);
            if (storedValue instanceof Map<?, ?> storedMap
                    && incomingValue instanceof Map<?, ?> incomingMap) {
                Map<String, Object> writable = new LinkedHashMap<>();
                incomingMap.forEach((nestedKey, value) -> writable.put(String.valueOf(nestedKey), value));
                restoreInto((Map<String, Object>) storedMap, writable);
                incoming.put(key, writable);
                return;
            }
            if (storedValue instanceof String storedText
                    && incomingValue instanceof String incomingText
                    && incomingText.contains("***")
                    && incomingText.equals(sanitizeValue(storedText))) {
                incoming.put(key, storedText);
            }
        });
    }

    private static Object sanitizeValue(Object value) {
        if (value instanceof Map<?, ?> map) return publicValues(map);
        if (value instanceof List<?> list) return list.stream().map(DatabaseConnectionViewPolicy::sanitizeValue).toList();
        if (value instanceof String text) {
            String sanitized = INLINE_SECRET.matcher(text).replaceAll("$1$2***");
            return AUTHORITY_PASSWORD.matcher(sanitized).replaceAll("$1***$2");
        }
        return value;
    }

    private static boolean isSecretKey(String key) {
        String normalized = key == null ? "" : NON_KEY_CHARACTERS.matcher(key)
                .replaceAll("").toLowerCase(Locale.ROOT);
        return normalized.contains("password") || normalized.contains("passwd") || normalized.equals("pwd")
                || normalized.contains("secret") || normalized.contains("token")
                || normalized.contains("credential") || normalized.contains("privatekey")
                || normalized.contains("accesskey") || normalized.contains("apikey");
    }
}
