package org.leo.ai.platform;

import org.leo.core.ai.AiRuntimeState;

/** 平台 AI 对话状态；与 Node AI 共用同一运行时状态机。 */
public class PlatformAiState extends AiRuntimeState {

    private final String stateId;
    private final long createdAt;
    private volatile long lastActiveAt;
    private volatile Integer aiConfigId;

    public PlatformAiState(String stateId) {
        this.stateId = stateId;
        this.createdAt = System.currentTimeMillis();
        this.lastActiveAt = createdAt;
    }

    public String getStateId() { return stateId; }
    public long getCreatedAt() { return createdAt; }
    public long getLastActiveAt() { return lastActiveAt; }
    public void touchLastActiveAt() { lastActiveAt = System.currentTimeMillis(); }
    public Integer getAiConfigId() { return aiConfigId; }
    public void setAiConfigId(Integer aiConfigId) { this.aiConfigId = aiConfigId; }

    public void stopGeneration() { stopGeneration("用户手动停止"); }

    public void stopGeneration(String reason) {
        stop(reason);
        offerWarnMessage(getStopReason());
    }

}
