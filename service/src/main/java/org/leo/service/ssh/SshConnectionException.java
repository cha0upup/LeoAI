package org.leo.service.ssh;

/** Sanitized application failure; transport/library messages and secrets stay inside the adapter. */
public final class SshConnectionException extends RuntimeException {
    public enum Kind {
        VALIDATION, NOT_FOUND, CONFLICT, FORBIDDEN, SESSION_EXPIRED,
        UNSUPPORTED_CAPABILITY, CAPACITY, UNAVAILABLE, PERSISTENCE
    }

    private final Kind kind;

    public SshConnectionException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public Kind getKind() {
        return kind;
    }
}
