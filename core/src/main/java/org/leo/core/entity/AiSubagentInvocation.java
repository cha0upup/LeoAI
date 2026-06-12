package org.leo.core.entity;

/**
 * 子 Agent 调用记录。父会话中通过 dispatchSubAgent 工具派发出隔离上下文的子会话时，
 * 用本记录跟踪「父-子」关系、任务描述、最终摘要。
 *
 * <p>状态机：{@code pending → running → completed | failed | cancelled}</p>
 */
public class AiSubagentInvocation {

    public static final String STATUS_PENDING = "pending";
    public static final String STATUS_RUNNING = "running";
    public static final String STATUS_COMPLETED = "completed";
    public static final String STATUS_FAILED = "failed";
    public static final String STATUS_CANCELLED = "cancelled";

    private String invocationId;
    private String parentThreadId;
    /** 父会话中触发本次派发的 assistant 消息 ID（可为 null）。 */
    private String parentMessageId;
    /** 实际执行的子会话 ID（创建后回填）。 */
    private String childThreadId;
    private String task;
    /** 任务输入数据 JSON（可选，给子 Agent 的额外参考材料）。 */
    private String inputJson;
    /** 子 Agent 完成后产出的摘要，会注入回父 Agent 的工具返回值。 */
    private String summary;
    private String status;
    private Long createdAt;
    private Long completedAt;

    public String getInvocationId() { return invocationId; }
    public void setInvocationId(String invocationId) { this.invocationId = invocationId; }

    public String getParentThreadId() { return parentThreadId; }
    public void setParentThreadId(String parentThreadId) { this.parentThreadId = parentThreadId; }

    public String getParentMessageId() { return parentMessageId; }
    public void setParentMessageId(String parentMessageId) { this.parentMessageId = parentMessageId; }

    public String getChildThreadId() { return childThreadId; }
    public void setChildThreadId(String childThreadId) { this.childThreadId = childThreadId; }

    public String getTask() { return task; }
    public void setTask(String task) { this.task = task; }

    public String getInputJson() { return inputJson; }
    public void setInputJson(String inputJson) { this.inputJson = inputJson; }

    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public Long getCreatedAt() { return createdAt; }
    public void setCreatedAt(Long createdAt) { this.createdAt = createdAt; }

    public Long getCompletedAt() { return completedAt; }
    public void setCompletedAt(Long completedAt) { this.completedAt = completedAt; }
}
