package org.leo.web.config;

import org.junit.jupiter.api.Test;
import org.leo.web.exception.ApiException;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class GlobalExceptionHandlerDatabaseTest {

    @Test
    void returnsAStableInternalErrorWithoutExposingExceptionDetails() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        ResponseEntity<?> response = handler.handleException(
                new IllegalStateException("jdbc:sqlite:/private/data.db secret-token"));
        Map<?, ?> body = (Map<?, ?>) response.getBody();

        assertEquals(500, response.getStatusCode().value());
        assertEquals(500, body.get("code"));
        assertEquals("服务器内部错误，请稍后重试", body.get("msg"));
        assertFalse(body.toString().contains("secret-token"));
        assertFalse(body.toString().contains("/private/data.db"));
        assertNotNull(UUID.fromString((String) body.get("traceId")));

        Map<?, ?> nextBody = handler.handleException(new RuntimeException()).getBody();
        assertNotEquals(body.get("traceId"), nextBody.get("traceId"));
    }

    @Test
    void includesDatabaseErrorDetailsInTheUnifiedApiResponse() {
        ApiException error = ApiException.databaseError(
                503,
                "connection interrupted",
                Map.of("errorCategory", "CONNECTION_ERROR", "retryable", true, "sqlState", "08006"));

        ResponseEntity<?> response = new GlobalExceptionHandler().handleApiException(error);
        Map<?, ?> body = (Map<?, ?>) response.getBody();

        assertEquals(503, response.getStatusCode().value());
        assertEquals(503, body.get("code"));
        assertEquals("CONNECTION_ERROR", body.get("errorCategory"));
        assertEquals(true, body.get("retryable"));
        assertEquals("08006", body.get("sqlState"));
    }

    @Test
    void keepsDatabaseAuthenticationSeparateFromApplicationLoginState() {
        ApiException error = ApiException.databaseError(
                401,
                "database credentials rejected",
                Map.of("databaseCode", 401, "errorCategory", "AUTHENTICATION_ERROR"));

        ResponseEntity<?> response = new GlobalExceptionHandler().handleApiException(error);
        Map<?, ?> body = (Map<?, ?>) response.getBody();

        assertEquals(422, response.getStatusCode().value());
        assertEquals(422, body.get("code"));
        assertEquals(401, body.get("databaseCode"));
    }
}
