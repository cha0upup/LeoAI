package org.leo.ai.runtime;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.OpenAiTokenUsage;
import dev.langchain4j.model.output.FinishReason;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.leo.core.entity.AiRuntimeStats;
import org.leo.core.entity.AiSseEvent;

import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class AiTurnArtifactsTest {

    private final AiTurnArtifacts artifacts = new AiTurnArtifacts();

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void snapshotsNodesWithoutMutatingEvents(boolean includeTextNodes) {
        Map<String, Object> step = new LinkedHashMap<>(Map.of("status", "IN_PROGRESS"));
        Map<String, Object> plan = new LinkedHashMap<>(Map.of("kind", "plan", "steps", List.of(step)));
        Map<String, Object> text = Map.of("kind", "text", "content", "answer", "seq", 7);
        List<AiSseEvent> events = new LinkedList<>(List.of(
                new AiSseEvent("node", "not a node"),
                new AiSseEvent(12L, 1000L, "node", plan, null, null, null, null),
                new AiSseEvent("node", Map.of("kind", "thinking", "content", "analysis")),
                new AiSseEvent("node", tool("scan", null)),
                new AiSseEvent("patch", tool("scan", true)),
                new AiSseEvent("node", text),
                new AiSseEvent("thinking", Map.of("kind", "thinking", "content", "obsolete event"))));

        List<Object> nodes = artifacts.assistantNodes(events, includeTextNodes);
        step.put("status", "COMPLETED");

        assertEquals(includeTextNodes ? 4 : 3, nodes.size());
        Map<?, ?> savedPlan = (Map<?, ?>) nodes.get(0);
        assertEquals(12L, ((Number) savedPlan.get("seq")).longValue());
        assertEquals(List.of(Map.of("status", "IN_PROGRESS")), savedPlan.get("steps"));
        assertFalse(plan.containsKey("seq"));
        assertEquals(3L, ((Number) ((Map<?, ?>) nodes.get(1)).get("seq")).longValue());
        assertEquals(Map.of("kind", "tool", "toolName", "scan", "success", true, "seq", 5L), nodes.get(2));
        if (includeTextNodes) assertEquals(text, nodes.get(3));
    }

    @Test
    void keepsBusinessResultsSeparateFromControlAndContextCounts() {
        List<AiSseEvent> events = List.of(
                new AiSseEvent("node", tool("lookup", null)),
                new AiSseEvent("patch", tool("lookup", true)),
                new AiSseEvent("patch", tool("alpha", true)),
                new AiSseEvent("patch", tool("lookup", false)),
                new AiSseEvent("patch", Map.of("kind", "tool", "toolKind", "CONTROL", "businessTool", false)),
                new AiSseEvent("patch", Map.of("kind", "tool", "toolKind", "CONTEXT", "businessTool", false)),
                new AiSseEvent("patch", "unstructured result"),
                new AiSseEvent("patch", Map.of("kind", "plan")));

        Map<String, Object> review = artifacts.review("完成", events, 25);

        assertEquals(3, review.get("toolCount"));
        assertEquals(1, review.get("controlCount"));
        assertEquals(1, review.get("contextCount"));
        assertEquals(2, review.get("successCount"));
        assertEquals(1, review.get("failureCount"));
        assertEquals(List.of("lookup", "alpha"), review.get("tools"));
        assertEquals(review.get("toolCount"), artifacts.toolCallCount(events));
    }

    @ParameterizedTest
    @CsvSource(value = {"en-US,null,null", "tr-TR,TOOL_EXECUTION,tool_execution",
            "tr-TR,CONTENT_FILTER,content_filter"}, nullValues = "null")
    @ResourceLock("java.util.Locale.default")
    void accumulatesUsageIntoConversationRuntimeStats(String languageTag, FinishReason reason, String finishReason) {
        AiRuntimeStats stats = new AiRuntimeStats();
        ChatResponse response = ChatResponse.builder()
                .id("response-1")
                .aiMessage(AiMessage.from("done"))
                .finishReason(reason)
                .tokenUsage(OpenAiTokenUsage.builder()
                        .inputTokenCount(10).outputTokenCount(5).totalTokenCount(15)
                        .inputTokensDetails(OpenAiTokenUsage.InputTokensDetails.builder()
                                .cachedTokens(4).build())
                        .outputTokensDetails(OpenAiTokenUsage.OutputTokensDetails.builder()
                                .reasoningTokens(2).build())
                        .build())
                .build();
        Map<String, Object> usage;
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag(languageTag));
            usage = artifacts.usage(response);
        } finally {
            Locale.setDefault(previous);
        }

        artifacts.accumulateUsage(stats, usage);

        assertEquals("response-1", usage.get("id"));
        if (reason == null) assertFalse(usage.containsKey("finishReason"));
        else assertEquals(finishReason, usage.get("finishReason"));
        assertFalse(usage.containsKey("responseId"));
        assertEquals(10, stats.getCumulativeInputTokens());
        assertEquals(5, stats.getCumulativeOutputTokens());
        assertEquals(15, stats.getCumulativeTotalTokens());
        assertEquals(4, stats.getCumulativeCachedInputTokens());
        assertEquals(2, stats.getCumulativeReasoningTokens());
        assertEquals(Map.of("inputTokens", 10L, "outputTokens", 5L, "totalTokens", 15L,
                        "cachedInputTokens", 4L, "reasoningTokens", 2L, "turnCount", 1),
                usage.get("cumulative"));
    }

    private Map<String, Object> tool(String name, Boolean success) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("kind", "tool");
        data.put("toolName", name);
        data.put("success", success);
        return data;
    }
}
