package org.leo.ai.runtime;

import org.leo.core.ai.AiRunStatus;

/** AI 单轮执行终态；运行状态使用核心契约，不依赖枚举名称或系统语言。 */
public enum AiTurnOutcome {
    COMPLETED,
    FAILED,
    CANCELLED;

    public String runtimeStatus() {
        return switch (this) {
            case COMPLETED -> AiRunStatus.COMPLETED;
            case CANCELLED -> AiRunStatus.CANCELLED;
            case FAILED -> AiRunStatus.FAILED;
        };
    }
}
