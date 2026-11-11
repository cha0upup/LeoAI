package org.leo.service.importing;

/** Import conflicts where an independent renamed copy is supported. */
public enum ImportConflictPolicy {
    SKIP, OVERWRITE, RENAME;

    public static ImportConflictPolicy parse(String value) {
        if (value == null) return SKIP;
        return switch (value.toLowerCase()) {
            case "overwrite" -> OVERWRITE;
            case "rename" -> RENAME;
            default -> SKIP;
        };
    }
}
