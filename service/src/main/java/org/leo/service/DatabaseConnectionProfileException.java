package org.leo.service;

/** Typed failure raised while managing a Agent-owned database profile. */
public final class DatabaseConnectionProfileException extends RuntimeException {

    public enum Kind {
        VALIDATION,
        NOT_FOUND,
        FORBIDDEN,
        PERSISTENCE
    }

    private final Kind kind;

    public DatabaseConnectionProfileException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public Kind getKind() {
        return kind;
    }
}
