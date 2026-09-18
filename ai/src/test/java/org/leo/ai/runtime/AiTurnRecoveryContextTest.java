package org.leo.ai.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.leo.core.entity.AiSseEvent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiTurnRecoveryContextTest {

    @Test
    void keepsVisibleOutputToolEvidenceAndPlanWithoutThinking() {
        Map<String, Object> tool = new LinkedHashMap<>();
        tool.put("kind", "tool");
        tool.put("toolName", "getBasicInfo");
        tool.put("toolCallId", "call-1");
        tool.put("status", "completed");
        tool.put("arguments", Map.of("scope", "host"));
        tool.put("resultPreview", "os=linux; user=leo");
        List<AiSseEvent> events = List.of(
                new AiSseEvent("node", Map.of(
                        "kind", "thinking", "content", "内部分析")),
                new AiSseEvent("delta", "已定位主机。"),
                new AiSseEvent("patch", tool));

        String context = AiTurnRecoveryContext.build(
                events, Map.of("status", "IN_PROGRESS", "step", 2));

        assertTrue(context.contains("已定位主机"));
        assertTrue(context.contains("getBasicInfo"));
        assertTrue(context.contains("os=linux; user=leo"));
        assertTrue(context.contains("当前计划快照"));
        assertFalse(context.contains("内部分析"));
    }

    @Test
    void returnsEmptyContextWhenTheTurnProducedNoProgress() {
        assertTrue(AiTurnRecoveryContext.build(List.of(), null).isEmpty());
    }

    @ParameterizedTest
    @MethodSource("visibleOutputs")
    void restoresVisibleTextThroughTheActiveRecoveryPath(List<AiSseEvent> events) {
        String context = AiTurnRecoveryContext.build(events, null);
        assertTrue(context.startsWith("已经生成\n\n"));
        assertFalse(context.contains("内部分析"));
        assertFalse(context.contains("重复正文"));
    }

    private static Stream<List<AiSseEvent>> visibleOutputs() {
        return Stream.of(
                List.of(new AiSseEvent("delta", "已经"), new AiSseEvent("delta", "生成"),
                        new AiSseEvent("node", Map.of("kind", "text", "content", "重复正文"))),
                List.of(new AiSseEvent("delta", Map.of("text", "已经")),
                        new AiSseEvent("delta", Map.of("delta", "生成"))),
                List.of(new AiSseEvent("node", Map.of("kind", "thinking", "content", "内部分析")),
                        new AiSseEvent("node", Map.of("kind", "text", "content", "已经生成"))));
    }

    @Test
    void boundsLargeRecoveryContextAndRetainsTheLatestToolEvidence() {
        List<AiSseEvent> events = new ArrayList<>();
        events.add(new AiSseEvent("delta", "x".repeat(10_000)));
        for (int i = 0; i < 70; i++) {
            events.add(new AiSseEvent("patch", Map.of(
                    "kind", "tool",
                    "toolName", "tool-" + i,
                    "toolCallId", "call-" + i,
                    "status", "completed",
                    "resultPreview", "result-" + i + "-" + "y".repeat(500),
                    "archiveId", "archive-" + i)));
        }

        String context = AiTurnRecoveryContext.build(
                events, Map.of("details", "z".repeat(5_000)));

        assertTrue(context.length() <= 20_000);
        assertTrue(context.contains("更早已完成进度: 10 项"));
        assertTrue(context.contains("tool-69"));
        assertTrue(context.contains("archive-69"));
        assertFalse(context.contains("tool-0 ["));
    }
}
