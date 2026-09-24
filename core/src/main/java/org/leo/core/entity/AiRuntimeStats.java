package org.leo.core.entity;

/**
 * AI 对话的累计 Token 用量。
 */
public class AiRuntimeStats {

    private long cumulativeInputTokens;
    private long cumulativeOutputTokens;
    private long cumulativeTotalTokens;
    private long cumulativeCachedInputTokens;
    private long cumulativeReasoningTokens;
    private int turnCount;

    /**
     * 累加一轮对话的 token 用量。
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
