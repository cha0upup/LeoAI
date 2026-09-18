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

    public List<Object> assistantNodes(List<AiSseEvent> eventLog) {
        return assistantNodes(eventLog, true);
    }

    public List<Object> assistantNodes(List<AiSseEvent> eventLog,
                                       boolean includeTextNodes) {
        if (eventLog == null || eventLog.isEmpty()) return List.of();
        List<Object> nodes = new ArrayList<>();
        for (int index = 0; index < eventLog.size(); index++) {
            AiSseEvent event = eventLog.get(index);
            String name = event.name();
            String kind = kindOf(event.data());
            if ("thinking".equals(name)
                    || ("node".equals(name) && ("thinking".equals(kind)
                            || (includeTextNodes && "text".equals(kind))
                            || "plan".equals(kind)
                            || "subtask".equals(kind)
                            || "user_input".equals(kind)))
                    || ("patch".equals(name) && ("tool".equals(kind)
                            || "subtask".equals(kind)))) {
                long sequence = event.seq() > 0 ? event.seq() : index + 1L;
                nodes.add(withSequence(event, sequence));
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
                if (!isCompletedTool(event)) continue;
                if (event.data() instanceof Map<?, ?> map) {
                    Object rawKind = map.get("toolKind");
                    String toolKind = rawKind != null ? String.valueOf(rawKind) : "COMMAND";
                    boolean business = !Boolean.FALSE.equals(map.get("businessTool"));
                    if (business) toolCount++;
                    else if ("CONTROL".equals(toolKind)) controlCount++;
                    else contextCount++;
                    if (!business) continue;
                    if (Boolean.FALSE.equals(map.get("success"))) {
                        failureCount++;
                    } else {
                        successCount++;
                    }
                    Object toolName = map.get("toolName");
                    if (toolName instanceof String name
                            && !name.isBlank() && !tools.contains(name)) {
                        tools.add(name);
                    }
                } else {
                    toolCount++;
                    successCount++;
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
            if (isCompletedBusinessTool(event)) count++;
        }
        return count;
    }

    private boolean isCompletedTool(AiSseEvent event) {
        return event != null
                && "patch".equals(event.name())
                && "tool".equals(kindOf(event.data()));
    }

    private boolean isCompletedBusinessTool(AiSseEvent event) {
        if (!isCompletedTool(event)) return false;
        return !(event.data() instanceof Map<?, ?> map)
                || !Boolean.FALSE.equals(map.get("businessTool"));
    }

    private Object withSequence(AiSseEvent event, long sequence) {
        Object payload = event.data();
        if (payload == null) return null;
        try {
            String json = JSON.toJSONString(payload);
            @SuppressWarnings("unchecked")
            Map<String, Object> map = JSON.parseObject(json, Map.class);
            if (map == null) return payload;
            map.putIfAbsent("seq", sequence);
            return map;
        } catch (Exception ignored) {
            return payload;
        }
    }

    private String kindOf(Object data) {
        if (data instanceof Map<?, ?> map && map.get("kind") instanceof String kind) {
            return kind;
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
