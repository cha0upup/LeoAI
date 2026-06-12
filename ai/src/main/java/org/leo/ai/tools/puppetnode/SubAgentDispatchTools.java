package org.leo.ai.tools.puppetnode;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.service.tool.BeforeToolExecution;
import dev.langchain4j.service.tool.ToolExecution;
import org.leo.ai.agent.AiToolContext;
import org.leo.ai.agent.ExploitSubAgent;
import org.leo.ai.agent.PersistenceSubAgent;
import org.leo.ai.agent.ReconSubAgent;
import org.leo.ai.config.AiAgentProperties;
import org.leo.ai.util.ToolResultUtils;
import org.leo.core.session.PuppetNodeSessionContainer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * 子 Agent 调度工具。主 Agent 通过此工具将专项任务分发给子 Agent 处理。
 *
 * <p>子 Agent 为非流式同步调用，执行完毕后将结果文本返回给主 Agent。
 *
 * <p>sessionId 与 threadId 通过 {@link AiToolContext} ThreadLocal 自动注入。
 *
 * <p>运行时限制（来自 {@link AiAgentProperties.SubAgentConfig}）：
 * <ul>
 *   <li>{@code maxPerSession}  — 单次会话内允许同时运行的子 Agent 数上限；超过后立即拒绝派发</li>
 *   <li>{@code timeoutSeconds} — 单个子 Agent 执行超时，超时后取消并返回错误</li>
 *   <li>{@code maxIterations}  — 单个子 Agent 内允许的最大工具调用次数；超过后抛异常强制终止 reasoning loop</li>
 * </ul>
 */
@Component
public class SubAgentDispatchTools {

    private static final Logger log = LoggerFactory.getLogger(SubAgentDispatchTools.class);

    private final ReconSubAgent reconSubAgent;
    private final PersistenceSubAgent persistenceSubAgent;
    private final ExploitSubAgent exploitSubAgent;
    private final AiAgentProperties agentProps;
    private final ExecutorService dispatchExecutor;

    // SSE 事件 status 字段取值
    private static final String STATUS_RUNNING   = "running";
    private static final String STATUS_COMPLETED = "completed";
    private static final String STATUS_FAILED    = "failed";

    // 子 Agent 结果文本压缩阈值（字符数）
    private static final int RESULT_COMPRESS_THRESHOLD = 12_000;
    // SSE summary 摘要的截断长度
    private static final int SSE_SUMMARY_LIMIT = 500;
    // 子 Agent 内部工具结果预览的截断长度
    private static final int INNER_TOOL_RESULT_PREVIEW_LIMIT = 2_000;

    /**
     * 事件发射器：(eventName, jsonPayload) → 发送 SSE 事件。
     * 并发工具执行时工具会运行在独立线程中，因此按 sessionId:threadId 绑定当前 SSE 发射器。
     */
    private static final ConcurrentMap<String, BiConsumer<String, Object>> EVENT_EMITTERS = new ConcurrentHashMap<>();
    private static final ConcurrentMap<String, ActiveSubAgent> ACTIVE_SUBAGENTS = new ConcurrentHashMap<>();

    /** 每个 session 当前活跃子 Agent 数量（含正在执行的）。 */
    private static final ConcurrentMap<String, AtomicInteger> SESSION_ACTIVE_COUNT = new ConcurrentHashMap<>();

    public SubAgentDispatchTools(ReconSubAgent reconSubAgent,
                                 PersistenceSubAgent persistenceSubAgent,
                                 ExploitSubAgent exploitSubAgent,
                                 AiAgentProperties agentProps,
                                 @Qualifier("subAgentDispatchExecutor") ExecutorService dispatchExecutor) {
        this.reconSubAgent = reconSubAgent;
        this.persistenceSubAgent = persistenceSubAgent;
        this.exploitSubAgent = exploitSubAgent;
        this.agentProps = agentProps;
        this.dispatchExecutor = dispatchExecutor;
    }

