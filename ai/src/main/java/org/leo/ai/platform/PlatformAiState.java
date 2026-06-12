package org.leo.ai.platform;

import org.leo.ai.agent.AiStateAccessor;
import org.leo.core.entity.AiConfirmationRequest;
import org.leo.core.entity.AiExecutionPolicy;
import org.leo.core.entity.AiPlan;
import org.leo.core.entity.AiRuntimeStats;
import org.leo.core.entity.AiSseEvent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 平台侧 AI 运行时状态，同时实现 {@link AiStateAccessor}。
 *
 * <p>可直接作为 {@code AiStateAccessor} 传入拦截器，无需额外适配层。
 * 生命周期由 {@link PlatformAiStateStore} 管理，绑定到 HTTP Session。
 */
public class PlatformAiState implements AiStateAccessor {

    private static final int MAX_RECENT_EVENTS = 500;
    public static final String STATUS_IDLE = "idle";
    public static final String STATUS_RUNNING = "running";
    public static final String STATUS_WAITING_CONFIRMATION = "waiting_confirmation";
    public static final String STATUS_COMPLETED = "completed";
    public static final String STATUS_FAILED = "failed";
    public static final String STATUS_CANCELLED = "cancelled";

    private final String stateId;
    private final long createdAt;
    private volatile long lastActiveAt;
    private volatile Integer aiConfigId;
    /** 执行模式：plan / execute / auto。 */
    private volatile String mode = "auto";
    private final ConcurrentHashMap<String, CompletableFuture<Boolean>> pendingConfirmations = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AiConfirmationRequest> pendingConfirmationRequests = new ConcurrentHashMap<>();
    private final AiRuntimeStats runtimeStats = new AiRuntimeStats();
    private volatile AiExecutionPolicy executionPolicy = AiExecutionPolicy.defaultPolicy();
    /** 累计对话轮次，用于上下文超限预警。 */
    private final AtomicInteger turnCount = new AtomicInteger(0);
    private static final int MAX_TURNS_WARN = 25;
    /** 正在执行 agent.invoke() 的线程引用，用于显式停止。 */
    private volatile Thread executingThread;
    /** 执行占用标记：HTTP 线程提交后台任务前先抢占，避免重复提交。 */
    private final AtomicBoolean executionClaimed = new AtomicBoolean(false);
    private final AtomicBoolean stopRequested = new AtomicBoolean(false);
    private volatile String runStatus = STATUS_IDLE;
    private volatile String stopReason;
    /** 外部注册的停止回调（如 StreamingHandle.cancel()），stopGeneration() 时触发。 */
    private volatile Runnable stopCallback;

    // ── 统一 SSE 事件队列（drain 线程的唯一消费入口）────────────────────────────────
    /** 所有类型的 SSE 事件在此汇聚，drain 线程以 poll(timeout) 阻塞等待，无事件时不空转。 */
    private final LinkedBlockingQueue<AiSseEvent> sseEventQueue = new LinkedBlockingQueue<>();
    private final AtomicLong sseEventSeq = new AtomicLong(0);
    private final List<AiSseEvent> recentSseEvents = Collections.synchronizedList(new ArrayList<>());

    // ── 会话级工具授权状态 ────────────────────────────────────────────────────────
    /** 本会话已放行的工具类型（永久策略 + 用户本次授权）。 */
    private final Set<String> sessionGrantedTypes = ConcurrentHashMap.newKeySet();
    /** 会话级全量授权（仅 admin 可开启）。 */
    private final AtomicBoolean sessionGrantedAll = new AtomicBoolean(false);

    // ── 任务计划历史 ──────────────────────────────────────────────────────────
    private final List<AiPlan> planHistory = new CopyOnWriteArrayList<>();
    public PlatformAiState(String stateId) {
        this.stateId = stateId;
        this.createdAt = System.currentTimeMillis();
        this.lastActiveAt = this.createdAt;
    }

    // ── AiStateAccessor ──────────────────────────────────────────────────────────

    @Override
    public AiRuntimeStats getRuntimeStats() {
        return runtimeStats;
    }

    @Override
    public AiExecutionPolicy getExecutionPolicy() {
        return executionPolicy != null ? executionPolicy : AiExecutionPolicy.defaultPolicy();
    }

