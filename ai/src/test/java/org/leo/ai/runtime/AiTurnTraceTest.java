package org.leo.ai.runtime;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.model.output.TokenUsage;
import org.junit.jupiter.api.Test;

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

    @Test
    void telemetryRecordsATraceExactlyOnce() {
        AiTurnTrace trace = AiTurnTrace.testTrace(
                "trace-1", "puppet", "thread-1",
                System.currentTimeMillis());
        trace.finish(
                AiTurnOutcome.FAILED,
                "tool_calling",
                "tool calls unsupported");
        AiTurnTelemetryRegistry registry = new AiTurnTelemetryRegistry();

        registry.record(trace);
        registry.record(trace);

        Map<String, Object> snapshot = registry.snapshot();
        assertEquals(1L, snapshot.get("completedTurns"));
        assertEquals(1L,
                ((Map<?, ?>) snapshot.get("outcomes")).get("failed"));
        assertEquals(1L,
                ((Map<?, ?>) snapshot.get("errorCategories"))
                        .get("tool_calling"));
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
    }

    @Test
    void telemetryIncludesCompressionRuntimeEvents() {
        AiTurnTelemetryRegistry registry = new AiTurnTelemetryRegistry();
        registry.recordRuntimeEvent("compression.checkpoint_restored");
        registry.recordRuntimeEvent("compression.checkpoint_restored");

        Map<?, ?> events = (Map<?, ?>) registry.snapshot().get("runtimeEvents");
        assertEquals(2L, events.get("compression.checkpoint_restored"));
    }
}