    /**
     * 注册会话销毁监听器，使会话被移除/驱逐时自动清理本类持有的所有静态映射，
     * 避免长时间运行的服务因异常路径未触发 {@link #clearEventEmitter} 而内存泄漏。
     */
    @PostConstruct
    void registerSessionCleanup() {
        PuppetNodeSessionContainer.registerDestroyListener(SubAgentDispatchTools::clearForSession);
    }

    /**
     * 清理与指定 sessionId 相关的所有静态状态：
     * <ul>
     *   <li>{@link #EVENT_EMITTERS}    — 所有 key 以 sessionId 开头的 SSE 发射器</li>
     *   <li>{@link #ACTIVE_SUBAGENTS}  — 所有 memoryId 包含 sessionId 的活跃子 Agent</li>
     *   <li>{@link #SESSION_ACTIVE_COUNT} — sessionId 的计数器</li>
     * </ul>
     */
    public static void clearForSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return;
        String prefix = sessionId + ":";
        EVENT_EMITTERS.keySet().removeIf(k -> k.equals(sessionId) || k.startsWith(prefix));
        ACTIVE_SUBAGENTS.keySet().removeIf(k -> k != null && (k.equals(sessionId) || k.startsWith(prefix)));
        SESSION_ACTIVE_COUNT.remove(sessionId);
    }

    // ── 事件发射器管理 ────────────────────────────────────────────────────────

    public static void setEventEmitter(String sessionId, BiConsumer<String, Object> emitter) {
        setEventEmitter(sessionId, null, emitter);
    }

    public static void setEventEmitter(String sessionId, String threadId, BiConsumer<String, Object> emitter) {
        String key = emitterKey(sessionId, threadId);
        if (key != null && emitter != null) {
            EVENT_EMITTERS.put(key, emitter);
        }
    }

    public static void clearEventEmitter(String sessionId) {
        clearEventEmitter(sessionId, null);
    }

    public static void clearEventEmitter(String sessionId, String threadId) {
        String key = emitterKey(sessionId, threadId);
        if (key != null) {
            EVENT_EMITTERS.remove(key);
        }
    }

    // ── @Tool 调度方法 ────────────────────────────────────────────────────────

    @Tool("""
            将专项任务分发给子 Agent 处理。根据 category 选择对应的子 Agent：
            - recon：侦察/信息收集（端口扫描、浏览器数据提取、凭据采集、剪贴板读写）
            - persistence：持久化/服务管理（计划任务、系统服务、事件日志、Tomcat 内存马、Java 插件）
            - exploit：攻击/利用（HTTP 请求/Fuzz、脚本执行、SQL 执行、应用资源读取）
            task 应包含具体操作指令和目标，子 Agent 会根据指令选择合适工具执行并返回结果摘要。
            timeoutSeconds 可选，0 或不填时使用系统默认值（300s）；
            轻量/单步任务可设 60，复杂多步/长耗时任务可设 600，上限 1800。
            """)
    public String dispatchSubtask(
            @P("具体任务描述，包含操作指令和目标") String task,
            @P("子 Agent 类别：recon | persistence | exploit") String category,
            @P("执行超时秒数，可选，0 或不填使用默认值") Integer timeoutSeconds) {
        String sessionId = AiToolContext.getSessionId();
        String threadId  = AiToolContext.getThreadId();

        if (sessionId == null || sessionId.isBlank()) {
            return "派发失败：上下文中无 sessionId，请确认 AgentConfig 已配置 beforeToolExecution 钩子。";
        }

        String wrappedTask = "[sessionId=" + sessionId + "] " + task;
        String agentName = category == null ? "recon" : category.trim().toLowerCase();

        Function<String, String> executor = switch (agentName) {
            case "recon"       -> subMemId -> reconSubAgent.chat(subMemId, wrappedTask);
            case "persistence" -> subMemId -> persistenceSubAgent.chat(subMemId, wrappedTask);
            case "exploit"     -> subMemId -> exploitSubAgent.chat(subMemId, wrappedTask);
            default -> null;
        };
        if (executor == null) {
            return "派发失败：未知的子 Agent 类别 \"" + category + "\"，可选值：recon | persistence | exploit。";
        }

        // 解析有效超时：调用方指定的值优先，不合法则退回配置默认值，最大上限 1800s
        int configDefault = agentProps.getSubagent().getTimeoutSeconds();
        int effectiveTimeout = (timeoutSeconds != null && timeoutSeconds > 0)
                ? Math.min(timeoutSeconds, 1800)
                : configDefault;

        return dispatchWithEvents(agentName, sessionId, threadId, task, executor, effectiveTimeout);
    }

    // ── 核心调度逻辑 ──────────────────────────────────────────────────────────

    private String dispatchWithEvents(String agentName, String sessionId, String threadId,
                                      String task, Function<String, String> executor, int timeoutSeconds) {
        AiAgentProperties.SubAgentConfig cfg = agentProps.getSubagent();

        // ── 1. 检查 maxPerSession ──────────────────────────────────────────────
        int maxPerSession = cfg.getMaxPerSession();
        AtomicInteger counter = SESSION_ACTIVE_COUNT.computeIfAbsent(sessionId, k -> new AtomicInteger(0));
        int active = counter.incrementAndGet();
        if (active > maxPerSession) {
            releaseSessionSlot(sessionId);
            log.warn("SubAgent 派发被拒绝：session={} 已有 {} 个活跃子 Agent，上限 {}", sessionId, active - 1, maxPerSession);
            return String.format("子 Agent 数量已达上限（%d），请等待当前子 Agent 执行完毕后再派发新任务。", maxPerSession);
        }

        String eventKey = emitterKey(sessionId, threadId);
        String invocationId = eventKey + ":" + agentName + ":" + System.currentTimeMillis();
        // memoryId 直接复用 invocationId（已自带 eventKey:agentName 前缀），避免双重前缀
        String memoryId = invocationId;
        long startTime = System.currentTimeMillis();

        // ── 2. 注册子 Agent（含 maxIterations 计数器 + 卡点追踪字段）─────────
        int maxIterations = cfg.getMaxIterations();
        ActiveSubAgent activeSubAgent = new ActiveSubAgent(eventKey, invocationId, agentName,
                new AtomicInteger(0), maxIterations,
                new AtomicReference<>(null), new AtomicLong(0L));
        ACTIVE_SUBAGENTS.put(memoryId, activeSubAgent);

        emitSubAgentEvent(eventKey, invocationId, agentName, STATUS_RUNNING, task, null, startTime);

        // ── 3. 带超时执行 ──────────────────────────────────────────────────────
        CompletableFuture<String> future = CompletableFuture.supplyAsync(
                () -> executor.apply(memoryId), dispatchExecutor);
        try {
            String result = future.get(timeoutSeconds, TimeUnit.SECONDS);
            emitSubAgentEvent(eventKey, invocationId, agentName, STATUS_COMPLETED, task,
                    truncate(result, SSE_SUMMARY_LIMIT), System.currentTimeMillis());
            return compressSubAgentResult(result);

        } catch (TimeoutException e) {
            future.cancel(true);
            // 收集卡点信息：是卡在某个工具调用，还是卡在 LLM 推理（工具已结束、未发起新工具）
            String lastTool = activeSubAgent.lastToolName().get();
            long lastToolStart = activeSubAgent.lastToolStartMs().get();
            int iterations = activeSubAgent.iterationCount().get();
            String stuckAt;
            if (lastTool != null && lastToolStart > 0) {
                long stuckMs = System.currentTimeMillis() - lastToolStart;
                stuckAt = String.format("卡在工具 %s（已运行 %ds）", lastTool, stuckMs / 1000);
            } else {
                stuckAt = "卡在 LLM 推理阶段（无活跃工具调用）";
            }
            log.warn("SubAgent 执行超时：session={}, agent={}, timeout={}s, iterations={}/{}, {}",
                    sessionId, agentName, timeoutSeconds, iterations, maxIterations, stuckAt);
            emitSubAgentEvent(eventKey, invocationId, agentName, STATUS_FAILED, task,
                    "执行超时（" + timeoutSeconds + "s），" + stuckAt, System.currentTimeMillis());
            return String.format("子 Agent 执行超时（%ds，%s），任务已取消，请简化任务或分步执行。",
                    timeoutSeconds, stuckAt);

        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            emitSubAgentEvent(eventKey, invocationId, agentName, STATUS_FAILED, task,
                    "错误: " + cause.getMessage(), System.currentTimeMillis());
            return "子 Agent 执行失败: " + cause.getMessage();

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            return "子 Agent 执行被中断";

        } finally {
            ACTIVE_SUBAGENTS.remove(memoryId);
            releaseSessionSlot(sessionId);
        }
    }

    /**
     * 释放一个会话级槽位。当计数归零时把整个 entry 从 {@link #SESSION_ACTIVE_COUNT}
     * 中移除，避免长时间运行的服务无限累积已结束会话的空计数器。
     *
     * <p>使用 {@link ConcurrentHashMap#compute} 保证"自减 + 判零移除"的原子性，
     * 不会与同 sessionId 的新派发请求（{@code computeIfAbsent} + {@code incrementAndGet}）冲突。
     */
    private static void releaseSessionSlot(String sessionId) {
        SESSION_ACTIVE_COUNT.compute(sessionId, (k, existing) -> {
            if (existing == null) return null; // 已被其他路径清理
            int remaining = existing.decrementAndGet();
            return remaining <= 0 ? null : existing;
        });
    }

    // ── 子 Agent 内部工具回调（LangChain4j hooks）────────────────────────────

    /**
     * 工具调用前回调：发送 tool_start 事件，并检查是否超出最大迭代次数。
     * 超出时抛出异常，LangChain4j 将把异常信息作为工具返回值传回模型，
     * 模型会据此结束 reasoning loop 并汇总结果。
     */
    public static void emitInnerToolStart(BeforeToolExecution execution) {
        ActiveSubAgent active = activeSubAgent(execution == null ? null : execution.invocationContext());
        if (active == null || execution == null) return;

        // 检查迭代次数上限
        int iteration = active.iterationCount().incrementAndGet();
        if (active.maxIterations() > 0 && iteration > active.maxIterations()) {
            throw new RuntimeException(
                    String.format("子 Agent 已达最大迭代次数（%d），强制终止，请汇总已有结果返回。",
                            active.maxIterations()));
        }

        long now = System.currentTimeMillis();
        // 记录最后一次工具调用的状态，供超时日志定位卡点
        active.lastToolName().set(execution.request().name());
        active.lastToolStartMs().set(now);

        LinkedHashMap<String, Object> data = new LinkedHashMap<>();
        data.put("toolName", execution.request().name());
        data.put("toolCallId", execution.request().id());
        data.put("arguments", execution.request().arguments());
        data.put("success", null);
        data.put("status", STATUS_RUNNING);
        data.put("entryType", "tool");
        data.put("iteration", iteration);
        data.put("maxIterations", active.maxIterations());
        data.put("parentAgentName", active.agentName());
        data.put("parentSubagentInvocationId", active.invocationId());
        data.put("timestamp", now);
        data.put("startTime", now);
        data.put("endTime", null);
        emitSubAgentInnerEvent(active, "tool_start", data);
    }

    public static void emitInnerToolDone(ToolExecution execution) {
        ActiveSubAgent active = activeSubAgent(execution == null ? null : execution.invocationContext());
        if (active == null || execution == null) return;
        // 工具已结束 —— 清空"卡点候选"，使后续若超时可判断为"卡在 LLM 推理阶段"而非工具内
        active.lastToolName().set(null);
        active.lastToolStartMs().set(0L);

        long now = System.currentTimeMillis();
        long startTime = toEpochMs(execution.startTime(), now);
        LinkedHashMap<String, Object> data = new LinkedHashMap<>();
        data.put("toolName", execution.request().name());
        data.put("toolCallId", execution.request().id());
        data.put("arguments", execution.request().arguments());
        data.put("resultPreview", truncate(execution.result(), INNER_TOOL_RESULT_PREVIEW_LIMIT));
        data.put("success", !execution.hasFailed());
        data.put("status", execution.hasFailed() ? STATUS_FAILED : STATUS_COMPLETED);
        data.put("entryType", "tool");
        data.put("parentAgentName", active.agentName());
        data.put("parentSubagentInvocationId", active.invocationId());
        data.put("timestamp", startTime);
        data.put("startTime", startTime);
        data.put("endTime", toEpochMs(execution.finishTime(), now));
        emitSubAgentInnerEvent(active, "tool", data);
    }

    // ── 私有工具方法 ──────────────────────────────────────────────────────────

    private void emitSubAgentEvent(String eventKey, String invocationId, String agentName,
                                   String status, String task, String summary, long timestamp) {
        BiConsumer<String, Object> emitter = EVENT_EMITTERS.get(eventKey);
        if (emitter == null) {
            log.warn("SubAgentDispatchTools: SSE emitter 不存在，subagent 事件丢弃。eventKey={}, agentName={}, status={}",
                    eventKey, agentName, status);
            return;
        }
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        payload.put("kind", "subtask");
        payload.put("invocationId", invocationId);
        payload.put("agentName", agentName);
        payload.put("status", status);
        payload.put("phase", phaseFor(status));
        payload.put("task", task);
        if (summary != null) payload.put("summary", summary);
        payload.put("timestamp", timestamp);
        // RUNNING → create subtask node; COMPLETED/FAILED → patch existing node
        String eventName = STATUS_RUNNING.equals(status) ? "node" : "patch";
        emitter.accept(eventName, payload);
    }

    private static ActiveSubAgent activeSubAgent(dev.langchain4j.invocation.InvocationContext context) {
        if (context == null || context.chatMemoryId() == null) return null;
        return ACTIVE_SUBAGENTS.get(String.valueOf(context.chatMemoryId()));
    }

    private static void emitSubAgentInnerEvent(ActiveSubAgent active, String name, Object data) {
        BiConsumer<String, Object> emitter = EVENT_EMITTERS.get(active.eventKey());
        if (emitter == null) {
            log.warn("SubAgentDispatchTools: SSE emitter 不存在，子 Agent 内部事件丢弃。eventKey={}, event={}",
                    active.eventKey(), name);
            return;
        }
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        payload.put("kind", "subtask");
        payload.put("subagentInvocationId", active.invocationId());
        payload.put("name", name);
        payload.put("data", data);
        emitter.accept("patch", payload);
    }

    /**
     * 压缩子 Agent 返回的结果文本（阈值 12K，保留足够上下文）。
     */
    private static String compressSubAgentResult(String result) {
        if (result == null || result.length() <= RESULT_COMPRESS_THRESHOLD) return result;
        return ToolResultUtils.compressCommandOutput(result, RESULT_COMPRESS_THRESHOLD);
    }

    private static String truncate(String value, int max) {
        if (value == null) return null;
        return value.length() > max ? value.substring(0, max) + "..." : value;
    }

    private static String phaseFor(String status) {
        if (STATUS_RUNNING.equals(status) || "pending".equals(status)) return "thinking";
        if (STATUS_COMPLETED.equals(status) || STATUS_FAILED.equals(status) || "cancelled".equals(status)) return status;
        return "subagent";
    }

    private static long toEpochMs(LocalDateTime ldt, long fallback) {
        if (ldt == null) return fallback;
        return ldt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    private static String emitterKey(String sessionId, String threadId) {
        if (sessionId == null || sessionId.isBlank()) return null;
        return threadId == null || threadId.isBlank() ? sessionId : sessionId + ":" + threadId;
    }

    private record ActiveSubAgent(
            String eventKey,
            String invocationId,
            String agentName,
            AtomicInteger iterationCount,
            int maxIterations,
            // 用于超时时定位卡点：最后一次内部工具调用的名称和开始时间。
            // 用 AtomicReference / AtomicLong 而不是 volatile 字段，便于 record 保持不可变的同时
            // 还能跨线程更新这两个调试字段。
            AtomicReference<String> lastToolName,
            AtomicLong lastToolStartMs
    ) {}
}
