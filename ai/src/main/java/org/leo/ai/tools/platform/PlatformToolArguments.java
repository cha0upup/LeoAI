package org.leo.ai.tools.platform;

/** Common text arguments for platform management tools. */
final class PlatformToolArguments {
    private PlatformToolArguments() { }

    static String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    static boolean isBlank(String value) {
        return trimToNull(value) == null;
    }

    static String requireNonBlank(String value, String message) {
        String trimmed = trimToNull(value);
        if (trimmed == null) throw new IllegalArgumentException(message);
        return trimmed;
    }

    static String defaultIfBlank(String value, String defaultValue) {
        String trimmed = trimToNull(value);
        return trimmed == null ? defaultValue : trimmed;
    }
}
