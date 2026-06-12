package org.leo.core.entity;

public class AiThreadRecord {

    private String threadId;
    private String scope;
    private String userId;
    private String puppetId;
    private String sessionId;
    private String title;
    private Integer configId;
    private String configName;
    private String configProtocol;
    private String configModel;
    private String configBaseUrl;
    private String configCompletionsPath;
    private Integer configMaxOutputTokens;
    private Long createdAt;
    private Long lastActiveAt;
    private Integer messageCount;
    private String runStatus;
    /** 父会话 ID（非空表示当前是子 Agent 派发出来的隔离上下文）。 */
    private String parentThreadId;
    /** 执行模式：plan / execute / auto。 */
    private String mode;
    /** 上下文压缩后写入的摘要（可为 null，未压缩时为 null）。 */
    private String contextSummary;
    /** 当前活跃 plan 的 ID（可为 null）。 */
    private String rootPlanId;

    public String getThreadId() { return threadId; }
    public void setThreadId(String threadId) { this.threadId = threadId; }

    public String getScope() { return scope; }
    public void setScope(String scope) { this.scope = scope; }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public String getPuppetId() { return puppetId; }
    public void setPuppetId(String puppetId) { this.puppetId = puppetId; }

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public Integer getConfigId() { return configId; }
    public void setConfigId(Integer configId) { this.configId = configId; }

    public String getConfigName() { return configName; }
    public void setConfigName(String configName) { this.configName = configName; }

    public String getConfigProtocol() { return configProtocol; }
    public void setConfigProtocol(String configProtocol) { this.configProtocol = configProtocol; }

    public String getConfigModel() { return configModel; }
    public void setConfigModel(String configModel) { this.configModel = configModel; }

    public String getConfigBaseUrl() { return configBaseUrl; }
    public void setConfigBaseUrl(String configBaseUrl) { this.configBaseUrl = configBaseUrl; }

    public String getConfigCompletionsPath() { return configCompletionsPath; }
    public void setConfigCompletionsPath(String configCompletionsPath) {
        this.configCompletionsPath = configCompletionsPath;
    }

    public Integer getConfigMaxOutputTokens() { return configMaxOutputTokens; }
    public void setConfigMaxOutputTokens(Integer configMaxOutputTokens) {
        this.configMaxOutputTokens = configMaxOutputTokens;
    }

    public Long getCreatedAt() { return createdAt; }
    public void setCreatedAt(Long createdAt) { this.createdAt = createdAt; }

    public Long getLastActiveAt() { return lastActiveAt; }
    public void setLastActiveAt(Long lastActiveAt) { this.lastActiveAt = lastActiveAt; }

    public Integer getMessageCount() { return messageCount; }
    public void setMessageCount(Integer messageCount) { this.messageCount = messageCount; }

    public String getRunStatus() { return runStatus; }
    public void setRunStatus(String runStatus) { this.runStatus = runStatus; }

    public String getParentThreadId() { return parentThreadId; }
    public void setParentThreadId(String parentThreadId) { this.parentThreadId = parentThreadId; }

    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }

    public String getContextSummary() { return contextSummary; }
    public void setContextSummary(String contextSummary) { this.contextSummary = contextSummary; }

    public String getRootPlanId() { return rootPlanId; }
    public void setRootPlanId(String rootPlanId) { this.rootPlanId = rootPlanId; }
}
