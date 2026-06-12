package org.leo.core.entity;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 会话级 AI 运行统计信息，用于分析工具利用率、失败率和整体推理效率。
 */
public class AiRuntimeStats {

    private long thinkingMessageCount;
    private long lastThinkingTimestamp;
    private long totalToolCalls;
    private long totalToolSuccessCalls;
    private long totalToolFailedCalls;
    private long totalToolDuration;
    private final Map<String, AiToolUsageStats> toolStats = new ConcurrentHashMap<>();

    // ── 累计 Token 用量 ──────────────────────────────────────────────
    private long cumulativeInputTokens;
    private long cumulativeOutputTokens;
    private long cumulativeTotalTokens;
    private long cumulativeCachedInputTokens;
    private long cumulativeReasoningTokens;
    private int turnCount;

    public synchronized void recordThinking(long timestamp) {
        thinkingMessageCount++;
        lastThinkingTimestamp = timestamp;
    }

    public synchronized void recordToolSuccess(String toolName, long startTime, long endTime, long duration, String resultPreview) {
        totalToolCalls++;
        totalToolSuccessCalls++;
        totalToolDuration += duration;
        getOrCreateToolStats(toolName).recordSuccess(startTime, endTime, duration, resultPreview);
    }

    public synchronized void recordToolFailure(String toolName, long startTime, long endTime, long duration, String errorMessage) {
        totalToolCalls++;
        totalToolFailedCalls++;
        totalToolDuration += duration;
        getOrCreateToolStats(toolName).recordFailure(startTime, endTime, duration, errorMessage);
    }

    private AiToolUsageStats getOrCreateToolStats(String toolName) {
        String safeToolName = toolName == null || toolName.isBlank() ? "unknown" : toolName;
        return toolStats.computeIfAbsent(safeToolName, AiToolUsageStats::new);
    }

    public long getThinkingMessageCount() {
        return thinkingMessageCount;
    }

    public long getLastThinkingTimestamp() {
        return lastThinkingTimestamp;
    }

    public long getTotalToolCalls() {
        return totalToolCalls;
    }

    public long getTotalToolSuccessCalls() {
        return totalToolSuccessCalls;
    }

    public long getTotalToolFailedCalls() {
        return totalToolFailedCalls;
    }

    public long getTotalToolDuration() {
        return totalToolDuration;
    }

    public Map<String, AiToolUsageStats> getToolStats() {
        return toolStats;
    }

    public double getAverageToolDuration() {
        if (totalToolCalls == 0) {
            return 0D;
        }
        return (double) totalToolDuration / totalToolCalls;
    }

    // ── Token 用量累计 ──────────────────────────────────────────────

    /**
     * 累加一轮对话的 token 用量。由 buildUsageEvent 在每轮完成时调用。
     */
    public synchronized void accumulateTokenUsage(long inputTokens, long outputTokens,
                                                   long totalTokens, long cachedInputTokens,
                                                   long reasoningTokens) {
        this.cumulativeInputTokens += inputTokens;
        this.cumulativeOutputTokens += outputTokens;
        this.cumulativeTotalTokens += totalTokens;
        this.cumulativeCachedInputTokens += cachedInputTokens;
        this.cumulativeReasoningTokens += reasoningTokens;
        this.turnCount++;
    }

    public long getCumulativeInputTokens() { return cumulativeInputTokens; }
    public long getCumulativeOutputTokens() { return cumulativeOutputTokens; }
    public long getCumulativeTotalTokens() { return cumulativeTotalTokens; }
    public long getCumulativeCachedInputTokens() { return cumulativeCachedInputTokens; }
    public long getCumulativeReasoningTokens() { return cumulativeReasoningTokens; }
    public int getTurnCount() { return turnCount; }
}
