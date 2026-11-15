package org.leo.ai.agent;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.exception.ToolExecutionException;
import dev.langchain4j.invocation.InvocationContext;
import dev.langchain4j.service.tool.AiServiceTool;
import dev.langchain4j.service.tool.ToolExecutionResult;
import dev.langchain4j.service.tool.ToolService;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiToolErrorHandlerTest {

    private final AiToolErrorHandler handler =
            new AiToolErrorHandler();
    private final InvocationContext context =
            InvocationContext.builder()
                    .chatMemoryId("session-1:thread-1")
                    .build();
    private final Map<String, AiServiceTool> tools =
            ToolService.findTools(new TestTools()).stream()
                    .collect(Collectors.toMap(
                            AiServiceTool::name,
                            Function.identity()));

    @Test
    void cyclicUnknownFailureStillPropagatesToTheTurnBoundary() {
        Throwable first = new RuntimeException("outer failure");
        Throwable second = new IllegalStateException("inner failure");
        first.initCause(second);
        second.initCause(first);

        ToolExecutionException propagated = assertTimeoutPreemptively(Duration.ofSeconds(2),
                () -> assertThrows(ToolExecutionException.class, () -> handler.handleExecution(first, null)));

        assertSame(first, propagated.getCause());
    }

    @Test
    void returnsArgumentBindingErrorToModelAndAllowsCorrectedRetry() {
        try (AiToolErrorHandler.TurnScope ignored =
                     handler.beginTurn(context.chatMemoryId())) {
            ToolExecutionResult invalid = execute(
                    "booleanTool",
                    """
                    {"cache":"true"}
                    """);

            assertTrue(invalid.isError());
            assertTrue(invalid.resultText().contains(
                    "\"protocol\":\"leo.tool.error.v1\""));
            assertTrue(invalid.resultText().contains(
                    "\"code\":\"INVALID_TOOL_ARGUMENTS\""));
            assertTrue(invalid.resultText().contains(
                    "\"retryable\":true"));

            ToolExecutionResult corrected = execute(
                    "booleanTool",
                    """
                    {"cache":true}
                    """);

            assertFalse(corrected.isError());
            assertEquals("cache=true", corrected.resultText());
        }
    }

    @Test
    void returnsToolValidationErrorToModel() {
        try (AiToolErrorHandler.TurnScope ignored =
                     handler.beginTurn(context.chatMemoryId())) {
            ToolExecutionResult result = execute(
                    "validateAction",
                    """
                    {"action":"destroy"}
                    """);

            assertTrue(result.isError());
            assertTrue(result.resultText().contains(
                    "\"code\":\"INVALID_TOOL_INPUT\""));
            assertTrue(result.resultText().contains(
                    "可选值为 get、set"));
        }
    }

    @Test
    void preservesCredentialDetailsInModelCorrectableError() {
        try (AiToolErrorHandler.TurnScope ignored =
                     handler.beginTurn(context.chatMemoryId())) {
            ToolExecutionResult result = execute(
                    "invalidCredential", "{}");

            assertTrue(result.isError());
            assertTrue(result.resultText().contains(
                    "password=source-secret"));
        }
    }

    @Test
    void honorsTypedRecoveryMetadata() {
        try (AiToolErrorHandler.TurnScope ignored =
                     handler.beginTurn(context.chatMemoryId())) {
            ToolExecutionResult result = execute(
                    "missingResource", "{}");

            assertTrue(result.isError());
            assertTrue(result.resultText().contains(
                    "\"code\":\"RESOURCE_NOT_FOUND\""));
            assertTrue(result.resultText().contains(
                    "请先调用 listResources"));
        }
    }

    @Test
    void stopsIdenticalCorrectionLoopAndResetsOnNextTurn() {
        String arguments = """
                {"cache":"true"}
                """;
        try (AiToolErrorHandler.TurnScope ignored =
                     handler.beginTurn(context.chatMemoryId())) {
            assertTrue(execute(
                    "booleanTool", arguments).resultText()
                    .contains("\"attempt\":1"));
            assertTrue(execute(
                    "booleanTool", arguments).resultText()
                    .contains("\"attempt\":2"));
            ToolExecutionResult lastAdvice =
                    execute("booleanTool", arguments);
            assertTrue(lastAdvice.resultText()
                    .contains("\"attempt\":3"));
            assertTrue(lastAdvice.resultText()
                    .contains("\"retryable\":false"));
            assertThrows(ToolExecutionException.class,
                    () -> execute("booleanTool", arguments));
        }

        try (AiToolErrorHandler.TurnScope ignored =
                     handler.beginTurn(context.chatMemoryId())) {
            assertTrue(execute(
                    "booleanTool", arguments).resultText()
                    .contains("\"attempt\":1"));
        }
    }

    @Test
    void returnsStablePermissionErrorAndDoesNotRetryPermissionFailure() {
        try (AiToolErrorHandler.TurnScope ignored =
                     handler.beginTurn(context.chatMemoryId())) {
            ToolExecutionResult result =
                    execute("denied", "{}");

            assertTrue(result.isError());
            assertTrue(result.resultText().contains(
                    "\"code\":\"TOOL_PERMISSION_DENIED\""));
            assertTrue(result.resultText().contains(
                    "\"recoverableBy\":\"USER\""));
            assertTrue(result.resultText().contains(
                    "\"retryable\":false"));
            assertFalse(result.resultText().contains(
                    "Bearer private-token"));

            assertTrue(execute("denied", "{}")
                    .resultText().contains("\"attempt\":2"));
            assertThrows(ToolExecutionException.class,
                    () -> execute("denied", "{}"));
        }
    }

    @Test
    void propagatesInfrastructureFailureToTurnBoundary() {
        try (AiToolErrorHandler.TurnScope ignored =
                     handler.beginTurn(context.chatMemoryId())) {
            assertThrows(ToolExecutionException.class,
                    () -> execute("internalFailure", "{}"));
        }
    }

    @Test
    void returnsUnknownToolAsModelCorrectableError() {
        ToolExecutionRequest request = request(
                "imaginedTool", "{}");

        String result =
                handler.handleUnknownTool(request).text();

        assertTrue(result.contains(
                "\"code\":\"UNKNOWN_TOOL\""));
        assertTrue(result.contains(
                "\"recoverableBy\":\"MODEL\""));
        assertTrue(result.contains(
                "\"retryable\":true"));
    }

    private ToolExecutionResult execute(
            String toolName,
            String arguments) {
        AiServiceTool tool = tools.get(toolName);
        return ToolService.executeWithErrorHandling(
                request(toolName, arguments),
                tool.toolExecutor(),
                context,
                handler::handleArguments,
                handler::handleExecution);
    }

    private ToolExecutionRequest request(
            String toolName,
            String arguments) {
        return ToolExecutionRequest.builder()
                .id("call-" + toolName)
                .name(toolName)
                .arguments(arguments)
                .build();
    }

    private static final class TestTools {

        @Tool
        public String booleanTool(
                @P(name = "cache", description = "是否缓存")
                boolean cache) {
            return "cache=" + cache;
        }

        @Tool
        public String validateAction(
                @P(name = "action", description = "操作")
                String action) {
            throw new IllegalArgumentException(
                    "action 无效，可选值为 get、set");
        }

        @Tool
        public String invalidCredential() {
            throw new IllegalArgumentException(
                    "password=source-secret");
        }

        @Tool
        public String missingResource() {
            throw AiToolException.modelCorrectable(
                    "RESOURCE_NOT_FOUND",
                    "资源不存在。",
                    "请先调用 listResources 获取有效资源 ID。");
        }

        @Tool
        public String denied() {
            throw new SecurityException(
                    "Authorization=Bearer private-token");
        }

        @Tool
        public String internalFailure() {
            throw new IllegalStateException(
                    "database unavailable");
        }
    }
}
