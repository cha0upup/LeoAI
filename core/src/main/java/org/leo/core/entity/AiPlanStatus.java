package org.leo.core.entity;

/**
 * AI 任务计划整体状态。
 */
public enum AiPlanStatus {
    /** 计划已创建，尚未开始执行 */
    PLANNING,
    /** 正在执行中 */
    IN_PROGRESS,
    /** 所有步骤完成，已输出最终结论 */
    COMPLETED,
    /** 执行过程中出现不可恢复的失败 */
    FAILED
}
