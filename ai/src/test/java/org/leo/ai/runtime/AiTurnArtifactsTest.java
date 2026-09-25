package org.leo.ai.runtime;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.OpenAiTokenUsage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.leo.core.entity.AiRuntimeStats;
import org.leo.core.entity.AiSseEvent;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class AiTurnArtifactsTest {

    private final AiTurnArtifacts artifacts = new AiTurnArtifacts();

    @Test
    void buildsReviewAndPersistsOnlyFinalTimelineNodes() {
        Map<String, Object> toolStart = tool("scan", null);
        Map<String, Object> toolResult = tool("scan", true);
        List<AiSseEvent> events = List.of(
                new AiSseEvent("node", Map.of("kind", "thinking", "content", "分析")),
                new AiSseEvent("node", toolStart),
                new AiSseEvent("patch", toolResult),
                new AiSseEvent("thinking", Map.of("content", "obsolete event")));

        Map<String, Object> review = artifacts.review("完成", events, 25);
        List<Object> nodes = artifacts.assistantNodes(events, true);

        assertEquals(1, review.get("toolCount"));
        assertEquals(1, review.get("successCount"));
        assertEquals(List.of("scan"), review.get("tools"));
        assertEquals(2, nodes.size());
        assertEquals(1, artifacts.toolCallCount(events));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void snapshotsNodesWithoutMutatingEvents(boolean includeTextNodes) {
        Map<String, Object> step = new LinkedHashMap<>(Map.of("status", "IN_PROGRESS"));
        Map<String, Object> plan = new LinkedHashMap<>(Map.of("kind", "plan", "steps", List.of(step)));
        Map<String, Object> text = Map.of("kind", "text", "content", "answer", "seq", 7);
        List<AiSseEvent> events = List.of(
                new AiSseEvent("node", "not a node"),
                new AiSseEvent(12L, 1000L, "node", plan, null, null, null, null),
                new AiSseEvent("node", Map.of("kind", "thinking", "content", "analysis")),
                new AiSseEvent("node", text));

        List<Object> nodes = artifacts.assistantNodes(events, includeTextNodes);
        step.put("status", "COMPLETED");

        assertEquals(includeTextNodes ? 3 : 2, nodes.size());
        Map<?, ?> savedPlan = (Map<?, ?>) nodes.get(0);
        assertEquals(12L, ((Number) savedPlan.get("seq")).longValue());
        assertEquals(List.of(Map.of("status", "IN_PROGRESS")), savedPlan.get("steps"));
        assertFalse(plan.containsKey("seq"));
        assertEquals(3L, ((Number) ((Map<?, ?>) nodes.get(1)).get("seq")).longValue());
        if (includeTextNodes) assertEquals(text, nodes.get(2));
    }

    @Test
    void keepsBusinessResultsSeparateFromControlAndContextCounts() {
        List<AiSseEvent> events = List.of(
                new AiSseEvent("node", tool("lookup", null)),
                new AiSseEvent("patch", tool("lookup", true)),
                new AiSseEvent("patch", tool("lookup", false)),
                new AiSseEvent("patch", Map.of("kind", "tool", "toolKind", "CONTROL", "businessTool", false)),
                new AiSseEvent("patch", Map.of("kind", "tool", "toolKind", "CONTEXT", "businessTool", false)),
                new AiSseEvent("patch", "unstructured result"),
                new AiSseEvent("patch", Map.of("kind", "plan")));

        Map<String, Object> review = artifacts.review("完成", events, 25);

        assertEquals(2, review.get("toolCount"));
        assertEquals(1, review.get("controlCount"));
        assertEquals(1, review.get("contextCount"));
        assertEquals(1, review.get("successCount"));
        assertEquals(1, review.get("failureCount"));
        assertEquals(List.of("lookup"), review.get("tools"));
        assertEquals(review.get("toolCount"), artifacts.toolCallCount(events));
    }

    @Test
    void accumulatesUsageIntoConversationRuntimeStats() {
        AiRuntimeStats stats = new AiRuntimeStats();
        Map<String, Object> usage = artifacts.usage(ChatResponse.builder()
                .id("response-1")
                .aiMessage(AiMessage.from("done"))
                .tokenUsage(OpenAiTokenUsage.builder()
                        .inputTokenCount(10).outputTokenCount(5).totalTokenCount(15)
                        .inputTokensDetails(OpenAiTokenUsage.InputTokensDetails.builder()
                                .cachedTokens(4).build())
                        .outputTokensDetails(OpenAiTokenUsage.OutputTokensDetails.builder()
                                .reasoningTokens(2).build())
                        .build())
                .build());

        artifacts.accumulateUsage(stats, usage);

        assertEquals("response-1", usage.get("id"));
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
