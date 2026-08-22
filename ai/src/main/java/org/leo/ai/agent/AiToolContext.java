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

    private record Ctx(String sessionId, String threadId) {}

    /** 跨执行器传播工具上下文所需的最小快照。 */
    public record Snapshot(String sessionId, String threadId,
                           int planStepIndex,
                           AiExecutionPolicy executionPolicy,
                           AiToolDescriptor toolDescriptor) {}

    private static final ThreadLocal<Ctx> HOLDER = new ThreadLocal<>();
    private static final ThreadLocal<Integer> PLAN_STEP_INDEX = new ThreadLocal<>();
    private static final ThreadLocal<AiExecutionPolicy> EXECUTION_POLICY = new ThreadLocal<>();
    private static final ThreadLocal<AiToolDescriptor> TOOL_DESCRIPTOR = new ThreadLocal<>();

    private AiToolContext() {}

    // ── 设置 / 清除 ──────────────────────────────────────────────────────────

    /** 从 memoryId 解析 sessionId + threadId。 */
    public static void setFromMemoryId(Object memoryId) {
        if (memoryId == null) {
            HOLDER.remove();
            return;
        }
        String value = String.valueOf(memoryId);
        int sep = value.indexOf(':');
        String sessionId = sep > 0 ? value.substring(0, sep) : value;
        String threadId  = (sep > 0 && sep < value.length() - 1) ? value.substring(sep + 1) : null;
        HOLDER.set(new Ctx(
                sessionId.isBlank() ? null : sessionId,
                threadId != null && threadId.isBlank() ? null : threadId));
    }

    /** 清除当前线程所有上下文。在 afterToolExecution 钩子中调用。 */
    public static void clear() {
        HOLDER.remove();
        PLAN_STEP_INDEX.remove();
        EXECUTION_POLICY.remove();
        TOOL_DESCRIPTOR.remove();
    }

    public static Snapshot capture() {
        Ctx ctx = HOLDER.get();
        return new Snapshot(
                ctx != null ? ctx.sessionId() : null,
                ctx != null ? ctx.threadId() : null,
                getPlanStepIndex(),
                getExecutionPolicy(), getToolDescriptor());
    }

    public static void restore(Snapshot snapshot) {
        clear();
        if (snapshot == null) return;
        if (snapshot.sessionId() != null || snapshot.threadId() != null) {
            HOLDER.set(new Ctx(snapshot.sessionId(), snapshot.threadId()));
        }
        setPlanStepIndex(snapshot.planStepIndex());
        setExecutionPolicy(snapshot.executionPolicy());
        setToolDescriptor(snapshot.toolDescriptor());
    }

    // ── 基本字段 ─────────────────────────────────────────────────────────────

    public static String getSessionId() {
        Ctx ctx = HOLDER.get();
        return ctx != null ? ctx.sessionId() : null;
    }

    public static String getThreadId() {
        Ctx ctx = HOLDER.get();
        return ctx != null ? ctx.threadId() : null;
    }

    public static boolean isPresent() {
        return HOLDER.get() != null && HOLDER.get().sessionId() != null;
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
        if (policy == null) {
            EXECUTION_POLICY.remove();
        } else {
            EXECUTION_POLICY.set(policy);
        }
    }

    public static AiExecutionPolicy getExecutionPolicy() {
        AiExecutionPolicy policy = EXECUTION_POLICY.get();
        return policy != null ? policy : AiExecutionPolicy.defaultPolicy();
    }

    public static AiExecutionPolicy requireExecutionPolicy() {
        AiExecutionPolicy policy = EXECUTION_POLICY.get();
        if (policy == null || policy.getUserId() == null || policy.getUserId().isBlank()) {
            throw new SecurityException("AI 工具调用缺少已认证的执行身份");
        }
        return policy;
    }

    public static void setToolDescriptor(AiToolDescriptor descriptor) {
        if (descriptor == null) TOOL_DESCRIPTOR.remove();
        else TOOL_DESCRIPTOR.set(descriptor);
    }

    public static AiToolDescriptor getToolDescriptor() {
        return TOOL_DESCRIPTOR.get();
    }

    // ── Plan 关联 ────────────────────────────────────────────────────────────

    /** 设置当前工具调用所属的 plan 步骤索引。 */
    public static void setPlanStepIndex(int stepIndex) {
        if (stepIndex < 0) {
            PLAN_STEP_INDEX.remove();
        } else {
            PLAN_STEP_INDEX.set(stepIndex);
        }
    }

    /** 获取当前工具调用所属的 plan 步骤索引，-1 表示无关联。 */
    public static int getPlanStepIndex() {
        Integer v = PLAN_STEP_INDEX.get();
        return v != null ? v : -1;
    }

}
