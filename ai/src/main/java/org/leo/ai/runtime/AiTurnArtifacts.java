package org.leo.ai.runtime;

import com.alibaba.fastjson.JSON;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import dev.langchain4j.model.openai.OpenAiTokenUsage;
import org.leo.core.entity.AiRuntimeStats;
import org.leo.core.entity.AiSseEvent;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 构建每轮执行的 usage、review 和可持久化 timeline 节点。 */
@Component
public class AiTurnArtifacts {

    public Map<String, Object> usage(ChatResponse response) {
        Map<String, Object> usage = new LinkedHashMap<>();
        if (response == null) return usage;
        if (response.id() != null) usage.put("id", response.id());
        if (response.modelName() != null) usage.put("model", response.modelName());
        if (response.finishReason() != null) {
            usage.put("finishReason", response.finishReason().name().toLowerCase());
        }
        TokenUsage tokenUsage = response.tokenUsage();
        if (tokenUsage != null) {
            usage.put("inputTokens", tokenUsage.inputTokenCount());
            usage.put("outputTokens", tokenUsage.outputTokenCount());
            usage.put("totalTokens", tokenUsage.totalTokenCount());
            if (tokenUsage instanceof OpenAiTokenUsage openaiUsage) {
                var inputDetails = openaiUsage.inputTokensDetails();
                if (inputDetails != null && inputDetails.cachedTokens() != null) {
                    usage.put("cachedInputTokens", inputDetails.cachedTokens());
                }
                var outputDetails = openaiUsage.outputTokensDetails();
                if (outputDetails != null && outputDetails.reasoningTokens() != null) {
                    usage.put("reasoningTokens", outputDetails.reasoningTokens());
                }
            }
        }
        usage.put("timestamp", System.currentTimeMillis());
        return usage;
    }

    public void accumulateUsage(AiRuntimeStats stats, Map<String, Object> usage) {
        if (stats == null || usage == null) return;
        stats.accumulateTokenUsage(
                toLong(usage.get("inputTokens")),
                toLong(usage.get("outputTokens")),
                toLong(usage.get("totalTokens")),
                toLong(usage.get("cachedInputTokens")),
                toLong(usage.get("reasoningTokens")));

        Map<String, Object> cumulative = new LinkedHashMap<>();
        cumulative.put("inputTokens", stats.getCumulativeInputTokens());
        cumulative.put("outputTokens", stats.getCumulativeOutputTokens());
        cumulative.put("totalTokens", stats.getCumulativeTotalTokens());
        cumulative.put("cachedInputTokens", stats.getCumulativeCachedInputTokens());
        cumulative.put("reasoningTokens", stats.getCumulativeReasoningTokens());
        cumulative.put("turnCount", stats.getTurnCount());
        usage.put("cumulative", cumulative);
    }

    public List<Object> assistantNodes(List<AiSseEvent> eventLog,
                                       boolean includeTextNodes) {
        if (eventLog == null || eventLog.isEmpty()) return List.of();
        List<Object> nodes = new ArrayList<>();
        for (int index = 0; index < eventLog.size(); index++) {
            AiSseEvent event = eventLog.get(index);
            if (!(event.data() instanceof Map<?, ?> data)) continue;
            String name = event.name();
            Object kind = data.get("kind");
            if (("node".equals(name) && ("thinking".equals(kind)
                            || (includeTextNodes && "text".equals(kind))
                            || "plan".equals(kind)
                            || "subtask".equals(kind)
                            || "user_input".equals(kind)))
                    || ("patch".equals(name) && ("tool".equals(kind)
                            || "subtask".equals(kind)))) {
                // 保留独立快照，避免计划等嵌套对象的后续更新修改已完成的节点。
                Map<String, Object> node = JSON.parseObject(JSON.toJSONString(data));
                node.putIfAbsent("seq", event.seq() > 0 ? event.seq() : index + 1L);
                nodes.add(node);
            }
        }
        return nodes;
    }

    public Map<String, Object> review(String output,
                                      List<AiSseEvent> eventLog,
                                      long durationMs) {
        LinkedHashMap<String, Object> review = new LinkedHashMap<>();
        int toolCount = 0;
        int controlCount = 0;
        int contextCount = 0;
        int successCount = 0;
        int failureCount = 0;
        List<String> tools = new ArrayList<>();
        if (eventLog != null) {
            for (AiSseEvent event : eventLog) {
                Map<?, ?> data = completedToolData(event);
                if (data == null) continue;
                if (Boolean.FALSE.equals(data.get("businessTool"))) {
                    if ("CONTROL".equals(data.get("toolKind"))) controlCount++;
                    else contextCount++;
                    continue;
                }
                toolCount++;
                if (Boolean.FALSE.equals(data.get("success"))) {
                    failureCount++;
                } else {
                    successCount++;
                }
                Object toolName = data.get("toolName");
                if (toolName instanceof String name
                        && !name.isBlank() && !tools.contains(name)) {
                    tools.add(name);
                }
            }
        }
        review.put("durationMs", Math.max(0L, durationMs));
        review.put("toolCount", toolCount);
        review.put("controlCount", controlCount);
        review.put("contextCount", contextCount);
        review.put("successCount", successCount);
        review.put("failureCount", failureCount);
        review.put("tools", tools);
        review.put("conclusionPreview", truncate(output != null ? output.trim() : "", 500));
        review.put("createdAt", System.currentTimeMillis());
        return review;
    }

    public int toolCallCount(List<AiSseEvent> eventLog) {
        if (eventLog == null) return 0;
        int count = 0;
        for (AiSseEvent event : eventLog) {
            Map<?, ?> data = completedToolData(event);
            if (data != null && !Boolean.FALSE.equals(data.get("businessTool"))) count++;
        }
        return count;
    }

    private Map<?, ?> completedToolData(AiSseEvent event) {
        if (event != null
                && "patch".equals(event.name())
                && event.data() instanceof Map<?, ?> data
                && "tool".equals(data.get("kind"))) {
            return data;
        }
        return null;
    }

    private long toLong(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private String truncate(String value, int max) {
        if (value == null) return null;
        return value.length() > max ? value.substring(0, max) + "\n...(已截断)" : value;
    }
}
