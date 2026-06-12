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

    /** 将计划状态推进到执行中（首次调用 updatePlanStep start 时触发）。 */
    public void markInProgress() {
        if (this.status == AiPlanStatus.PLANNING) {
            this.status    = AiPlanStatus.IN_PROGRESS;
            this.updatedAt = System.currentTimeMillis();
        }
    }

    /**
     * 更新指定步骤为执行中。
     *
     * @param stepIndex 步骤序号（0-based）
     * @return 找到并更新返回 true，否则 false
     */
    public boolean startStep(int stepIndex) {
        AiPlanStep step = findStep(stepIndex);
        if (step == null) return false;
        // 检查依赖步骤是否已终结
        if (step.getDependsOn() != null && !step.getDependsOn().isEmpty()) {
            for (int depIndex : step.getDependsOn()) {
                AiPlanStep dep = findStep(depIndex);
                if (dep != null) {
                    AiStepStatus depStatus = dep.getStatus();
                    if (depStatus == AiStepStatus.PENDING || depStatus == AiStepStatus.IN_PROGRESS) {
                        return false; // 依赖步骤未完成，不能启动
                    }
                }
            }
        }
        step.markInProgress();
        markInProgress();
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
        AiPlanStep step = findStep(stepIndex);
        if (step == null) return false;
        step.markCompleted(result);
        this.updatedAt = System.currentTimeMillis();
        return true;
    }

    /**
     * 更新指定步骤为失败。
     */
    public boolean failStep(int stepIndex, String reason) {
        AiPlanStep step = findStep(stepIndex);
        if (step == null) return false;
        step.markFailed(reason);
        this.status    = AiPlanStatus.FAILED;
        this.updatedAt = System.currentTimeMillis();
        return true;
    }

    /**
     * 更新指定步骤为已跳过。
     */
    public boolean skipStep(int stepIndex, String reason) {
        AiPlanStep step = findStep(stepIndex);
        if (step == null) return false;
        step.markSkipped(reason);
        this.updatedAt = System.currentTimeMillis();
        return true;
    }

    /**
     * 完成整个计划，写入最终结论。
     */
    public void complete(String finalSummary) {
        this.finalSummary = finalSummary;
        this.status       = AiPlanStatus.COMPLETED;
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

    private AiPlanStep findStep(int index) {
        for (AiPlanStep s : steps) {
            if (s.getIndex() == index) return s;
        }
        return null;
    }

    /** 返回第一个 PENDING 步骤的序号，全部完成时返回 -1。 */
    public int nextPendingStepIndex() {
        for (AiPlanStep s : steps) {
            if (s.getStatus() == AiStepStatus.PENDING) return s.getIndex();
        }
        return -1;
    }

    /**
     * 当前是否有正在执行的且已预批准的步骤。
     * 用于工具确认拦截器判断是否跳过确认。
     */
    public boolean hasPreApprovedActiveStep() {
        for (AiPlanStep s : steps) {
            if (s.getStatus() == AiStepStatus.IN_PROGRESS && s.isPreApproved()) return true;
        }
        return false;
    }

    /**
     * 设置指定步骤的预批准状态。
     *
     * @return 找到步骤返回 true，否则 false
     */
    public boolean setStepPreApproved(int stepIndex, boolean preApproved) {
        AiPlanStep step = findStep(stepIndex);
        if (step == null) return false;
        step.setPreApproved(preApproved);
        this.updatedAt = System.currentTimeMillis();
        return true;
    }

    /** 预批准所有尚未终结的步骤。 */
    public int preApproveAllPending() {
        int count = 0;
        for (AiPlanStep s : steps) {
            AiStepStatus st = s.getStatus();
            if ((st == AiStepStatus.PENDING || st == AiStepStatus.IN_PROGRESS) && !s.isPreApproved()) {
                s.setPreApproved(true);
                count++;
            }
        }
        if (count > 0) this.updatedAt = System.currentTimeMillis();
        return count;
    }

    /** 是否所有步骤都已终结（COMPLETED / FAILED / SKIPPED）。 */
    public boolean allStepsTerminated() {
        for (AiPlanStep s : steps) {
            AiStepStatus st = s.getStatus();
            if (st == AiStepStatus.PENDING || st == AiStepStatus.IN_PROGRESS) return false;
        }
        return true;
    }

    // ── Getters ───────────────────────────────────────────────────────────────

    public String        getPlanId()       { return planId; }
    public String        getTitle()        { return title; }
    public String        getGoal()         { return goal; }
    public AiPlanStatus  getStatus()       { return status; }
    public String        getFinalSummary() { return finalSummary; }
    public long          getCreatedAt()    { return createdAt; }
    public long          getUpdatedAt()    { return updatedAt; }

    public List<AiPlanStep> getSteps() {
        return Collections.unmodifiableList(steps);
    }
}
