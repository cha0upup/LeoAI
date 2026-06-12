package org.leo.core.entity;

import java.util.UUID;

/**
 * AI 对话审计记录。
 *
 * <p>记录每一次 AI Chat 请求的关键信息，包含操作人、会话来源、消息摘要、
 * 工具调用数量和最终结果，供管理员事后溯源。
 */
public class AiChatAuditEntry {

    public enum SessionType { PLATFORM, PUPPET }

    /** 记录 ID（epoch 毫秒 + 随机后缀） */
    private String id;
    /** 记录时间戳（毫秒） */
    private long timestamp;
    /** 会话类型 */
    private SessionType sessionType;
    /** Puppet 会话 ID（PUPPET 类型时有值） */
    private String sessionId;
    /** 用户 ID */
    private String userId;
    /** 用户名 */
    private String userName;
    /** 用户角色 */
    private String privilege;
    /** 用户消息摘要（最多 200 字符） */
    private String messageSummary;
    /** 本轮触发的工具调用数 */
    private int toolCallCount;
    /** 是否为确认执行请求 */
    private boolean confirmed;
    /** 结果状态：ok / error */
    private String status;
    /** AI 回复摘要（最多 200 字符）或错误信息 */
    private String replySummary;
    /** 耗时（毫秒） */
    private long durationMs;

    public AiChatAuditEntry() {
    }

    // ─── Builder-style factory ────────────────────────────────────────────────

    public static AiChatAuditEntry platform(String userId, String userName, String privilege,
                                            String message, boolean confirmed) {
        return create(SessionType.PLATFORM, null, userId, userName, privilege, message, confirmed);
    }

    public static AiChatAuditEntry puppet(String sessionId, String userId, String userName,
                                          String privilege, String message, boolean confirmed) {
        return create(SessionType.PUPPET, sessionId, userId, userName, privilege, message, confirmed);
    }

    private static AiChatAuditEntry create(SessionType type, String sessionId,
                                            String userId, String userName, String privilege,
                                            String message, boolean confirmed) {
        AiChatAuditEntry e = new AiChatAuditEntry();
        e.id = UUID.randomUUID().toString();
        e.timestamp = System.currentTimeMillis();
        e.sessionType = type;
        e.sessionId = sessionId;
        e.userId = userId;
        e.userName = userName;
        e.privilege = privilege;
        e.messageSummary = truncate(message, 200);
        e.confirmed = confirmed;
        e.status = "pending";
        return e;
    }

    public void complete(String reply, int toolCallCount, long durationMs) {
        this.replySummary = truncate(reply, 200);
        this.toolCallCount = toolCallCount;
        this.durationMs = durationMs;
        this.status = "ok";
    }

    public void fail(String errorMsg, long durationMs) {
        this.replySummary = truncate(errorMsg, 200);
        this.durationMs = durationMs;
        this.status = "error";
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "…";
    }

    // ─── Getters ──────────────────────────────────────────────────────────────

    public String getId() { return id; }
    public long getTimestamp() { return timestamp; }
    public SessionType getSessionType() { return sessionType; }
    public String getSessionId() { return sessionId; }
    public String getUserId() { return userId; }
    public String getUserName() { return userName; }
    public String getPrivilege() { return privilege; }
    public String getMessageSummary() { return messageSummary; }
    public int getToolCallCount() { return toolCallCount; }
    public boolean isConfirmed() { return confirmed; }
    public String getStatus() { return status; }
    public String getReplySummary() { return replySummary; }
    public long getDurationMs() { return durationMs; }
}
