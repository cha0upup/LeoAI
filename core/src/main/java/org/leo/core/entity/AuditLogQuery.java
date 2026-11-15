package org.leo.core.entity;

public class AuditLogQuery {
    public static final int MAX_LIMIT = 1000;
    private static final int DEFAULT_LIMIT = 50;

    private String userId;
    private String userName;
    private String agentId;
    private String agentName;
    private String sessionId;
    private String operationType;
    private String status;
    private String clientIp;
    private String keyword;
    private String remark;
    private String startTime;
    private String endTime;
    private Integer limit;
    private Integer offset;

    public static AuditLogQuery page(Integer limit, Integer offset) {
        AuditLogQuery query = new AuditLogQuery();
        query.limit = limit == null || limit <= 0 ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);
        query.offset = offset == null || offset < 0 ? 0 : offset;
        return query;
    }

    /** Returns normalized filters and pagination without changing the caller's query. */
    public AuditLogQuery normalized() {
        AuditLogQuery query = page(limit, offset);
        query.userId = trimToNull(userId);
        query.userName = trimToNull(userName);
        query.agentId = trimToNull(agentId);
        query.agentName = trimToNull(agentName);
        query.sessionId = trimToNull(sessionId);
        query.operationType = trimToNull(operationType);
        query.status = trimToNull(status);
        query.clientIp = trimToNull(clientIp);
        query.keyword = trimToNull(keyword);
        query.remark = trimToNull(remark);
        query.startTime = trimToNull(startTime);
        query.endTime = trimToNull(endTime);
        return query;
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getUserName() {
        return userName;
    }

    public void setUserName(String userName) {
        this.userName = userName;
    }

    public String getAgentId() {
        return agentId;
    }

    public void setAgentId(String agentId) {
        this.agentId = agentId;
    }

    public String getAgentName() {
        return agentName;
    }

    public void setAgentName(String agentName) {
        this.agentName = agentName;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getOperationType() {
        return operationType;
    }

    public void setOperationType(String operationType) {
        this.operationType = operationType;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getClientIp() {
        return clientIp;
    }

    public void setClientIp(String clientIp) {
        this.clientIp = clientIp;
    }

    public String getKeyword() {
        return keyword;
    }

    public void setKeyword(String keyword) {
        this.keyword = keyword;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }

    public String getStartTime() {
        return startTime;
    }

    public void setStartTime(String startTime) {
        this.startTime = startTime;
    }

    public String getEndTime() {
        return endTime;
    }

    public void setEndTime(String endTime) {
        this.endTime = endTime;
    }

    public Integer getLimit() {
        return limit;
    }

    public void setLimit(Integer limit) {
        this.limit = limit;
    }

    public Integer getOffset() {
        return offset;
    }

    public void setOffset(Integer offset) {
        this.offset = offset;
    }
}
