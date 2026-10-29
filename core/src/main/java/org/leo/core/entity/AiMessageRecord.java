package org.leo.core.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.alibaba.fastjson.annotation.JSONField;

public class AiMessageRecord {

    private String messageId;
    private String threadId;
    private String turnId;
    private String runId;
    private String runStatus;
    private String protocolStatus;
    private String dispatchStatus;
    private String protocolErrorMessage;
    private String answerToQuestionId;
    private Long messageSeq;
    private String status;
    private String role;
    private String content;
    private String recoveryContext;
    private Long timestamp;
    private String attachmentsJson;
    private String nodesJson;
    private String reviewJson;
    private String planJson;

    public String getMessageId() { return messageId; }
    public void setMessageId(String messageId) { this.messageId = messageId; }

    public String getThreadId() { return threadId; }
    public void setThreadId(String threadId) { this.threadId = threadId; }

    public String getTurnId() { return turnId; }
    public void setTurnId(String turnId) { this.turnId = turnId; }

    public String getRunId() { return runId; }
    public void setRunId(String runId) { this.runId = runId; }

    public String getRunStatus() { return runStatus; }
    public void setRunStatus(String runStatus) { this.runStatus = runStatus; }

    public String getProtocolStatus() { return protocolStatus; }
    public void setProtocolStatus(String protocolStatus) { this.protocolStatus = protocolStatus; }

    public String getDispatchStatus() { return dispatchStatus; }
    public void setDispatchStatus(String dispatchStatus) { this.dispatchStatus = dispatchStatus; }

    public String getProtocolErrorMessage() { return protocolErrorMessage; }
    public void setProtocolErrorMessage(String protocolErrorMessage) {
        this.protocolErrorMessage = protocolErrorMessage;
    }
    public String getAnswerToQuestionId() { return answerToQuestionId; }
    public void setAnswerToQuestionId(String answerToQuestionId) {
        this.answerToQuestionId = answerToQuestionId;
    }

    public Long getMessageSeq() { return messageSeq; }
    public void setMessageSeq(Long messageSeq) { this.messageSeq = messageSeq; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    /** Internal model memory, never part of the user-visible message projection. */
    @JsonIgnore
    @JSONField(serialize = false)
    public String getRecoveryContext() { return recoveryContext; }
    public void setRecoveryContext(String recoveryContext) { this.recoveryContext = recoveryContext; }

    public Long getTimestamp() { return timestamp; }
    public void setTimestamp(Long timestamp) { this.timestamp = timestamp; }

    public String getAttachmentsJson() { return attachmentsJson; }
    public void setAttachmentsJson(String attachmentsJson) { this.attachmentsJson = attachmentsJson; }

    public String getNodesJson() { return nodesJson; }
    public void setNodesJson(String nodesJson) { this.nodesJson = nodesJson; }

    public String getReviewJson() { return reviewJson; }
    public void setReviewJson(String reviewJson) { this.reviewJson = reviewJson; }

    public String getPlanJson() { return planJson; }
    public void setPlanJson(String planJson) { this.planJson = planJson; }
}
