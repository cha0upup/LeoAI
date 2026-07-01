package org.leo.core.entity;

public class AiRunRecord {

    private String runId;
    private String threadId;
    private String status;
    private Long startedAt;
    private Long finishedAt;
    private Long durationMs;
    private Integer configId;
    private String input;
    private String output;
    private String errorMessage;
    private Integer toolCallCount;
    private String runtimeJson;

    public String getRunId() { return runId; }
    public void setRunId(String runId) { this.runId = runId; }

    public String getThreadId() { return threadId; }
    public void setThreadId(String threadId) { this.threadId = threadId; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public Long getStartedAt() { return startedAt; }
    public void setStartedAt(Long startedAt) { this.startedAt = startedAt; }

    public Long getFinishedAt() { return finishedAt; }
    public void setFinishedAt(Long finishedAt) { this.finishedAt = finishedAt; }

    public Long getDurationMs() { return durationMs; }
    public void setDurationMs(Long durationMs) { this.durationMs = durationMs; }

    public Integer getConfigId() { return configId; }
    public void setConfigId(Integer configId) { this.configId = configId; }

    public String getInput() { return input; }
    public void setInput(String input) { this.input = input; }

    public String getOutput() { return output; }
    public void setOutput(String output) { this.output = output; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    public Integer getToolCallCount() { return toolCallCount; }
    public void setToolCallCount(Integer toolCallCount) { this.toolCallCount = toolCallCount; }

    public String getRuntimeJson() { return runtimeJson; }
    public void setRuntimeJson(String runtimeJson) { this.runtimeJson = runtimeJson; }
}
