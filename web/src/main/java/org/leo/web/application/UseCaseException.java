package org.leo.web.application;

import java.util.Objects;

/** 应用用例可向调用方展示的失败；传输状态由入口适配器决定。 */
public final class UseCaseException extends RuntimeException {
    public enum Kind { INVALID_INPUT, NOT_FOUND, OPERATION_FAILED }

    private final Kind kind;

    public UseCaseException(Kind kind, String message) {
        super(message);
        this.kind = Objects.requireNonNull(kind, "kind");
    }

    public Kind getKind() {
        return kind;
    }
}
