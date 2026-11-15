package org.leo.core.entity;

import com.alibaba.fastjson.annotation.JSONCreator;
import com.alibaba.fastjson.annotation.JSONField;

import java.util.List;

/**
 * AI 任务计划中的单个执行步骤。
 */
public class AiPlanStep {

    /** 步骤序号，从 0 开始 */
    private final int index;
    /** 步骤描述 */
    private final String description;
    /** 预计使用的工具或子 Agent（可为 null） */
    private final String toolHint;
    /** 是否可与其他步骤并行执行 */
    private final boolean parallel;
    /** 该步骤成功的判定标准（可为 null） */
    private final String successCriteria;
    /** 该步骤的最大重试次数（默认 1） */
    private final int maxRetries;
    /** 该步骤依赖的其他步骤的索引列表（可为 null） */
    private final List<Integer> dependsOn;

    private volatile AiStepStatus status;
    /** 步骤完成后的关键发现摘要（简短） */
    private volatile String result;
    /** 失败或跳过时的原因 */
    private volatile String reason;
    /** 已开始执行的次数；首次执行计为 1，后续每次重试递增。 */
    private volatile int attemptCount;
    private volatile long startedAt;
    private volatile long completedAt;

    public AiPlanStep(int index, String description, String toolHint,
                      boolean parallel, String successCriteria,
                      int maxRetries, List<Integer> dependsOn) {
        this(index, description, toolHint, parallel, successCriteria, maxRetries, dependsOn,
                AiStepStatus.PENDING, null, null, 0, 0L, 0L);
    }

    /**
     * FastJSON 快照构造器：创建参数与运行状态一同恢复，
     * 保留步骤结果、重试次数和时间，避免重启后重复执行已完成步骤。
     */
    @JSONCreator
    public AiPlanStep(@JSONField(name = "index") int index,
                      @JSONField(name = "description") String description,
                      @JSONField(name = "toolHint") String toolHint,
                      @JSONField(name = "parallel") boolean parallel,
                      @JSONField(name = "successCriteria") String successCriteria,
                      @JSONField(name = "maxRetries") int maxRetries,
                      @JSONField(name = "dependsOn") List<Integer> dependsOn,
                      @JSONField(name = "status") AiStepStatus status,
                      @JSONField(name = "result") String result,
                      @JSONField(name = "reason") String reason,
                      @JSONField(name = "attemptCount") int attemptCount,
                      @JSONField(name = "startedAt") long startedAt,
                      @JSONField(name = "completedAt") long completedAt) {
        this.index             = index;
        this.description       = description;
        this.toolHint          = toolHint;
        this.parallel          = parallel;
        this.successCriteria   = successCriteria;
        this.maxRetries        = maxRetries > 0 ? maxRetries : 1;
        this.dependsOn         = dependsOn;
        this.status            = status != null ? status : AiStepStatus.PENDING;
        this.result            = result;
        this.reason            = reason;
        this.attemptCount      = attemptCount;
        this.startedAt         = startedAt;
        this.completedAt       = completedAt;
    }

    // ── 状态变更 ──────────────────────────────────────────────────────────────

    public boolean markInProgress() {
        if (!canStart()) return false;
        boolean retry = this.status == AiStepStatus.FAILED;
        this.status    = AiStepStatus.IN_PROGRESS;
        this.attemptCount++;
        if (retry) this.result = null;
        this.reason = null;
        this.completedAt = 0L;
        this.startedAt = System.currentTimeMillis();
        return true;
    }

    public boolean markCompleted(String result) {
        if (this.status != AiStepStatus.IN_PROGRESS) return false;
        this.status      = AiStepStatus.COMPLETED;
        this.result      = result;
        this.reason      = null;
        this.completedAt = System.currentTimeMillis();
        return true;
    }

    public boolean markFailed(String reason) {
        if (this.status != AiStepStatus.IN_PROGRESS) return false;
        this.status      = AiStepStatus.FAILED;
        this.reason      = reason;
        this.completedAt = System.currentTimeMillis();
        return true;
    }

    public boolean markSkipped(String reason) {
        if (this.status != AiStepStatus.PENDING
                && this.status != AiStepStatus.IN_PROGRESS) {
            return false;
        }
        this.status      = AiStepStatus.SKIPPED;
        this.reason      = reason;
        this.completedAt = System.currentTimeMillis();
        return true;
    }

    /** 是否可以首次启动或在失败后重试。 */
    public boolean canStart() {
        return this.status == AiStepStatus.PENDING
                || (this.status == AiStepStatus.FAILED
                && this.attemptCount <= this.maxRetries);
    }

    // ── Getters ───────────────────────────────────────────────────────────────

    public int          getIndex()            { return index; }
    public String       getDescription()      { return description; }
    public String       getToolHint()         { return toolHint; }
    public boolean      isParallel()          { return parallel; }
    public String       getSuccessCriteria()  { return successCriteria; }
    public int          getMaxRetries()       { return maxRetries; }
    public List<Integer> getDependsOn()       { return dependsOn; }
    public AiStepStatus getStatus()           { return status; }
    public String       getResult()           { return result; }
    public String       getReason()           { return reason; }
    public int          getAttemptCount()     { return attemptCount; }
    public long         getStartedAt()        { return startedAt; }
    public long         getCompletedAt()      { return completedAt; }

    /** 追加/更新步骤结果摘要（不改变步骤状态）。 */
    public void setResult(String result) { this.result = result; }
}
