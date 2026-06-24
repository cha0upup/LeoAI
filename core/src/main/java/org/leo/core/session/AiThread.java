package org.leo.core.session;

import org.leo.core.entity.AiExecutionPolicy;
import org.leo.core.entity.AiPlan;
import org.leo.core.entity.AiRuntimeStats;
import org.leo.core.entity.AiSseEvent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 单条 AI 对话线程，归属于某个 {@link PuppetNodeSession}。
 *
 * <p>一个 PuppetNodeSession 可持有多个 AiThread，彼此独立、可并行执行。
 * 每个线程拥有独立的 SSE 队列、工具确认状态和授权状态。
 *
 * <p>停止机制：{@link #stop()} 同时做三件事：
 * <ol>
 *   <li>设置 {@code stopRequested} 标志</li>
 *   <li>调用 stopCallback（如 StreamingHandle.cancel()）取消流式响应</li>
 *   <li>interrupt 正在执行的线程（中断阻塞的 HTTP 调用）</li>
 *   <li>以 false 完成所有挂起的工具确认 Future，防止拦截器永久阻塞</li>
 * </ol>
 */
public class AiThread {

    private static final int MAX_TURNS_WARN = 25;
    private static final int MAX_RECENT_EVENTS = 500;

    public static final String STATUS_IDLE = "idle";
    public static final String STATUS_RUNNING = "running";
    public static final String STATUS_WAITING_CONFIRMATION = "waiting_confirmation";
    public static final String STATUS_COMPLETED = "completed";
    public static final String STATUS_FAILED = "failed";
    public static final String STATUS_CANCELLED = "cancelled";

    public static final String MODE_AUTO = "auto";

    private final String threadId;
    private volatile String title;
    private final long createdAt;
    private volatile long lastActiveAt;
    private volatile Integer aiConfigId;
    /** 执行模式：plan / execute / auto。 */
    private volatile String mode = MODE_AUTO;
    /** 父会话 ID（子 Agent 派发使用，当前会话非派生时为 null）。 */
    private volatile String parentThreadId;

    // ── Agent 生命周期（已迁移到 LangChain4j，不再需要 ReactAgent/MemorySaver 引用） ──

    // ── 停止机制 ──────────────────────────────────────────────────────────────
    /** 正在执行 agent.invoke() 的线程引用，用于 interrupt。 */
    private volatile Thread executingThread;
    /** 执行占用标记：HTTP 线程提交后台任务前先抢占，避免重复提交。 */
    private final AtomicBoolean executionClaimed = new AtomicBoolean(false);
    private final AtomicBoolean stopRequested = new AtomicBoolean(false);
    private volatile String runStatus = STATUS_IDLE;
    private volatile String stopReason;
    private volatile long taskTimeoutAt;
    /** 外部注册的停止回调（如 StreamingHandle.cancel()），stop() 时触发。 */
    private volatile Runnable stopCallback;

    // ── SSE 事件队列 ──────────────────────────────────────────────────────────
    private final LinkedBlockingQueue<AiSseEvent> sseEventQueue = new LinkedBlockingQueue<>();
    private final AtomicLong sseEventSeq = new AtomicLong(0);
    private final List<AiSseEvent> recentSseEvents = Collections.synchronizedList(new ArrayList<>());

    // ── 轮次计数 ──────────────────────────────────────────────────────────────
    private final AtomicInteger turnCount = new AtomicInteger(0);

    // ── 运行统计 & 执行策略 ───────────────────────────────────────────────────
    private volatile AiRuntimeStats runtimeStats = new AiRuntimeStats();
    private volatile AiExecutionPolicy executionPolicy = AiExecutionPolicy.defaultPolicy();

    // ── 任务计划历史 ──────────────────────────────────────────────────────────
    private final List<AiPlan> planHistory = new CopyOnWriteArrayList<>();

    public AiThread(String threadId, String title) {
        this(threadId, title, System.currentTimeMillis(), 0L);
    }

    public AiThread(String threadId, String title, long createdAt, long lastActiveAt) {
        this.threadId     = threadId;
        this.title        = title;
        this.createdAt    = createdAt > 0 ? createdAt : System.currentTimeMillis();
        this.lastActiveAt = lastActiveAt > 0 ? lastActiveAt : this.createdAt;
    }

    // ── 基础属性 ──────────────────────────────────────────────────────────────

    public String getThreadId()       { return threadId; }
    public String getTitle()          { return title; }
    public void   setTitle(String t)  { this.title = t; }
    public long   getCreatedAt()      { return createdAt; }
    public long   getLastActiveAt()   { return lastActiveAt; }
    public void   touchLastActiveAt() { this.lastActiveAt = System.currentTimeMillis(); }
    public Integer getAiConfigId()     { return aiConfigId; }
    public void    setAiConfigId(Integer aiConfigId) { this.aiConfigId = aiConfigId; }

    public String getMode()            { return mode != null ? mode : MODE_AUTO; }
    public void   setMode(String m)    { this.mode = m == null || m.isBlank() ? MODE_AUTO : m; }

    public String getParentThreadId()                 { return parentThreadId; }
    public void   setParentThreadId(String parentId)  { this.parentThreadId = parentId; }

    // ── 停止机制 ──────────────────────────────────────────────────────────────

    /**
     * 原子抢占执行权。调用成功后，即使后台线程尚未进入 {@link #markExecuting(Thread)}，
     * {@link #isExecuting()} 也会返回 true，防止同一线程重复提交 AI 任务。
     */
    public boolean claimExecution() {
        boolean claimed = executionClaimed.compareAndSet(false, true);
        if (claimed) {
            stopRequested.set(false);
            runStatus = STATUS_RUNNING;
            stopReason = null;
            taskTimeoutAt = 0L;
        }
        return claimed;
    }

    /** 在 SSE 任务线程开始执行 agent.invoke() 前调用。 */
    public void markExecuting(Thread t) {
        if (!executionClaimed.get()) {
            claimExecution();
        }
        this.executingThread = t;
    }

    /** 在 SSE 任务线程 finally 块中调用，清除引用。 */
    public void clearExecuting() {
        this.executingThread = null;
        executionClaimed.set(false);
        stopRequested.set(false);
    }

    public boolean isStopRequested() { return stopRequested.get(); }
    public String getRunStatus() { return runStatus; }
    public boolean isExecuting() { return executionClaimed.get() || executingThread != null; }
    public String getStopReason() { return stopReason; }
    public long getTaskTimeoutAt() { return taskTimeoutAt; }
    public void setTaskTimeoutAt(long taskTimeoutAt) { this.taskTimeoutAt = Math.max(0L, taskTimeoutAt); }

    /**
     * 干净停止：interrupt 执行线程 + 取消所有挂起确认。
     * 可从任意线程安全调用（HTTP 线程、SSE onCompletion 回调等）。
     */
    public void stop() {
        stop("用户手动停止");
    }

    public void stop(String reason) {
        stopRequested.set(true);
        stopReason = reason != null && !reason.isBlank() ? reason : "已停止";
        runStatus = STATUS_CANCELLED;
        Runnable cb = stopCallback;
        if (cb != null) {
            try { cb.run(); } catch (Exception ignored) {}
        }
        Thread t = executingThread;
        if (t != null) t.interrupt();
    }

    public void setStopCallback(Runnable callback) {
        this.stopCallback = callback;
    }

    public void markCompleted() {
        runStatus = STATUS_COMPLETED;
        taskTimeoutAt = 0L;
    }

    public void markFailed() {
        runStatus = STATUS_FAILED;
        taskTimeoutAt = 0L;
    }

    public void markCancelled() {
        runStatus = STATUS_CANCELLED;
        taskTimeoutAt = 0L;
    }

    // ── SSE 事件队列 ──────────────────────────────────────────────────────────

    public LinkedBlockingQueue<AiSseEvent> getSseEventQueue() { return sseEventQueue; }

    public AiSseEvent offerSseEvent(String name, Object data) {
        AiSseEvent event = recordSseEvent(name, data);
        sseEventQueue.offer(event);
        return event;
    }

    public AiSseEvent recordSseEvent(String name, Object data) {
        return recordSseEvent(name, data, null);
    }

    public AiSseEvent recordSseEvent(String name, Object data, String subagentInvocationId) {
        AiSseEvent event = new AiSseEvent(sseEventSeq.incrementAndGet(),
                System.currentTimeMillis(), name, data, subagentInvocationId);
        synchronized (recentSseEvents) {
            recentSseEvents.add(event);
            int overflow = recentSseEvents.size() - MAX_RECENT_EVENTS;
            if (overflow > 0) {
                recentSseEvents.subList(0, overflow).clear();
            }
        }
        return event;
    }

    public List<AiSseEvent> recentSseEventsAfter(long afterSeq, int limit) {
        int safeLimit = limit > 0 ? Math.min(limit, MAX_RECENT_EVENTS) : MAX_RECENT_EVENTS;
        List<AiSseEvent> result = new ArrayList<>();
        synchronized (recentSseEvents) {
            for (AiSseEvent event : recentSseEvents) {
                if (event.seq() > afterSeq) {
                    result.add(event);
                    if (result.size() >= safeLimit) break;
                }
            }
        }
        return result;
    }

    public long getLastSseEventSeq() {
        return sseEventSeq.get();
    }

    public void clearSseEvents() {
        sseEventQueue.clear();
        synchronized (recentSseEvents) {
            recentSseEvents.clear();
        }
        sseEventSeq.set(0L);
    }

    public void offerSystemWarn(String message) {
        if (message != null && !message.isBlank()) {
            offerSseEvent("warn", message);
        }
    }

    // ── 工具确认 ──────────────────────────────────────────────────────────────

    // ── 轮次计数 ──────────────────────────────────────────────────────────────

    /**
     * 每次 AI 回复完成后调用，累加轮次并在达到阈值时返回警告文本。
     *
     * @return 达到阈值时返回提示字符串，否则返回 null
     */
    public String incrementAndCheckTurnCount() {
        int count = turnCount.incrementAndGet();
        if (count >= MAX_TURNS_WARN) {
            return "对话已进行 " + count + " 轮，上下文接近上限，建议新建对话线程以保持最佳效果。";
        }
        return null;
    }

    public void resetTurnCount() { turnCount.set(0); }
    public int  getTurnCount()   { return turnCount.get(); }

    // ── 运行统计 & 执行策略 ───────────────────────────────────────────────────

    public AiRuntimeStats getRuntimeStats() { return runtimeStats; }

    public void resetRuntimeStats() { this.runtimeStats = new AiRuntimeStats(); }

    public AiExecutionPolicy getExecutionPolicy() {
        AiExecutionPolicy p = executionPolicy;
        return p != null ? p : AiExecutionPolicy.defaultPolicy();
    }

    public void setExecutionPolicy(AiExecutionPolicy policy) {
        this.executionPolicy = policy != null ? policy : AiExecutionPolicy.defaultPolicy();
    }

    // ── 任务计划管理 ──────────────────────────────────────────────────────────

    /**
     * 添加新计划到历史列表（每次 createPlan 调用时触发）。
     */
    public void addPlan(AiPlan plan) {
        if (plan != null) {
            planHistory.add(plan);
        }
    }

    /**
     * 获取当前活跃计划（最近一次创建的）。
     */
    public AiPlan getCurrentPlan() {
        if (planHistory.isEmpty()) return null;
        return planHistory.get(planHistory.size() - 1);
    }

    /**
     * 获取所有历史计划（按创建顺序，不可修改）。
     */
    public List<AiPlan> getPlanHistory() {
        return Collections.unmodifiableList(planHistory);
    }
}
