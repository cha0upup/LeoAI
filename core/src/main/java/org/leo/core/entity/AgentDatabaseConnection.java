package org.leo.core.entity;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Date;

/** Persisted runtime-neutral database connection profile owned by a Agent. */
public class AgentDatabaseConnection {
    private String connectionId;
    private String connectionName;
    private String agentId;
    private String dialect;
    private String connectionSpec;
    private String username;
    private String password;
    private Integer status;
    private Integer testStatus;
    private Date lastTestTime;
    private String lastTestMessage;
    private Integer maxConnections;
    private Integer timeoutSeconds;
    private String createUserId;
    private Date createTime;
    private Date updateTime;
    private String description;
    private String remark;

    public AgentDatabaseConnection() {
        status = 1;
        testStatus = 0;
        maxConnections = 10;
        timeoutSeconds = 30;
        createTime = new Date();
        updateTime = new Date();
    }

    public String getConnectionId() { return connectionId; }
    public void setConnectionId(String connectionId) { this.connectionId = connectionId; }
    public String getConnectionName() { return connectionName; }
    public void setConnectionName(String connectionName) { this.connectionName = connectionName; }
    public String getAgentId() { return agentId; }
    public void setAgentId(String agentId) { this.agentId = agentId; }
    public String getDialect() { return dialect; }
    public void setDialect(String dialect) { this.dialect = dialect; }
    public String getConnectionSpec() { return connectionSpec; }
    public void setConnectionSpec(String connectionSpec) { this.connectionSpec = connectionSpec; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }
    public Integer getTestStatus() { return testStatus; }
    public void setTestStatus(Integer testStatus) { this.testStatus = testStatus; }
    public Date getLastTestTime() { return lastTestTime; }
    public void setLastTestTime(Date lastTestTime) { this.lastTestTime = lastTestTime; }
    public String getLastTestMessage() { return lastTestMessage; }
    public void setLastTestMessage(String lastTestMessage) { this.lastTestMessage = lastTestMessage; }
    public Integer getMaxConnections() { return maxConnections; }
    public void setMaxConnections(Integer maxConnections) { this.maxConnections = maxConnections; }
    public Integer getTimeoutSeconds() { return timeoutSeconds; }
    public void setTimeoutSeconds(Integer timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
    public String getCreateUserId() { return createUserId; }
    public void setCreateUserId(String createUserId) { this.createUserId = createUserId; }
    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date createTime) { this.createTime = createTime; }
    public Date getUpdateTime() { return updateTime; }
    public void setUpdateTime(Date updateTime) { this.updateTime = updateTime; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
}
