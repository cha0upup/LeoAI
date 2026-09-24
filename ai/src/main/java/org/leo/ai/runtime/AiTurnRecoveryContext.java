package org.leo.ai.runtime;

import com.alibaba.fastjson.JSON;
import org.leo.core.entity.AiSseEvent;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 将未完成 Turn 中已经产生的可见输出、工具结果和计划快照整理成可恢复上下文。
 *
 * <p>该文本会随 discarded assistant 消息持久化，并在后续重建 ChatMemory 时重新载入。
 * 只提取用户已经可见的执行事件，不保存 thinking 内容。
 */
public final class AiTurnRecoveryContext {

    private static final int MAX_CONTEXT_CHARS = 20_000;
    private static final int MAX_VISIBLE_OUTPUT_CHARS = 3_000;
    private static final int MAX_PROGRESS_ITEMS = 60;
    private static final int MAX_PROGRESS_ITEM_CHARS = 240;
    private static final int MAX_TOOL_NAME_CHARS = 80;
    private static final int MAX_ARCHIVE_ID_CHARS = 100;
    private static final int MAX_RESULT_CHARS = 160;
    private static final int MAX_ARGUMENT_CHARS = 120;
    private static final int MAX_PLAN_CHARS = 1_500;

    private AiTurnRecoveryContext() {
    }

    public static String build(List<AiSseEvent> eventLog, Object planSnapshot) {
        String visibleOutput = visibleOutput(eventLog);
        List<String> progress = new ArrayList<>();
        Set<String> completedCalls = new LinkedHashSet<>();
        Object latestPlan = planSnapshot;

        if (eventLog != null) {
            for (AiSseEvent event : eventLog) {
                if (event == null || !(event.data() instanceof Map<?, ?> data)) continue;
                String kind = string(data.get("kind"));
                if ("node".equals(event.name()) && "plan".equals(kind)) {
                    latestPlan = data;
                    continue;
                }
                if (!"patch".equals(event.name())) continue;
                if ("tool".equals(kind)) {
                    addToolProgress(progress, completedCalls, data);
                } else if ("subtask".equals(kind)) {
                    addSubtaskProgress(progress, data);
                }
            }
        }

        boolean hasPlan = latestPlan != null;
        if (visibleOutput.isBlank() && progress.isEmpty() && !hasPlan) return "";

        StringBuilder context = new StringBuilder();
        if (!visibleOutput.isBlank()) {
            context.append(truncate(visibleOutput.trim(), MAX_VISIBLE_OUTPUT_CHARS))
                    .append("\n\n");
        }
        context.append("[执行在此处中断；以下是可用于继续任务的已完成进度]");
        int firstProgress = Math.max(0, progress.size() - MAX_PROGRESS_ITEMS);
        if (firstProgress > 0) {
            context.append("\n- 更早已完成进度: ")
                    .append(firstProgress).append(" 项（详见原 Turn 时间线）");
        }
        for (int i = firstProgress; i < progress.size(); i++) {
            context.append("\n- ").append(progress.get(i));
        }
        if (hasPlan) {
            context.append("\n- 当前计划快照: ")
                    .append(compact(latestPlan, MAX_PLAN_CHARS));
        }
        context.append("\n后续收到“继续”时，从上述进度衔接，不重复已经完成的步骤。");
        return truncate(context.toString(), MAX_CONTEXT_CHARS);
    }

    private static void addToolProgress(List<String> progress,
                                        Set<String> completedCalls,
                                        Map<?, ?> data) {
        String status = string(data.get("status"));
        if (status == null || "running".equalsIgnoreCase(status)) return;
        String callId = string(data.get("toolCallId"));
        if (callId != null && !completedCalls.add(callId)) return;

        StringBuilder item = new StringBuilder("工具 ")
                .append(truncateInline(
                        defaultText(string(data.get("toolName")), "unknown"),
                        MAX_TOOL_NAME_CHARS))
                .append(" [").append(truncateInline(status, 24)).append(']');
        Object archiveId = data.get("archiveId");
        if (archiveId != null && !String.valueOf(archiveId).isBlank()) {
            // 完整结果的定位信息比预览更重要，放在截断优先区。
            item.append("，archiveId: ")
                    .append(compact(archiveId, MAX_ARCHIVE_ID_CHARS));
        }
        Object result = data.get("resultPreview");
        if (result != null && !String.valueOf(result).isBlank()) {
            item.append("，结果: ")
                    .append(compact(result, MAX_RESULT_CHARS));
        } else {
            Object arguments = data.get("arguments");
            if (arguments != null) {
                item.append("，参数: ")
                        .append(compact(arguments, MAX_ARGUMENT_CHARS));
            }
        }
        progress.add(truncateInline(item.toString(), MAX_PROGRESS_ITEM_CHARS));
    }

    private static void addSubtaskProgress(List<String> progress, Map<?, ?> data) {
        String status = string(data.get("status"));
        if (status == null || "pending".equalsIgnoreCase(status)
                || "running".equalsIgnoreCase(status)) {
            return;
        }
        StringBuilder item = new StringBuilder("子任务 [")
                .append(status).append("]");
        Object task = data.get("task");
        if (task != null) item.append("，任务: ").append(compact(task, 100));
        Object summary = data.get("summary");
        if (summary != null && !String.valueOf(summary).isBlank()) {
            item.append("，结果: ").append(compact(summary, MAX_RESULT_CHARS));
        }
        progress.add(truncateInline(item.toString(), MAX_PROGRESS_ITEM_CHARS));
    }

    private static String visibleOutput(List<AiSseEvent> eventLog) {
        if (eventLog == null || eventLog.isEmpty()) return "";
        StringBuilder deltas = new StringBuilder();
        for (AiSseEvent event : eventLog) {
            if (event != null && "delta".equals(event.name())
                    && event.data() instanceof String text) {
                deltas.append(text);
            }
        }
        if (!deltas.isEmpty()) return deltas.toString().trim();

        StringBuilder nodes = new StringBuilder();
        for (AiSseEvent event : eventLog) {
            if (event == null || !"node".equals(event.name())
                    || !(event.data() instanceof Map<?, ?> data)
                    || !"text".equals(string(data.get("kind")))) {
                continue;
            }
            Object content = data.get("content");
            if (content != null) nodes.append(content);
        }
        return nodes.toString().trim();
    }

    private static String compact(Object value, int maxChars) {
        if (value == null) return "";
        String text;
        if (value instanceof String string) {
            text = string;
        } else {
            try {
                text = JSON.toJSONString(value);
            } catch (RuntimeException ignored) {
                text = String.valueOf(value);
            }
        }
        return truncateInline(text.replaceAll("\\s+", " ").trim(), maxChars);
    }

    private static String truncate(String value, int maxChars) {
        if (value == null || value.length() <= maxChars) return value;
        String suffix = "\n...(已截断)";
        return value.substring(0, Math.max(0, maxChars - suffix.length())) + suffix;
    }

    private static String truncateInline(String value, int maxChars) {
        if (value == null || value.length() <= maxChars) return value;
        String suffix = "...(已截断)";
        return value.substring(0, Math.max(0, maxChars - suffix.length())) + suffix;
    }

    private static String string(Object value) {
        return value != null ? String.valueOf(value) : null;
    }

    private static String defaultText(String value, String fallback) {
        return value != null && !value.isBlank() ? value : fallback;
    }
}
