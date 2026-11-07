package org.leo.web.exception;

import org.leo.service.ssh.SshConnectionException;

import java.util.function.Supplier;

/** The HTTP boundary alone translates SSH application failures into HTTP responses. */
public final class SshApiExceptionMapper {
    private SshApiExceptionMapper() { }

    public static <T> T call(Supplier<T> action) {
        try { return action.get(); }
        catch (SshConnectionException error) { throw toApiException(error); }
    }

    public static ApiException toApiException(SshConnectionException error) {
        return switch (error.getKind()) {
            case VALIDATION, UNSUPPORTED_CAPABILITY -> ApiException.badRequest(error.getMessage());
            case NOT_FOUND -> ApiException.notFound(error.getMessage());
            case CONFLICT -> ApiException.conflict(error.getMessage());
            case FORBIDDEN -> ApiException.forbidden(error.getMessage());
            case SESSION_EXPIRED -> ApiException.unauthorized(error.getMessage());
            case CAPACITY -> ApiException.tooManyRequests(error.getMessage());
            case UNAVAILABLE -> ApiException.serviceUnavailable(error.getMessage());
            case PERSISTENCE -> ApiException.serverError(error.getMessage());
        };
    }
}
