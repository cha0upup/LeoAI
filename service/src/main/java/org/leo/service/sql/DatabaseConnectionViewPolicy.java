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

    /** Omitted fields survive patches; only exact, unchanged public masks restore stored strings. */
    static Map<String, Object> mergeForEdit(Map<?, ?> stored, Map<?, ?> incoming) {
        return mergeForEdit(stored, incoming, true);
    }

    private static Map<String, Object> mergeForEdit(Map<?, ?> stored, Map<?, ?> incoming,
                                                   boolean restoreMasks) {
        Map<String, Object> merged = new LinkedHashMap<>();
        stored.forEach((key, value) -> merged.put(String.valueOf(key), value));
        incoming.forEach((key, incomingValue) -> {
            String name = String.valueOf(key);
            Object storedValue = merged.get(name);
            // Secret containers are not public masked views; their explicit edits bypass mask restoration.
            boolean restoreMask = restoreMasks && !isSecretKey(name);
            if (storedValue instanceof Map<?, ?> storedMap
                    && incomingValue instanceof Map<?, ?> incomingMap) {
                merged.put(name, mergeForEdit(storedMap, incomingMap, restoreMask));
            } else if (restoreMask && storedValue instanceof String storedText
                    && incomingValue instanceof String incomingText
                    && incomingText.contains("***")
                    && incomingText.equals(sanitizeValue(storedText))) {
                merged.put(name, storedText);
            } else {
                merged.put(name, incomingValue);
            }
        });
        return merged;
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
