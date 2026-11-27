package org.leo.web.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.leo.core.agent.AbstractAgentNode;
import org.leo.core.agent.capability.SqlCapable;
import org.leo.core.entity.Agent;
import org.leo.core.infrastructure.persistence.session.AtomicFileStore;
import org.leo.service.sql.AgentDatabaseConnectionService;
import org.leo.service.sql.AgentNodeSqlService;
import org.leo.service.sql.DatabaseConnectionProfileException;
import org.leo.service.sql.SqlExecutionException;
import org.leo.service.sql.SqlExportService;
import org.leo.service.ssh.SshConnectionException;
import org.leo.service.team.TeamOperationException;
import org.leo.web.application.UseCaseException;
import org.leo.web.controller.agentnode.sql.AgentNodeSqlController;
import org.leo.web.exception.ApiException;
import org.leo.web.security.DatabaseConnectionResolver;
import org.leo.web.util.AuditLogUtil;
import org.leo.web.util.ControllerUtil;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @ParameterizedTest
    @MethodSource("businessFailures")
    void mapsOriginalBusinessFailuresAtTheHttpBoundary(RuntimeException error, int code) throws Exception {
        MockMvcBuilders.standaloneSetup(new BusinessFailureController(error))
                .setControllerAdvice(handler).build()
                .perform(get("/business-failure-fixture"))
                .andExpect(status().is(code))
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.msg").value(error.getMessage()))
                .andExpect(result -> assertSame(error, result.getResolvedException()));
    }

    private static Stream<Arguments> businessFailures() {
        var useCases = Map.of(UseCaseException.Kind.INVALID_INPUT, 400, UseCaseException.Kind.FORBIDDEN, 403,
                UseCaseException.Kind.NOT_FOUND, 404, UseCaseException.Kind.OPERATION_FAILED, 500);
        var ssh = Map.of(SshConnectionException.Kind.VALIDATION, 400,
                SshConnectionException.Kind.NOT_FOUND, 404, SshConnectionException.Kind.CONFLICT, 409,
                SshConnectionException.Kind.FORBIDDEN, 403, SshConnectionException.Kind.SESSION_EXPIRED, 401,
                SshConnectionException.Kind.UNSUPPORTED_CAPABILITY, 400, SshConnectionException.Kind.CAPACITY, 429,
                SshConnectionException.Kind.UNAVAILABLE, 503, SshConnectionException.Kind.PERSISTENCE, 500);
        var profiles = Map.of(DatabaseConnectionProfileException.Kind.VALIDATION, 400,
                DatabaseConnectionProfileException.Kind.NOT_FOUND, 404,
                DatabaseConnectionProfileException.Kind.FORBIDDEN, 403,
                DatabaseConnectionProfileException.Kind.PERSISTENCE, 500);
        var teams = Map.of(TeamOperationException.Kind.VALIDATION, 400,
                TeamOperationException.Kind.NOT_FOUND, 404,
                TeamOperationException.Kind.FORBIDDEN, 403,
                TeamOperationException.Kind.PERSISTENCE, 500);
        return Stream.of(
                useCases.entrySet().stream().map(entry -> Arguments.of(
                        new UseCaseException(entry.getKey(), "用例失败"), entry.getValue())),
                ssh.entrySet().stream().map(entry -> Arguments.of(
                        new SshConnectionException(entry.getKey(), "SSH 业务失败"), entry.getValue())),
                profiles.entrySet().stream().map(entry -> Arguments.of(
                        new DatabaseConnectionProfileException(entry.getKey(), "配置操作失败"), entry.getValue())),
                teams.entrySet().stream().map(entry -> Arguments.of(
                        new TeamOperationException(entry.getKey(), "团队操作失败"), entry.getValue())))
                .flatMap(stream -> stream);
    }

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
    void keepsEnvelopeStatusAndMessageAuthoritativeWhilePreservingConflictDetails() throws Exception {
        ApiException error = ApiException.conflict("请确认主机指纹",
                Map.of("code", 200, "msg", "overridden", "errorCode", "SSH_HOST_KEY_CONFIRMATION_REQUIRED",
                        "fingerprint", "pin", "previousFingerprint", ""));
        MockMvcBuilders.standaloneSetup(new BusinessFailureController(error))
                .setControllerAdvice(handler).build()
                .perform(get("/business-failure-fixture"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409))
                .andExpect(jsonPath("$.msg").value("请确认主机指纹"))
                .andExpect(jsonPath("$.errorCode").value("SSH_HOST_KEY_CONFIRMATION_REQUIRED"))
                .andExpect(jsonPath("$.fingerprint").value("pin"))
                .andExpect(jsonPath("$.previousFingerprint").value(""));
    }

    @ParameterizedTest
    @CsvSource({"400,400", "429,429", "500,500", "502,502", "503,503", "504,504",
            "200,422", "401,422", "403,422", "404,422", "799,422"})
    void normalizesDatabaseCodesWithoutLosingStructuredDetailsOrChangingLoginState(int databaseCode, int httpCode) {
        var error = new SqlExecutionException(databaseCode, "database error", "SQL_ERROR", "40001", true, 1213);
        assertEquals(Map.of("code", httpCode, "msg", "database error", "databaseCode", databaseCode,
                        "errorCategory", "SQL_ERROR", "sqlState", "40001", "retryable", true, "vendorCode", 1213),
                errorBody(handler.handleSqlExecutionException(error), httpCode));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void sqlEndpointsPreserveTheOriginalFailureAndAuditOnlyAuditedOperations(boolean audited) throws Exception {
        AgentNodeSqlService sql = mock(AgentNodeSqlService.class);
        DatabaseConnectionResolver resolver = mock(DatabaseConnectionResolver.class);
        var controller = new AgentNodeSqlController(sql, mock(SqlExportService.class),
                mock(AgentDatabaseConnectionService.class), resolver);
        var node = mock(AbstractAgentNode.class, withSettings().extraInterfaces(SqlCapable.class));
        Agent agent = new Agent();
        agent.setAgentId("agent-1");
        when(node.getAgent()).thenReturn(agent);
        Map<String, Object> connection = Map.of("dialect", "mysql", "host", "db.internal",
                "database", "inventory", "username", "app", "password", "private-password");
        when(resolver.resolve(connection, "agent-1", null)).thenReturn(connection);
        var failure = new SqlExecutionException(503, "connection interrupted", "CONNECTION_ERROR", "08006", true, null);
        when(sql.getDatabases((SqlCapable) node, connection)).thenThrow(failure);
        when(sql.executeSql((SqlCapable) node, connection, "select 1", null)).thenThrow(failure);
        Map<String, Object> payload = new HashMap<>(Map.of("sessionId", "session-1", "connection", connection));
        if (audited) payload.put("sql", "select 1");

        try (var context = mockStatic(ControllerUtil.class); var audit = mockStatic(AuditLogUtil.class)) {
            context.when(() -> ControllerUtil.requireCapability("session-1", SqlCapable.class)).thenReturn((SqlCapable) node);
            context.when(() -> ControllerUtil.getAbstractAgentNode("session-1")).thenReturn(node);
            MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(handler).build()
                    .perform(post(audited ? "/agent-node/sql/query/execute" : "/agent-node/sql/metadata/databases")
                            .contentType(MediaType.APPLICATION_JSON).content(new ObjectMapper().writeValueAsBytes(payload)))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.databaseCode").value(503))
                    .andExpect(jsonPath("$.retryable").value(true))
                    .andExpect(result -> assertSame(failure, result.getResolvedException()));

            if (audited) {
                audit.verify(() -> AuditLogUtil.logFailure(eq(node), eq("SQL_EXEC"), eq("执行SQL"), nullable(String.class),
                        argThat(params -> !params.toString().contains("private-password")), eq(failure.getMessage()), isNull()));
            } else {
                audit.verifyNoInteractions();
            }
        }
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
    static class BusinessFailureController {
        private final Exception error;
        BusinessFailureController(Exception error) { this.error = error; }
        @GetMapping("/business-failure-fixture")
        public Object fail() throws Exception {
            throw error;
        }
    }

    @RestController
    static class FixtureController {
        private final Path path;
        FixtureController(Path path) { this.path = path; }
        @GetMapping("/storage-fixture")
        public Object read() { return new AtomicFileStore().readJsonMap(path.toFile()); }
    }
}
