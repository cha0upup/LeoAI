package org.leo.ai.runtime;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.model.output.TokenUsage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiTurnTraceTest {

    @Test
    void bindsPersistenceIdentityAndBuildsOrderedPhaseDurations() {
        AiTurnTrace trace = AiTurnTrace.testTrace(
                "trace-1", "platform", "thread-1",
                System.currentTimeMillis());

        trace.checkpoint(AiTurnTrace.Checkpoint.TRANSPORT_READY);
        trace.checkpoint(AiTurnTrace.Checkpoint.AGENT_RESOLVED);
        trace.bind("turn-1", "run-1");
        trace.checkpoint(AiTurnTrace.Checkpoint.MODEL_STARTED);
        trace.checkpoint(AiTurnTrace.Checkpoint.FIRST_EVENT);
        trace.finish(AiTurnOutcome.COMPLETED, null, null);

        Map<String, Object> snapshot = trace.snapshot();
        assertEquals("trace-1", snapshot.get("traceId"));
        assertEquals("turn-1", snapshot.get("turnId"));
        assertEquals("run-1", snapshot.get("runId"));
        assertEquals("completed", snapshot.get("outcome"));
        assertNotNull(snapshot.get("phaseDurations"));
        assertTrue(((Map<?, ?>) snapshot.get("checkpoints"))
                .containsKey("first_event"));
        assertFalse(trace.toJson().isBlank());
    }

    @ParameterizedTest
    @CsvSource(value = {"en-US,FAILED,failed", "tr-TR,COMPLETED,completed", "tr-TR,FAILED,failed",
            "tr-TR,CANCELLED,cancelled", "tr-TR,null,failed"}, nullValues = "null")
    @ResourceLock("java.util.Locale.default")
    void telemetryRecordsStableOutcomesExactlyOnce(String languageTag, AiTurnOutcome outcome, String status) {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag(languageTag));
            AiTurnTrace trace = AiTurnTrace.testTrace(
                    "trace-1", "agent", "thread-1", System.currentTimeMillis());
            trace.finish(outcome, "tool_calling", "tool calls unsupported");
            AiTurnTelemetryRegistry registry = new AiTurnTelemetryRegistry();

            registry.record(trace);
            registry.record(trace);

            assertEquals(status, trace.outcome());
            assertEquals(status, trace.snapshot().get("outcome"));
            if (outcome != null) {
                assertEquals(status, new AiTurnOrchestrator.TerminalResult(outcome, null).runtimeStatus());
            }
            Map<String, Object> snapshot = registry.snapshot();
            assertEquals(1L, snapshot.get("completedTurns"));
            assertEquals(Map.of(status, 1L), snapshot.get("outcomes"));
            assertEquals(Map.of("tool_calling", 1L), snapshot.get("errorCategories"));
        } finally {
            Locale.setDefault(previous);
        }
    }

    @Test
    void persistsModelUsageAndAggregatedToolProtectionMetrics() {
        AiTurnTrace trace = AiTurnTrace.testTrace(
                "trace-2", "platform", "thread-2",
                System.currentTimeMillis());
        trace.recordModelResponse(ChatResponse.builder()
                .id("response-2")
                .aiMessage(AiMessage.from("done"))
                .modelName("test-model")
                .tokenUsage(new TokenUsage(120, 30, 150))
                .finishReason(FinishReason.STOP)
                .build());
        trace.recordEvent(AiTurnEvent.toolCompleted(Map.of(
                "toolName", "getSlow",
                "success", false,
                "durationMs", 50L,
                "code", "TOOL_TIMEOUT",
                "retryable", true,
                "attempt", 2,
                "truncated", false,
                "deduplicated", false)));
        trace.recordEvent(AiTurnEvent.toolCompleted(Map.of(
                "toolName", "createRecord",
                "success", true,
                "durationMs", 10L,
                "code", "OK",
                "retryable", false,
                "attempt", 0,
                "truncated", true,
                "deduplicated", true)));

        Map<String, Object> snapshot = trace.snapshot();
        Map<?, ?> usage = (Map<?, ?>) snapshot.get("modelUsage");
        Map<?, ?> metrics = (Map<?, ?>) snapshot.get("toolMetrics");

        assertEquals(Map.of("responseId", "response-2", "model", "test-model",
                "finishReason", "stop", "inputTokens", 120,
                "outputTokens", 30, "totalTokens", 150), usage);
        assertEquals(2, metrics.get("count"));
        assertEquals(60L, metrics.get("totalDurationMs"));
        assertEquals(1, metrics.get("timeoutCount"));
        assertEquals(1, metrics.get("truncatedCount"));
        assertEquals(1, metrics.get("deduplicatedCount"));
        assertEquals(1, metrics.get("retryableFailureCount"));
        assertEquals(2, metrics.get("maxAttempt"));

        List<?> executions = (List<?>) metrics.get("executions");
        ((Map<?, ?>) executions.get(0)).clear();
        executions.clear();
        Map<?, ?> nextMetrics = (Map<?, ?>) trace.snapshot().get("toolMetrics");
        assertEquals(2, ((List<?>) nextMetrics.get("executions")).size());
        assertEquals(60L, nextMetrics.get("totalDurationMs"));
    }

    @Test
    void telemetryIncludesCompressionRuntimeEvents() {
        AiTurnTelemetryRegistry registry = new AiTurnTelemetryRegistry();
        registry.recordRuntimeEvent("model.retry");
        registry.recordRuntimeEvent("compression.checkpoint_restored");
        registry.recordRuntimeEvent("compression.checkpoint_restored");
        registry.recordRuntimeEvent("compression.completed");

        Map<?, ?> events = (Map<?, ?>) registry.snapshot().get("runtimeEvents");
        assertEquals(Map.of("compression.checkpoint_restored", 2L, "compression.completed", 1L, "model.retry", 1L), events);
        assertEquals(List.of("compression.checkpoint_restored", "compression.completed", "model.retry"),
                List.copyOf(events.keySet()));
    }
}
