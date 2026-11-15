package org.leo.ai.agent;

import org.leo.core.entity.AiExecutionPolicy;

/**
 * ThreadLocal 工具执行上下文。
 *
 * <p>在工具方法执行期间持有当前 sessionId / threadId / planStepIndex，
 * 替代每个 @Tool 方法上重复的参数声明。
 *
 * <p>生命周期由 {@link AiAgentFactory} 注入的 {@code beforeToolExecution} / {@code afterToolExecution}
 * 钩子管理：工具线程启动前设置，工具执行完毕后清除（finally 保证）。
 */
public final class AiToolContext {

    /** 跨执行器传播工具上下文所需的最小快照。 */
    public record Snapshot(String sessionId, String threadId,
                           int planStepIndex,
                           AiExecutionPolicy executionPolicy,
                           AiToolDescriptor toolDescriptor) {}

    private static final Snapshot EMPTY = new Snapshot(null, null, -1, null, null);
    private static final ThreadLocal<Snapshot> HOLDER = new ThreadLocal<>();

    private AiToolContext() {}

    // ── 设置 / 清除 ──────────────────────────────────────────────────────────

    /** 从 memoryId 解析 sessionId + threadId。 */
    public static void setFromMemoryId(Object memoryId) {
        String value = memoryId != null ? String.valueOf(memoryId) : "";
        int sep = value.indexOf(':');
        String sessionId = sep > 0 ? value.substring(0, sep) : value;
        String threadId  = (sep > 0 && sep < value.length() - 1) ? value.substring(sep + 1) : null;
        Snapshot current = current();
        HOLDER.set(new Snapshot(
                sessionId.isBlank() ? null : sessionId,
                threadId != null && threadId.isBlank() ? null : threadId,
                current.planStepIndex(), current.executionPolicy(), current.toolDescriptor()));
    }

    /** 清除当前线程所有上下文。在 afterToolExecution 钩子中调用。 */
    public static void clear() {
        HOLDER.remove();
    }

    public static Snapshot capture() {
        Snapshot current = current();
        return new Snapshot(
                current.sessionId(), current.threadId(), current.planStepIndex(),
                getExecutionPolicy(), getToolDescriptor());
    }

    public static void restore(Snapshot snapshot) {
        if (snapshot == null) {
            clear();
            return;
        }
        HOLDER.set(new Snapshot(snapshot.sessionId(), snapshot.threadId(),
                Math.max(-1, snapshot.planStepIndex()), snapshot.executionPolicy(), snapshot.toolDescriptor()));
    }

    // ── 基本字段 ─────────────────────────────────────────────────────────────

    public static String getSessionId() {
        return current().sessionId();
    }

    public static String getThreadId() {
        return current().threadId();
    }

    public static String requireSessionId() {
        String id = getSessionId();
        if (id == null || id.isBlank()) {
            throw new IllegalStateException(
                    "AiToolContext.sessionId 未设置。请确认 AiAgentFactory 已配置 beforeToolExecution 钩子。");
        }
        return id;
    }

    public static void setExecutionPolicy(AiExecutionPolicy policy) {
        Snapshot current = current();
        HOLDER.set(new Snapshot(current.sessionId(), current.threadId(), current.planStepIndex(),
                policy, current.toolDescriptor()));
    }

    public static AiExecutionPolicy getExecutionPolicy() {
        AiExecutionPolicy policy = current().executionPolicy();
        return policy != null ? policy : AiExecutionPolicy.defaultPolicy();
    }

    public static AiExecutionPolicy requireExecutionPolicy() {
        AiExecutionPolicy policy = current().executionPolicy();
        if (policy == null || policy.getUserId() == null || policy.getUserId().isBlank()) {
            throw new SecurityException("AI 工具调用缺少已认证的执行身份");
        }
        return policy;
    }

    public static void setToolDescriptor(AiToolDescriptor descriptor) {
        Snapshot current = current();
        HOLDER.set(new Snapshot(current.sessionId(), current.threadId(), current.planStepIndex(),
                current.executionPolicy(), descriptor));
    }

    public static AiToolDescriptor getToolDescriptor() {
        return current().toolDescriptor();
    }

    // ── Plan 关联 ────────────────────────────────────────────────────────────

    /** 设置当前工具调用所属的 plan 步骤索引。 */
    public static void setPlanStepIndex(int stepIndex) {
        Snapshot current = current();
        HOLDER.set(new Snapshot(current.sessionId(), current.threadId(), Math.max(-1, stepIndex),
                current.executionPolicy(), current.toolDescriptor()));
    }

    /** 获取当前工具调用所属的 plan 步骤索引，-1 表示无关联。 */
    public static int getPlanStepIndex() {
        return current().planStepIndex();
    }

    private static Snapshot current() {
        Snapshot snapshot = HOLDER.get();
        return snapshot != null ? snapshot : EMPTY;
    }
}
