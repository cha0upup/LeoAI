package org.leo.core.entity;

import com.alibaba.fastjson.JSON;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Agent 在继续执行前向用户发出的结构化输入请求。 */
public class AiUserInputRequest {

    public static final String TYPE_CLARIFICATION = "CLARIFICATION";
    public static final String TYPE_CONFIRMATION = "CONFIRMATION";

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_ANSWERED = "ANSWERED";
    public static final String STATUS_CANCELLED = "CANCELLED";
    public static final String STATUS_EXPIRED = "EXPIRED";

    private String requestId;
    private String threadId;
    private String turnId;
    private String itemId;
    private String requestType;
    private String prompt;
    private String optionsJson;
    private Boolean allowFreeText;
    private String actionSummary;
    private String toolName;
    private String argumentsHash;
    private String risk;
    private String status;
    private String answer;
    private Long createdAt;
    private Long answeredAt;
    /** 确认凭证被高风险工具消费的时间；普通澄清始终为空。 */
    private Long confirmationConsumedAt;
    private Long expiresAt;

    public List<AiUserInputOption> options() {
        if (optionsJson == null || optionsJson.isBlank()) return Collections.emptyList();
        List<AiUserInputOption> values = JSON.parseArray(optionsJson, AiUserInputOption.class);
        return values != null ? values : Collections.emptyList();
    }

    public List<String> optionValues() {
        return options().stream()
                .map(AiUserInputOption::getValue)
                .filter(value -> value != null && !value.isBlank())
                .toList();
    }

    public Map<String, Object> toMap() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("kind", "user_input");
        value.put("questionId", requestId);
        value.put("type", requestType);
        value.put("prompt", prompt);
        value.put("options", options().stream().map(option -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("label", option.getLabel());
            item.put("value", option.getValue());
            item.put("intent", option.getIntent());
            return item;
        }).toList());
        value.put("allowFreeText", Boolean.TRUE.equals(allowFreeText));
        value.put("actionSummary", actionSummary);
        value.put("toolName", toolName);
        value.put("argumentsHash", argumentsHash);
        value.put("risk", risk);
        value.put("status", status != null ? status.toLowerCase(Locale.ROOT) : null);
        value.put("answer", answer);
        value.put("createdAt", createdAt);
        value.put("answeredAt", answeredAt);
        value.put("confirmationConsumedAt", confirmationConsumedAt);
        value.put("expiresAt", expiresAt);
        return value;
    }

    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
    public String getThreadId() { return threadId; }
    public void setThreadId(String threadId) { this.threadId = threadId; }
    public String getTurnId() { return turnId; }
    public void setTurnId(String turnId) { this.turnId = turnId; }
    public String getItemId() { return itemId; }
    public void setItemId(String itemId) { this.itemId = itemId; }
    public String getRequestType() { return requestType; }
    public void setRequestType(String requestType) { this.requestType = requestType; }
    public String getPrompt() { return prompt; }
    public void setPrompt(String prompt) { this.prompt = prompt; }
    public String getOptionsJson() { return optionsJson; }
    public void setOptionsJson(String optionsJson) { this.optionsJson = optionsJson; }
    public Boolean getAllowFreeText() { return allowFreeText; }
    public void setAllowFreeText(Boolean allowFreeText) { this.allowFreeText = allowFreeText; }
    public String getActionSummary() { return actionSummary; }
    public void setActionSummary(String actionSummary) { this.actionSummary = actionSummary; }
    public String getToolName() { return toolName; }
    public void setToolName(String toolName) { this.toolName = toolName; }
    public String getArgumentsHash() { return argumentsHash; }
    public void setArgumentsHash(String argumentsHash) { this.argumentsHash = argumentsHash; }
    public String getRisk() { return risk; }
    public void setRisk(String risk) { this.risk = risk; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getAnswer() { return answer; }
    public void setAnswer(String answer) { this.answer = answer; }
    public Long getCreatedAt() { return createdAt; }
    public void setCreatedAt(Long createdAt) { this.createdAt = createdAt; }
    public Long getAnsweredAt() { return answeredAt; }
    public void setAnsweredAt(Long answeredAt) { this.answeredAt = answeredAt; }
    public Long getConfirmationConsumedAt() { return confirmationConsumedAt; }
    public void setConfirmationConsumedAt(Long confirmationConsumedAt) {
        this.confirmationConsumedAt = confirmationConsumedAt;
    }
    public Long getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Long expiresAt) { this.expiresAt = expiresAt; }
}
