package org.leo.ai.agent;

/**
 * ThreadLocal 工具执行上下文。
 *
 * <p>在工具方法执行期间持有当前 sessionId / threadId，替代每个 @Tool 方法上重复的
 * {@code @P("当前会话 ID") String sessionId} 参数。
 *
 * <p>生命周期由 AgentConfig 注入的 {@code beforeToolExecution} / {@code afterToolExecution}
 * 钩子管理：工具线程启动前设置，工具执行完毕后清除（finally 保证）。
 *
 * <p>子 Agent 工具同样在独立线程上执行，钩子也为每个子 Agent 工具线程设置各自的上下文，
 * 因此嵌套调用不会互相干扰。
 */
public final class AiToolContext {

    private record Ctx(String sessionId, String threadId) {}

    private static final ThreadLocal<Ctx> HOLDER = new ThreadLocal<>();

    private AiToolContext() {}

    // ── 设置 / 清除 ──────────────────────────────────────────────────────────

    /**
     * 从 LangChain4j memoryId（格式 sessionId:threadId）解析并设置当前线程上下文。
     * 在 {@code beforeToolExecution} 钩子中调用。
     */
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

    /**
     * 清除当前线程上下文。在 {@code afterToolExecution} 钩子中调用。
     */
    public static void clear() {
        HOLDER.remove();
    }

    // ── 读取 ─────────────────────────────────────────────────────────────────

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

    /**
     * 读取 sessionId，若缺失则抛出清晰的运行时异常（工具方法不应静默失败）。
     */
    public static String requireSessionId() {
        String id = getSessionId();
        if (id == null || id.isBlank()) {
            throw new IllegalStateException(
                    "AiToolContext.sessionId 未设置。请确认 AgentConfig 已配置 beforeToolExecution 钩子。");
        }
        return id;
    }
}
