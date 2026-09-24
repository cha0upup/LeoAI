package org.leo.ai.runtime;

import org.junit.jupiter.api.Test;
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
        List<Object> nodes = artifacts.assistantNodes(events);

        assertEquals(1, review.get("toolCount"));
        assertEquals(1, review.get("successCount"));
        assertEquals(List.of("scan"), review.get("tools"));
        assertEquals(2, nodes.size());
        assertEquals(1, artifacts.toolCallCount(events));
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
        Map<String, Object> usage = new LinkedHashMap<>();
        usage.put("inputTokens", 10);
        usage.put("outputTokens", 5);
        usage.put("totalTokens", 15);

        artifacts.accumulateUsage(stats, usage);

        assertEquals(10, stats.getCumulativeInputTokens());
        assertEquals(5, stats.getCumulativeOutputTokens());
        assertEquals(15, stats.getCumulativeTotalTokens());
        assertFalse(((Map<?, ?>) usage.get("cumulative")).isEmpty());
    }

    private Map<String, Object> tool(String name, Boolean success) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("kind", "tool");
        data.put("toolName", name);
        data.put("success", success);
        return data;
    }
}