    /**
     * PlatformAiState 只要对象存在就是有效的（状态生命周期由 PlatformAiStateStore 管理）。
     */
    @Override
    public boolean isValid() {
        return true;
    }

    @Override
    public CompletableFuture<Boolean> awaitConfirmation(String callId, String toolName,
            String toolType, String toolTypeLabel, String argsPreview, String impact) {
        return awaitConfirmation(callId, toolName, toolType, toolTypeLabel, argsPreview, impact, 0L);
    }

    @Override
    public CompletableFuture<Boolean> awaitConfirmation(String callId, String toolName,
            String toolType, String toolTypeLabel, String argsPreview, String impact,
            long timeoutMs) {
        long requestedAt = System.currentTimeMillis();
        Long expiresAt = timeoutMs > 0 ? requestedAt + timeoutMs : null;
        AiConfirmationRequest req = new AiConfirmationRequest(
                callId, toolName, toolType, toolTypeLabel, argsPreview, impact,
                requestedAt, expiresAt, timeoutMs > 0 ? timeoutMs : null);
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        pendingConfirmations.put(callId, future);
        pendingConfirmationRequests.put(callId, req);
        runStatus = STATUS_WAITING_CONFIRMATION;
        offerSseEvent("confirm", req);
        return future;
    }

    @Override
    public boolean resolveConfirmation(String callId, boolean approved) {
        CompletableFuture<Boolean> future = pendingConfirmations.remove(callId);
        pendingConfirmationRequests.remove(callId);
        if (future != null) {
            future.complete(approved);
            if (pendingConfirmations.isEmpty() && executingThread != null && !stopRequested.get()) {
                runStatus = STATUS_RUNNING;
            }
            return true;
        }
        return false;
    }

    @Override
    public void cancelAllPendingConfirmations() {
        for (CompletableFuture<Boolean> f : pendingConfirmations.values()) {
            f.complete(false);
        }
        pendingConfirmations.clear();
        pendingConfirmationRequests.clear();
        if (STATUS_WAITING_CONFIRMATION.equals(runStatus) && isExecuting() && !stopRequested.get()) {
            runStatus = STATUS_RUNNING;
        }
    }

    /**
     * 原子抢占执行权。调用成功后，即使后台线程尚未进入 {@link #markExecuting(Thread)}，
     * {@link #isExecuting()} 也会返回 true，防止同一平台 AI 会话重复提交任务。
     */
    public boolean claimExecution() {
        boolean claimed = executionClaimed.compareAndSet(false, true);
        if (claimed) {
            stopRequested.set(false);
            stopReason = null;
            runStatus = STATUS_RUNNING;
        }
        return claimed;
    }

    public void markExecuting(Thread thread) {
        if (!executionClaimed.get()) {
            claimExecution();
        }
        this.executingThread = thread;
    }

    public void clearExecuting() {
        this.executingThread = null;
        executionClaimed.set(false);
        stopRequested.set(false);
    }

    public boolean isStopRequested() {
        return stopRequested.get();
    }

    public boolean isExecuting() {
        return executionClaimed.get() || executingThread != null;
    }

    public String getRunStatus() {
        return runStatus;
    }

    public String getStopReason() {
        return stopReason;
    }

    public int getPendingConfirmationCount() {
        return pendingConfirmations.size();
    }

    public long getNextConfirmationExpiresAt() {
        long next = Long.MAX_VALUE;
        for (AiConfirmationRequest request : pendingConfirmationRequests.values()) {
            Long expiresAt = request.getExpiresAt();
            if (expiresAt != null && expiresAt > 0 && expiresAt < next) {
                next = expiresAt;
            }
        }
        return next == Long.MAX_VALUE ? 0L : next;
    }

    public void markCompleted() {
        runStatus = STATUS_COMPLETED;
    }

    public void markFailed() {
        runStatus = STATUS_FAILED;
    }

    public void markCancelled() {
        runStatus = STATUS_CANCELLED;
    }

    public void stopGeneration() {
        stopGeneration("用户手动停止");
    }

