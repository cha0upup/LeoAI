package org.leo.core.entity;

/**
 * AI 工具调用中断确认请求。
 *
 * <p>当工具拦截逻辑检测到高影响工具调用时，
 * 将此对象放入状态队列，由 SSE 事件推送给前端。
 * 前端展示确认对话框，用户响应后通过 {@code /confirm} 接口解除阻塞，
 * 拦截器方能继续执行（或拒绝）该工具调用。
 *
 * <p>注意：对应的 {@code CompletableFuture<Boolean>} 不包含在此对象中，
 * 仅存储于服务端的 {@code pendingConfirmations} map 里，通过 {@link #callId} 关联。
 */
public class AiConfirmationRequest {

    /** 与工具调用一一对应的唯一标识，由拦截器生成，用于前端回调时定位挂起的 Future。 */
    private String callId;

    /** 工具名称，如 {@code "execonce"}。 */
    private String toolName;

    /** 工具所属类型，如 {@code "command"}，与会话授权类型保持一致。 */
    private String toolType;

    /** 工具类型中文标签，供前端展示，如 {@code "命令执行"}。 */
    private String toolTypeLabel;

    /** 工具参数的简要预览（截断后的 JSON），供用户判断操作内容。 */
    private String argsPreview;

    /** 该操作的影响面描述（中文），如 {@code "将在 puppet 侧执行系统命令…"}。 */
    private String impact;

    /** 请求创建时间，毫秒时间戳。 */
    private Long requestedAt;

    /** 等待确认超时时间，毫秒时间戳。 */
    private Long expiresAt;

    /** 确认等待时长，毫秒。 */
    private Long timeoutMs;

    public AiConfirmationRequest() {
    }

    public AiConfirmationRequest(String callId, String toolName, String toolType,
                                 String toolTypeLabel, String argsPreview, String impact) {
        this.callId = callId;
        this.toolName = toolName;
        this.toolType = toolType;
        this.toolTypeLabel = toolTypeLabel;
        this.argsPreview = argsPreview;
        this.impact = impact;
    }

    public AiConfirmationRequest(String callId, String toolName, String toolType,
                                 String toolTypeLabel, String argsPreview, String impact,
                                 Long requestedAt, Long expiresAt, Long timeoutMs) {
        this(callId, toolName, toolType, toolTypeLabel, argsPreview, impact);
        this.requestedAt = requestedAt;
        this.expiresAt = expiresAt;
        this.timeoutMs = timeoutMs;
    }

    public String getCallId()       { return callId; }
    public String getToolName()     { return toolName; }
    public String getToolType()     { return toolType; }
    public String getToolTypeLabel(){ return toolTypeLabel; }
    public String getArgsPreview()  { return argsPreview; }
    public String getImpact()       { return impact; }
    public Long getRequestedAt()     { return requestedAt; }
    public Long getExpiresAt()       { return expiresAt; }
    public Long getTimeoutMs()       { return timeoutMs; }

    public void setCallId(String callId)             { this.callId = callId; }
    public void setToolName(String toolName)         { this.toolName = toolName; }
    public void setToolType(String toolType)         { this.toolType = toolType; }
    public void setToolTypeLabel(String toolTypeLabel){ this.toolTypeLabel = toolTypeLabel; }
    public void setArgsPreview(String argsPreview)   { this.argsPreview = argsPreview; }
    public void setImpact(String impact)             { this.impact = impact; }
    public void setRequestedAt(Long requestedAt)      { this.requestedAt = requestedAt; }
    public void setExpiresAt(Long expiresAt)          { this.expiresAt = expiresAt; }
    public void setTimeoutMs(Long timeoutMs)          { this.timeoutMs = timeoutMs; }
}
