package org.leo.ai.agent;

/**
 * ThreadLocal 工具执行上下文。
 *
 * <p>在工具方法执行期间持有当前 sessionId / threadId / planStepIndex，
 * 替代每个 @Tool 方法上重复的参数声明。
 *
 * <p>生命周期由 AgentConfig 注入的 {@code beforeToolExecution} / {@code afterToolExecution}
 * 钩子管理：工具线程启动前设置，工具执行完毕后清除（finally 保证）。
 */
public final class AiToolContext {

    private record Ctx(String sessionId, String threadId) {}

    private static final ThreadLocal<Ctx> HOLDER = new ThreadLocal<>();
    private static final ThreadLocal<Integer> PLAN_STEP_INDEX = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> PLAN_STEP_PRE_APPROVED = new ThreadLocal<>();

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
        PLAN_STEP_PRE_APPROVED.remove();
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
                    "AiToolContext.sessionId 未设置。请确认 AgentConfig 已配置 beforeToolExecution 钩子。");
        }
        return id;
    }

    // ── Plan 关联 ────────────────────────────────────────────────────────────

    /** 设置当前工具调用所属的 plan 步骤索引。 */
    public static void setPlanStepIndex(int stepIndex) {
        PLAN_STEP_INDEX.set(stepIndex);
    }

    /** 获取当前工具调用所属的 plan 步骤索引，-1 表示无关联。 */
    public static int getPlanStepIndex() {
        Integer v = PLAN_STEP_INDEX.get();
        return v != null ? v : -1;
    }

    /** 设置当前步骤是否已被预批准。 */
    public static void setPlanStepPreApproved(boolean preApproved) {
        PLAN_STEP_PRE_APPROVED.set(preApproved);
    }

    /** 当前步骤是否已被预批准。 */
    public static boolean isPlanStepPreApproved() {
        return Boolean.TRUE.equals(PLAN_STEP_PRE_APPROVED.get());
    }
}