    public void stopGeneration(String reason) {
        stopRequested.set(true);
        stopReason = reason != null && !reason.isBlank() ? reason : "已停止";
        runStatus = STATUS_CANCELLED;
        Runnable cb = stopCallback;
        if (cb != null) {
            try { cb.run(); } catch (Exception ignored) {}
        }
        Thread thread = executingThread;
        if (thread != null) {
            thread.interrupt();
        }
        cancelAllPendingConfirmations();
        if (stopReason != null && !stopReason.isBlank()) {
            offerWarnMessage(stopReason);
        }
    }

    public void setStopCallback(Runnable callback) {
        this.stopCallback = callback;
    }

    // ── Getters / Setters ────────────────────────────────────────────────────────

    public String getStateId() {
        return stateId;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public long getLastActiveAt() {
        return lastActiveAt;
    }

    public void touchLastActiveAt() {
        this.lastActiveAt = System.currentTimeMillis();
    }

    public Integer getAiConfigId() {
        return aiConfigId;
    }

    public String getMode() {
        return mode != null ? mode : "auto";
    }

    public void setMode(String mode) {
        this.mode = mode == null || mode.isBlank() ? "auto" : mode;
    }

    public void setAiConfigId(Integer aiConfigId) {
        this.aiConfigId = aiConfigId;
    }

    public void setExecutionPolicy(AiExecutionPolicy executionPolicy) {
        this.executionPolicy = executionPolicy != null ? executionPolicy : AiExecutionPolicy.defaultPolicy();
    }

    @Override
    public LinkedBlockingQueue<AiSseEvent> getAiSseEventQueue() {
        return sseEventQueue;
    }

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

    /**
     * 每次 AI 回复完成后调用。超过阈值时返回警告字符串，否则返回 null。
     */
    public String incrementAndCheckTurnCount() {
        int count = turnCount.incrementAndGet();
        if (count >= MAX_TURNS_WARN) {
            return "对话已进行 " + count + " 轮，上下文接近上限，建议点击「新建会话」以保持最佳效果。";
        }
        return null;
    }

    /** 重置轮次计数（新建会话时调用）。 */
    public void resetTurnCount() {
        turnCount.set(0);
        sessionGrantedTypes.clear();
        sessionGrantedAll.set(false);
        pendingConfirmations.clear();
        pendingConfirmationRequests.clear();
        executingThread = null;
        executionClaimed.set(false);
        runStatus = STATUS_IDLE;
        stopReason = null;
        stopRequested.set(false);
        clearSseEvents();
    }

    // ── 会话级工具授权 ────────────────────────────────────────────────────────────

    /** 将指定工具类型加入本会话放行集合（由 /grant 接口调用）。 */
    @Override
    public void grantSessionType(String toolType) {
        if (toolType != null && !toolType.isBlank()) {
            sessionGrantedTypes.add(toolType.trim());
        }
    }

    /** 开启会话级全量授权（仅 admin）。 */
    @Override
    public void grantSessionAll() {
        sessionGrantedAll.set(true);
    }

    /** 判断指定类型是否已在本会话内获得放行。 */
    @Override
    public boolean isSessionGranted(String toolType) {
        if (sessionGrantedAll.get()) return true;
        if (toolType == null) return false;
        return sessionGrantedTypes.contains(toolType);
    }

    /** 是否已开启全量授权（供 controller 查询）。 */
    public boolean isSessionGrantedAll() {
        return sessionGrantedAll.get();
    }

    /** 当前已放行的工具类型快照（供 controller 查询）。 */
    public Set<String> getSessionGrantedTypes() {
        return Set.copyOf(sessionGrantedTypes);
    }

    @Override
    public void offerWarnMessage(String message) {
        if (message != null && !message.isBlank()) {
            offerSseEvent("warn", message);
        }
    }

    // ── 任务计划管理 ──────────────────────────────────────────────────────────

    @Override
    public void setCurrentPlan(AiPlan plan) {
        if (plan != null) {
            planHistory.add(plan);
            offerSseEvent("plan", plan);
        }
    }

    @Override
    public AiPlan getCurrentPlan() {
        if (planHistory.isEmpty()) return null;
        return planHistory.get(planHistory.size() - 1);
    }

    @Override
    public List<AiPlan> getPlanHistory() {
        return Collections.unmodifiableList(planHistory);
    }

    @Override
    public void notifyPlanUpdated() {
        AiPlan plan = getCurrentPlan();
        if (plan != null) {
            offerSseEvent("plan", plan);
        }
    }
}
