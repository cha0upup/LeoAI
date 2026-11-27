package org.leo.core.entity;

import java.util.UUID;

/**
 * AI 对话审计记录。
 *
 * <p>后台执行可以更新结果；查询方始终读取一次完整的不可变快照。
 */
public class AiChatAuditEntry {

    public enum SessionType { PLATFORM, AGENT }

    private static final int SUMMARY_LIMIT = 200;
    private volatile Snapshot snapshot;

    private AiChatAuditEntry(Snapshot snapshot) {
        this.snapshot = snapshot;
    }

    public static AiChatAuditEntry platform(String userId, String userName, String privilege,
                                            String message) {
        return create(SessionType.PLATFORM, null, userId, userName, privilege, message);
    }

    public static AiChatAuditEntry agent(String sessionId, String userId, String userName,
                                          String privilege, String message) {
        return create(SessionType.AGENT, sessionId, userId, userName, privilege, message);
    }

    private static AiChatAuditEntry create(SessionType type, String sessionId,
                                            String userId, String userName, String privilege,
                                            String message) {
        return new AiChatAuditEntry(new Snapshot(UUID.randomUUID().toString(), System.currentTimeMillis(),
                type, sessionId, userId, userName, privilege, summarize(message), 0, "pending", null, 0L));
    }

    public synchronized void complete(String reply, int toolCallCount, long durationMs) {
        snapshot = snapshot.withResult(toolCallCount, "ok", summarize(reply), durationMs);
    }

    public synchronized void fail(String errorMsg, long durationMs) {
        snapshot = snapshot.withResult(snapshot.toolCallCount(), "error", summarize(errorMsg), durationMs);
    }

    public Snapshot snapshot() {
        return snapshot;
    }

    private static String summarize(String text) {
        if (text == null) return "";
        return text.length() <= SUMMARY_LIMIT ? text : text.substring(0, SUMMARY_LIMIT) + "…";
    }

    public record Snapshot(String id, long timestamp, SessionType sessionType, String sessionId,
                           String userId, String userName, String privilege, String messageSummary,
                           int toolCallCount, String status, String replySummary, long durationMs) {
        private Snapshot withResult(int toolCallCount, String status, String replySummary, long durationMs) {
            return new Snapshot(id, timestamp, sessionType, sessionId, userId, userName, privilege,
                    messageSummary, toolCallCount, status, replySummary, durationMs);
        }
    }
}
