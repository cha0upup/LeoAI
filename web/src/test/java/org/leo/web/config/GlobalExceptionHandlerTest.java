package org.leo.web.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.leo.core.infrastructure.persistence.session.AtomicFileStore;
import org.leo.web.exception.ApiException;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void returnsAStableInternalErrorWithoutExposingExceptionDetails() {
        Map<String, Object> body = errorBody(handler.handleException(
                new IllegalStateException("jdbc:sqlite:/private/data.db secret-token")), 500);

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

        Map<String, Object> body = errorBody(handler.handleApiException(error), 503);

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

        Map<String, Object> body = errorBody(handler.handleApiException(error), 422);

        assertEquals(401, body.get("databaseCode"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void storageFailuresReturnAnExplicitErrorWithoutExposingPathsOrContents(
            boolean corrupt, @TempDir Path temp) throws Exception {
        Path target = temp.resolve("fixture.json");
        if (corrupt) Files.writeString(target, "{private-content");
        else Files.createDirectory(target);
        var mvc = MockMvcBuilders.standaloneSetup(new FixtureController(target))
                .setControllerAdvice(handler).build();
        var response = mvc.perform(get("/storage-fixture")).andReturn().getResponse();
        assertEquals(500, response.getStatus());
        String body = response.getContentAsString(StandardCharsets.UTF_8);
        assertTrue(body.contains(corrupt ? "INVALID_CONTENT" : "READ_FAILED"), body);
        assertTrue(body.contains("traceId"));
        assertTrue(body.contains("fixture.json"));
        assertFalse(body.contains(temp.toString()));
        assertFalse(body.contains("private-content"));
    }

    private static Map<String, Object> errorBody(ResponseEntity<? extends Map<String, Object>> response, int code) {
        assertEquals(code, response.getStatusCode().value());
        Map<String, Object> body = response.getBody();
        assertNotNull(body);
        assertEquals(code, body.get("code"));
        return body;
    }

    @RestController
    static class FixtureController {
        private final Path path;
        FixtureController(Path path) { this.path = path; }
        @GetMapping("/storage-fixture")
        public Object read() { return new AtomicFileStore().readJsonMap(path.toFile()); }
    }
}
