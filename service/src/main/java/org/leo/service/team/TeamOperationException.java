package org.leo.service.team;

/** Team management failures; callers decide how to present them. */
public final class TeamOperationException extends RuntimeException {
    public enum Kind { VALIDATION, NOT_FOUND, FORBIDDEN, PERSISTENCE }

    private final Kind kind;

    public TeamOperationException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public Kind getKind() {
        return kind;
    }
}
