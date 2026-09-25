package org.leo.core.session;

import org.leo.core.ai.AiRuntimeState;

/** 单条 Puppet AI 对话线程；执行状态由统一的 {@link AiRuntimeState} 提供。 */
public class AiThread extends AiRuntimeState {

    private final String threadId;
    private volatile String title;
    private final long createdAt;
    private volatile long lastActiveAt;
    private volatile Integer aiConfigId;
    private volatile String parentThreadId;

    public AiThread(String threadId, String title) {
        this(threadId, title, System.currentTimeMillis(), 0L);
    }

    public AiThread(String threadId, String title, long createdAt, long lastActiveAt) {
        this.threadId = threadId;
        this.title = title;
        this.createdAt = createdAt > 0 ? createdAt : System.currentTimeMillis();
        this.lastActiveAt = lastActiveAt > 0 ? lastActiveAt : this.createdAt;
    }

    public String getThreadId() { return threadId; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public long getCreatedAt() { return createdAt; }
    public long getLastActiveAt() { return lastActiveAt; }
    public void touchLastActiveAt() { lastActiveAt = System.currentTimeMillis(); }
    public Integer getAiConfigId() { return aiConfigId; }
    public void setAiConfigId(Integer aiConfigId) { this.aiConfigId = aiConfigId; }
    public String getParentThreadId() { return parentThreadId; }
    public void setParentThreadId(String parentThreadId) { this.parentThreadId = parentThreadId; }

    public void stop() { stop("用户手动停止"); }
}
