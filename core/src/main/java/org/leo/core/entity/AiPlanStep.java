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

    private volatile AiStepStatus status = AiStepStatus.PENDING;
    /** 步骤完成后的关键发现摘要（简短） */
    private volatile String result;
    /** 失败或跳过时的原因 */
    private volatile String reason;
    /** 预批准标记：为 true 时该步骤内的高影响工具调用跳过用户确认 */
    private volatile boolean preApproved;

    private volatile long startedAt;
    private volatile long completedAt;

    /**
     * 原始构造函数，保持向后兼容性。
     */
    public AiPlanStep(int index, String description, String toolHint, boolean parallel) {
        this(index, description, toolHint, parallel, null, 1, null);
    }

    /**
     * 完整构造函数，包括新增的可选字段。
     * 同时被 FastJSON 用于反序列化（{@link JSONCreator} + {@link JSONField}），
     * 让 plan 快照可以从持久化 JSON 还原。volatile 状态字段（status/result/preApproved 等）
     * 由 FastJSON 通过反射直接写入。
     */
    @JSONCreator
    public AiPlanStep(@JSONField(name = "index") int index,
                      @JSONField(name = "description") String description,
                      @JSONField(name = "toolHint") String toolHint,
                      @JSONField(name = "parallel") boolean parallel,
                      @JSONField(name = "successCriteria") String successCriteria,
                      @JSONField(name = "maxRetries") int maxRetries,
                      @JSONField(name = "dependsOn") List<Integer> dependsOn) {
        this.index             = index;
        this.description       = description;
        this.toolHint          = toolHint;
        this.parallel          = parallel;
        this.successCriteria   = successCriteria;
        this.maxRetries        = maxRetries > 0 ? maxRetries : 1;
        this.dependsOn         = dependsOn;
    }

    // ── 状态变更 ──────────────────────────────────────────────────────────────

    public void markInProgress() {
        this.status    = AiStepStatus.IN_PROGRESS;
        this.startedAt = System.currentTimeMillis();
    }

    public void markCompleted(String result) {
        this.status      = AiStepStatus.COMPLETED;
        this.result      = result;
        this.completedAt = System.currentTimeMillis();
    }

    public void markFailed(String reason) {
        this.status      = AiStepStatus.FAILED;
        this.reason      = reason;
        this.completedAt = System.currentTimeMillis();
    }

    public void markSkipped(String reason) {
        this.status      = AiStepStatus.SKIPPED;
        this.reason      = reason;
        this.completedAt = System.currentTimeMillis();
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
    public boolean      isPreApproved()       { return preApproved; }
    public long         getStartedAt()        { return startedAt; }
    public long         getCompletedAt()      { return completedAt; }

    // ── 预批准 ───────────────────────────────────────────────────────────────

    public void setPreApproved(boolean preApproved) { this.preApproved = preApproved; }
}
