package org.leo.core.entity;

import com.alibaba.fastjson.annotation.JSONCreator;
import com.alibaba.fastjson.annotation.JSONField;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * AI 任务执行计划。
 *
 * <p>Agent 在开始多步任务前通过 {@code createPlan} 工具创建，
 * 执行过程中通过 {@code updatePlanStep} 工具实时更新步骤状态，
 * 全部步骤完成后通过 {@code completePlan} 写入最终结论。
 */
public class AiPlan {

    private final String planId;
    /** 任务标题（简短） */
    private final String title;
    /** 一句话目标 */
    private final String goal;
    /** 有序步骤列表 */
    private final List<AiPlanStep> steps;

    private volatile AiPlanStatus status = AiPlanStatus.PLANNING;
    /** completePlan 时写入的最终结论摘要 */
    private volatile String finalSummary;
    /** 单步骤超时时间（毫秒），0 表示不启用。超过此时间仍在执行的步骤将被自动标记为失败 */
    private volatile long stepTimeoutMs;

    private final long createdAt;
    private volatile long updatedAt;

    public AiPlan(String title, String goal, List<AiPlanStep> steps) {
        this.planId    = UUID.randomUUID().toString();
        this.title     = title != null ? title.trim() : "未命名计划";
        this.goal      = goal  != null ? goal.trim()  : "";
        this.steps     = steps != null ? new ArrayList<>(steps) : new ArrayList<>();
        this.createdAt = System.currentTimeMillis();
        this.updatedAt = this.createdAt;
    }

    /**
     * FastJSON 反序列化专用构造器：保留原 planId / createdAt，
     * 让重启后恢复的计划与持久化前的实例 id 一致。
     */
    @JSONCreator
    public AiPlan(@JSONField(name = "planId")    String planId,
                  @JSONField(name = "title")     String title,
                  @JSONField(name = "goal")      String goal,
                  @JSONField(name = "steps")     List<AiPlanStep> steps,
                  @JSONField(name = "createdAt") long createdAt) {
        this.planId    = planId != null && !planId.isBlank() ? planId : UUID.randomUUID().toString();
        this.title     = title != null ? title : "未命名计划";
        this.goal      = goal  != null ? goal  : "";
        this.steps     = steps != null ? new ArrayList<>(steps) : new ArrayList<>();
        this.createdAt = createdAt > 0 ? createdAt : System.currentTimeMillis();
        this.updatedAt = this.createdAt;
    }

    // ── 状态变更 ──────────────────────────────────────────────────────────────

    /**
     * 更新指定步骤为执行中。
     *
     * @param stepIndex 步骤序号（0-based）
     * @return 找到并更新返回 true，否则 false
     */
    public boolean startStep(int stepIndex) {
        AiPlanStep step = getStep(stepIndex);
        if (step == null) return false;
        if (this.status == AiPlanStatus.COMPLETED) return false;
        // 依赖步骤必须真实完成；FAILED/SKIPPED 不等价于满足依赖。
        if (step.getDependsOn() != null && !step.getDependsOn().isEmpty()) {
            for (int depIndex : step.getDependsOn()) {
                AiPlanStep dep = getStep(depIndex);
                if (dep == null || dep.getStatus() != AiStepStatus.COMPLETED) {
                    return false;
                }
            }
        }
        if (!step.markInProgress()) return false;
        this.status = AiPlanStatus.IN_PROGRESS;
        this.updatedAt = System.currentTimeMillis();
        return true;
    }

    /**
     * 更新指定步骤为已完成。
     *
     * @param stepIndex 步骤序号
     * @param result    关键发现摘要（可为 null）
     * @return 找到并更新返回 true，否则 false
     */
    public boolean completeStep(int stepIndex, String result) {
        AiPlanStep step = getStep(stepIndex);
        if (step == null) return false;
        String effectiveResult = result != null && !result.isBlank()
                ? result : step.getResult();
        if (step.getSuccessCriteria() != null
                && !step.getSuccessCriteria().isBlank()
                && (effectiveResult == null || effectiveResult.isBlank())) {
            return false;
        }
        if (!step.markCompleted(effectiveResult)) return false;
        reconcileStatus();
        this.updatedAt = System.currentTimeMillis();
        return true;
    }

