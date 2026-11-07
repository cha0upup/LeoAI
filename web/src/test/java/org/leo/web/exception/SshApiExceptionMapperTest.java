package org.leo.web.exception;

import org.junit.jupiter.api.Test;
import org.leo.service.ssh.SshConnectionException.Kind;
import org.leo.service.ssh.SshConnectionException;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SshApiExceptionMapperTest {
    @Test void preservesHttpFailureSemanticsWithoutLeakingHttpTypesIntoApplicationServices() {
        Map<Kind, Integer> statuses = Map.of(Kind.VALIDATION, 400, Kind.NOT_FOUND, 404,
                Kind.CONFLICT, 409, Kind.FORBIDDEN, 403, Kind.SESSION_EXPIRED, 401,
                Kind.UNSUPPORTED_CAPABILITY, 400, Kind.CAPACITY, 429, Kind.UNAVAILABLE, 503, Kind.PERSISTENCE, 500);
        statuses.forEach((kind, code) -> {
            var failure = new SshConnectionException(kind, "fixture-message");
            ApiException mapped = SshApiExceptionMapper.toApiException(failure);
            assertEquals(code, mapped.getHttpStatus().value());
            assertEquals("fixture-message", mapped.getMessage());
            assertEquals(code, assertThrows(ApiException.class,
                    () -> SshApiExceptionMapper.call(() -> { throw failure; })).getCode());
        });
    }

    @Test void leavesSuccessfulValuesAndUnrelatedFailuresUntouched() {
        Object value = new Object();
        assertSame(value, SshApiExceptionMapper.call(() -> value));
        var failure = new IllegalStateException("fixture");
        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> SshApiExceptionMapper.call(() -> { throw failure; })));
    }
}
