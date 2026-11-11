package org.leo.service.importing;

/** Import conflicts without rename support; only explicit overwrite enables replacement. */
public enum ImportOverwritePolicy {
    SKIP, OVERWRITE;

    public static ImportOverwritePolicy parse(String value) {
        if (value == null) return SKIP;
        return switch (value.toLowerCase()) {
            case "overwrite" -> OVERWRITE;
            default -> SKIP;
        };
    }
}