    /**
     * 更新指定步骤为失败。若仍有待处理步骤，计划保持 IN_PROGRESS；
     * 所有步骤终结后，只要存在失败步骤，计划自动转为 FAILED。
     */
    public boolean failStep(int stepIndex, String reason) {
        AiPlanStep step = getStep(stepIndex);
        if (step == null) return false;
        if (!step.markFailed(reason)) return false;
        reconcileStatus();
        this.updatedAt = System.currentTimeMillis();
        return true;
    }

    /**
     * 更新指定步骤为已跳过。
     */
    public boolean skipStep(int stepIndex, String reason) {
        AiPlanStep step = getStep(stepIndex);
        if (step == null) return false;
        if (!step.markSkipped(reason)) return false;
        reconcileStatus();
        this.updatedAt = System.currentTimeMillis();
        return true;
    }

    /**
     * 完成整个计划，写入最终结论。
     */
    public void complete(String finalSummary) {
        if (!allStepsTerminated()) {
            throw new IllegalStateException("仍有未结束的计划步骤，不能完成计划");
        }
        this.finalSummary = finalSummary;
        this.status = hasFailedSteps()
                ? AiPlanStatus.FAILED : AiPlanStatus.COMPLETED;
        this.updatedAt    = System.currentTimeMillis();
    }

    /**
     * 将整个计划标记为失败。
     */
    public void fail(String reason) {
        this.finalSummary = reason;
        this.status       = AiPlanStatus.FAILED;
        this.updatedAt    = System.currentTimeMillis();
    }

    // ── 查询 ──────────────────────────────────────────────────────────────────

    public AiPlanStep getStep(int index) {
        for (AiPlanStep s : steps) {
            if (s.getIndex() == index) return s;
        }
        return null;
    }

    /** 是否所有步骤都已终结（COMPLETED / FAILED / SKIPPED）。 */
    public boolean allStepsTerminated() {
        for (AiPlanStep s : steps) {
            AiStepStatus st = s.getStatus();
            if (st == AiStepStatus.PENDING || st == AiStepStatus.IN_PROGRESS) return false;
        }
        return true;
    }

    /**
     * 检查所有 IN_PROGRESS 状态步骤是否超时，超时则自动标记为失败。
     * 在每次工具调用前由 Agent 调用，确保卡住的步骤不会无限等待。
     *
     * @return 因超时被自动失败的步骤数量
     */
    public int checkStepTimeouts() {
        if (stepTimeoutMs <= 0) return 0;
        long now = System.currentTimeMillis();
        int timedOut = 0;
        for (AiPlanStep s : steps) {
            if (s.getStatus() != AiStepStatus.IN_PROGRESS) continue;
            long elapsed = now - s.getStartedAt();
            if (elapsed > stepTimeoutMs) {
                if (s.markFailed("步骤超时（已执行 " + elapsed
                        + "ms，超过阈值 " + stepTimeoutMs + "ms）")) {
                    timedOut++;
                }
            }
        }
        if (timedOut > 0) {
            this.updatedAt = now;
            reconcileStatus();
        }
        return timedOut;
    }

    /** 设置步骤超时时间（毫秒），0 表示不启用。 */
    public void setStepTimeoutMs(long stepTimeoutMs) {
        this.stepTimeoutMs = Math.max(0L, stepTimeoutMs);
    }

    private void reconcileStatus() {
        boolean hasActive = steps.stream().anyMatch(step ->
                step.getStatus() == AiStepStatus.PENDING
                        || step.getStatus() == AiStepStatus.IN_PROGRESS);
        if (hasActive) {
            this.status = AiPlanStatus.IN_PROGRESS;
        } else {
            this.status = hasFailedSteps()
                    ? AiPlanStatus.FAILED : AiPlanStatus.COMPLETED;
        }
    }

    private boolean hasFailedSteps() {
        return steps.stream().anyMatch(
                step -> step.getStatus() == AiStepStatus.FAILED);
    }

    // ── Getters ───────────────────────────────────────────────────────────────

    public String        getPlanId()       { return planId; }
    public String        getTitle()        { return title; }
    public String        getGoal()         { return goal; }
    public AiPlanStatus  getStatus()       { return status; }
    public String        getFinalSummary() { return finalSummary; }
    public long          getCreatedAt()    { return createdAt; }
    public long          getUpdatedAt()    { return updatedAt; }
    public long          getStepTimeoutMs(){ return stepTimeoutMs; }

    public List<AiPlanStep> getSteps() {
        return Collections.unmodifiableList(steps);
    }
}
