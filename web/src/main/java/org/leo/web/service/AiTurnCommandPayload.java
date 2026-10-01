package org.leo.web.service;

import com.alibaba.fastjson.JSON;
import org.leo.core.entity.AiExecutionPolicy;

/** 可持久化的 Turn 执行命令；不包含运行时对象或 HTTP 请求引用。 */
public class AiTurnCommandPayload {

    public static final String SCOPE_PLATFORM = "platform";
    public static final String SCOPE_AGENT = "agent";

    private String scope;
    private String sessionId;
    private String userMessage;
    private String guardedMessage;
    private Integer configId;
    private String reasoningEffort;
    private Object attachments;
    private String userId;
    private String userName;
    private String privilege;
    private String answerToQuestionId;

    public static AiTurnCommandPayload create(
            String scope, String sessionId, String userMessage,
            String guardedMessage, Integer configId, String reasoningEffort,
            Object attachments, AiExecutionPolicy policy) {
        AiTurnCommandPayload value = new AiTurnCommandPayload();
        value.scope = scope;
        value.sessionId = sessionId;
        value.userMessage = userMessage;
        value.guardedMessage = guardedMessage;
        value.configId = configId;
        value.reasoningEffort = reasoningEffort;
        value.attachments = attachments;
        if (policy != null) {
            value.userId = policy.getUserId();
            value.userName = policy.getUserName();
            value.privilege = policy.getPrivilege();
        }
        return value;
    }

    public AiTurnCommandPayload answerTo(String questionId) {
        this.answerToQuestionId = questionId == null || questionId.isBlank()
                ? null : questionId.trim();
        return this;
    }

    public String toJson() {
        return JSON.toJSONString(this);
    }

    public static AiTurnCommandPayload fromJson(String json) {
        if (json == null || json.isBlank()) {
            throw new IllegalStateException("Turn 命令载荷缺失");
        }
        return JSON.parseObject(json, AiTurnCommandPayload.class);
    }

    public AiExecutionPolicy executionPolicy() {
        return new AiExecutionPolicy(userId, userName, privilege);
    }

    public String getScope() { return scope; }
    public void setScope(String scope) { this.scope = scope; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public String getUserMessage() { return userMessage; }
    public void setUserMessage(String userMessage) { this.userMessage = userMessage; }
    public String getGuardedMessage() { return guardedMessage; }
    public void setGuardedMessage(String guardedMessage) {
        this.guardedMessage = guardedMessage;
    }
    public Integer getConfigId() { return configId; }
    public void setConfigId(Integer configId) { this.configId = configId; }
    public String getReasoningEffort() { return reasoningEffort; }
    public void setReasoningEffort(String reasoningEffort) {
        this.reasoningEffort = reasoningEffort;
    }
    public Object getAttachments() { return attachments; }
    public void setAttachments(Object attachments) { this.attachments = attachments; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getUserName() { return userName; }
    public void setUserName(String userName) { this.userName = userName; }
    public String getPrivilege() { return privilege; }
    public void setPrivilege(String privilege) { this.privilege = privilege; }
    public String getAnswerToQuestionId() { return answerToQuestionId; }
    public void setAnswerToQuestionId(String answerToQuestionId) {
        this.answerToQuestionId = answerToQuestionId;
    }
}
