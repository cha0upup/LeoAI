package org.leo.core.entity;

/**
 * AI 任务计划单步状态。
 */
public enum AiStepStatus {
    /** 等待执行 */
    PENDING,
    /** 正在执行 */
    IN_PROGRESS,
    /** 执行成功 */
    COMPLETED,
    /** 执行失败 */
    FAILED,
    /** 已跳过（前置条件不满足或不再需要） */
    SKIPPED
}
