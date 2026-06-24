package org.leo.web.service;

import dev.langchain4j.model.chat.response.PartialToolCall;
import dev.langchain4j.service.tool.BeforeToolExecution;
import dev.langchain4j.service.tool.ToolExecution;
import org.leo.ai.agent.AiToolContext;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Normalizes LangChain4j tool lifecycle callbacks into frontend SSE payloads.
 */
class AiToolEventFactory {

    private AiToolEventFactory() {
    }

    /** 工具开始执行 → 前端 {@code node} 事件（kind=tool）。 */
    static Map<String, Object> buildToolStartEventData(BeforeToolExecution execution) {
        LinkedHashMap<String, Object> data = new LinkedHashMap<>();
        long now = System.currentTimeMillis();
        data.put("kind", "tool");
        data.put("toolName", execution.request().name());
        data.put("toolCallId", execution.request().id());
        data.put("arguments", execution.request().arguments());
        data.put("success", null);
        data.put("status", "running");
        data.put("timestamp", now);
        data.put("startTime", now);
        data.put("endTime", null);
        injectPlanStepIndex(data);
        return data;
    }

    /** 工具参数流式生成中 → 前端 {@code tool_delta} 事件（kind=tool）。 */
    static Map<String, Object> buildToolDeltaEventData(PartialToolCall partial) {
        LinkedHashMap<String, Object> data = new LinkedHashMap<>();
        long now = System.currentTimeMillis();
        String id = partial.id();
        data.put("kind", "tool");
        data.put("toolName", partial.name());
        data.put("toolCallId", id == null || id.isBlank() ? "tool-draft-" + partial.index() : id);
        data.put("arguments", partial.partialArguments());
        data.put("success", null);
        data.put("status", "running");
        data.put("message", "正在生成工具调用");
        data.put("timestamp", now);
        data.put("startTime", now);
        data.put("endTime", null);
        return data;
    }

    /** 工具执行完毕 → 前端 {@code patch} 事件（kind=tool）。 */
    static Map<String, Object> buildToolEventData(ToolExecution execution) {
        LinkedHashMap<String, Object> data = new LinkedHashMap<>();
        long now = System.currentTimeMillis();
        long startTime = toEpochMs(execution.startTime(), now);
        data.put("kind", "tool");
        data.put("toolName", execution.request().name());
        data.put("toolCallId", execution.request().id());
        data.put("arguments", execution.request().arguments());
        data.put("resultPreview", truncate(execution.result(), 2000));
        data.put("success", !execution.hasFailed());
        data.put("status", execution.hasFailed() ? "failed" : "completed");
        data.put("timestamp", startTime);
        data.put("startTime", startTime);
        data.put("endTime", toEpochMs(execution.finishTime(), now));
        injectPlanStepIndex(data);
        return data;
    }

    private static void injectPlanStepIndex(Map<String, Object> data) {
        int idx = AiToolContext.getPlanStepIndex();
        if (idx >= 0) data.put("planStepIndex", idx);
    }

    private static String truncate(String value, int max) {
        if (value == null) return null;
        return value.length() > max ? value.substring(0, max) + "\n...(已截断)" : value;
    }

    private static long toEpochMs(LocalDateTime ldt, long fallback) {
        if (ldt == null) return fallback;
        return ldt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }
}
