package org.leo.core.entity;

/**
 * 单个工具的聚合统计信息，用于观察工具调用效率和失败模式。
 */
public class AiToolUsageStats {

    private final String toolName;
    private long totalCalls;
    private long successCalls;
    private long failedCalls;
    private long totalDuration;
    private long maxDuration;
    private long lastDuration;
    private long lastStartTime;
    private long lastEndTime;
    private String lastErrorMessage;
    private String lastResultPreview;

    public AiToolUsageStats(String toolName) {
        this.toolName = toolName;
    }

    public synchronized void recordSuccess(long startTime, long endTime, long duration, String resultPreview) {
        totalCalls++;
        successCalls++;
        totalDuration += duration;
        maxDuration = Math.max(maxDuration, duration);
        lastDuration = duration;
        lastStartTime = startTime;
        lastEndTime = endTime;
        lastErrorMessage = null;
        lastResultPreview = resultPreview;
    }

    public synchronized void recordFailure(long startTime, long endTime, long duration, String errorMessage) {
        totalCalls++;
        failedCalls++;
        totalDuration += duration;
        maxDuration = Math.max(maxDuration, duration);
        lastDuration = duration;
        lastStartTime = startTime;
        lastEndTime = endTime;
        lastErrorMessage = errorMessage;
    }

    public String getToolName() {
        return toolName;
    }

    public long getTotalCalls() {
        return totalCalls;
    }

    public long getSuccessCalls() {
        return successCalls;
    }

    public long getFailedCalls() {
        return failedCalls;
    }

    public long getTotalDuration() {
        return totalDuration;
    }

    public long getMaxDuration() {
        return maxDuration;
    }

    public long getLastDuration() {
        return lastDuration;
    }

    public long getLastStartTime() {
        return lastStartTime;
    }

    public long getLastEndTime() {
        return lastEndTime;
    }

    public String getLastErrorMessage() {
        return lastErrorMessage;
    }

    public String getLastResultPreview() {
        return lastResultPreview;
    }

    public double getAverageDuration() {
        if (totalCalls == 0) {
            return 0D;
        }
        return (double) totalDuration / totalCalls;
    }
}
