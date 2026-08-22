package org.leo.core.ai;

import org.leo.core.entity.AiExecutionPolicy;
import org.leo.core.entity.AiPlan;
import org.leo.core.entity.AiRuntimeStats;
import org.leo.core.entity.AiSseEvent;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Consumer;

/**
 * Platform 与 Puppet Agent 共用的瞬时运行时状态。
 *
 * <p>数据库 Turn 是持久化权威；本类只保存执行句柄、取消信号、事件队列和当前
 * 进程内计划。所有作用域包装类都复用这一实现，避免两套状态机逐渐分叉。
 */
public class AiRuntimeState implements AiEventStreamRuntime {

    private static final int MAX_TURNS_WARN = 25;

    private volatile Thread executingThread;
    private final AtomicBoolean executionClaimed = new AtomicBoolean(false);
    private final AtomicBoolean stopRequested = new AtomicBoolean(false);
    private final AtomicBoolean waitingForUserInput = new AtomicBoolean(false);
    private final AtomicBoolean terminalControlRequested = new AtomicBoolean(false);
    private volatile String terminalControlName;
    private volatile String runStatus = AiRunStatus.IDLE;
    private volatile String activeTurnId;
    private volatile String activeItemId;
    private volatile String activeRunId;
    private volatile String activeLeaseToken;
    private volatile String stopReason;
    private volatile long taskTimeoutAt;
    private volatile Runnable stopCallback;

    private final ReentrantReadWriteLock toolGate = new ReentrantReadWriteLock(true);
    private final LinkedBlockingQueue<AiSseEvent> sseEventQueue = new LinkedBlockingQueue<>();
    private final AtomicLong sseEventSeq = new AtomicLong(0);
    private volatile long currentRunStartSeq;
    private volatile Consumer<AiSseEvent> eventJournalSink = event -> {};

    private final AtomicInteger turnCount = new AtomicInteger(0);
    private volatile AiRuntimeStats runtimeStats = new AiRuntimeStats();
    private volatile AiExecutionPolicy executionPolicy = AiExecutionPolicy.defaultPolicy();
    private final List<AiPlan> planHistory = new CopyOnWriteArrayList<>();
    private final Set<String> activatedSkills = ConcurrentHashMap.newKeySet();

    @Override
    public boolean claimExecution() {
        boolean claimed = executionClaimed.compareAndSet(false, true);
        if (claimed) {
            currentRunStartSeq = sseEventSeq.get();
            stopRequested.set(false);
            waitingForUserInput.set(false);
            terminalControlRequested.set(false);
            terminalControlName = null;
            runStatus = AiRunStatus.RUNNING;
            stopReason = null;
            taskTimeoutAt = 0L;
        }
        return claimed;
    }

    @Override
    public void markExecuting(Thread thread) {
        if (!executionClaimed.get()) claimExecution();
        executingThread = thread;
    }

    @Override
    public void clearExecuting() {
        executingThread = null;
        stopCallback = null;
        executionClaimed.set(false);
        stopRequested.set(false);
    }

    @Override public boolean isStopRequested() { return stopRequested.get(); }
    public boolean isWaitingForUserInput() { return waitingForUserInput.get(); }
    @Override public boolean isExecuting() { return executionClaimed.get() || executingThread != null; }
    @Override public String getRunStatus() { return runStatus; }
    @Override public String getActiveTurnId() { return activeTurnId; }
    @Override public void bindActiveTurnId(String turnId) { activeTurnId = turnId; }
    @Override public String getActiveItemId() { return activeItemId; }
    @Override public void bindActiveItemId(String itemId) { activeItemId = itemId; }
    @Override public String getActiveRunId() { return activeRunId; }
    @Override public void bindActiveRunId(String runId) { activeRunId = runId; }
    @Override public String getActiveLeaseToken() { return activeLeaseToken; }
    @Override public void bindActiveLeaseToken(String leaseToken) { activeLeaseToken = leaseToken; }
    @Override public String getStopReason() { return stopReason; }
    public long getTaskTimeoutAt() { return taskTimeoutAt; }
    public void setTaskTimeoutAt(long timeoutAt) { taskTimeoutAt = Math.max(0L, timeoutAt); }

    public void stop(String reason) {
        stopRequested.set(true);
        stopReason = reason != null && !reason.isBlank() ? reason : "已停止";
        runStatus = AiRunStatus.CANCELLED;
        Runnable callback = stopCallback;
        if (callback != null) {
            try { callback.run(); } catch (Exception ignored) { }
        }
        Thread thread = executingThread;
        if (thread != null) thread.interrupt();
    }

    @Override public void setStopCallback(Runnable callback) { stopCallback = callback; }

    @Override
    public void markCompleted() {
        runStatus = waitingForUserInput.get()
                ? AiRunStatus.WAITING_FOR_USER : AiRunStatus.COMPLETED;
        taskTimeoutAt = 0L;
    }

    public void markWaitingForUserInput() {
        waitingForUserInput.set(true);
        runStatus = AiRunStatus.WAITING_FOR_USER;
        taskTimeoutAt = 0L;
    }

    @Override
    public void markFailed() {
        waitingForUserInput.set(false);
        runStatus = AiRunStatus.FAILED;
        taskTimeoutAt = 0L;
    }

    @Override
    public void markCancelled() {
        waitingForUserInput.set(false);
        runStatus = AiRunStatus.CANCELLED;
        taskTimeoutAt = 0L;
    }

    /**
     * 为单个工具调用取得运行时闸门。独占控制动作使用写锁，普通工具使用读锁。
     * 终止控制动作成功后，后续普通工具会被拒绝。
     */
    public ToolLease acquireToolLease(boolean exclusive) {
        Lock lock = exclusive ? toolGate.writeLock() : toolGate.readLock();
        lock.lock();
        if (terminalControlRequested.get()) {
            lock.unlock();
            throw new IllegalStateException("终止控制动作已生效: " + terminalControlName);
        }
        return new ToolLease(lock);
    }

    public void markTerminalControl(String controlName) {
        terminalControlName = controlName;
        terminalControlRequested.set(true);
    }

    public boolean isTerminalControlRequested() { return terminalControlRequested.get(); }
    public String getTerminalControlName() { return terminalControlName; }

    @Override public LinkedBlockingQueue<AiSseEvent> getAiSseEventQueue() { return sseEventQueue; }
    public LinkedBlockingQueue<AiSseEvent> getSseEventQueue() { return sseEventQueue; }

    public AiSseEvent offerSseEvent(String name, Object data) {
        AiSseEvent event = recordSseEvent(name, data);
        sseEventQueue.offer(event);
        return event;
    }

    @Override public AiSseEvent recordSseEvent(String name, Object data) {
        return recordSseEvent(name, data, null);
    }

    @Override
    public AiSseEvent recordSseEvent(String name, Object data, String subagentInvocationId) {
        AiSseEvent event = new AiSseEvent(sseEventSeq.incrementAndGet(),
                System.currentTimeMillis(), name, data, subagentInvocationId,
                activeTurnId, activeItemId, activeRunId);
        eventJournalSink.accept(event);
        return event;
    }

    @Override
    public void configureEventJournal(long persistedLastSeq, Consumer<AiSseEvent> eventSink) {
        sseEventSeq.accumulateAndGet(Math.max(0L, persistedLastSeq), Math::max);
        eventJournalSink = eventSink != null ? eventSink : event -> {};
    }

    @Override public long getLastSseEventSeq() { return sseEventSeq.get(); }
    @Override public long getCurrentRunStartSeq() { return currentRunStartSeq; }

    public void clearSseEvents() {
        sseEventQueue.clear();
        currentRunStartSeq = sseEventSeq.get();
        activeTurnId = null;
        activeItemId = null;
        activeRunId = null;
        activeLeaseToken = null;
    }

    public void offerWarnMessage(String message) {
        if (message != null && !message.isBlank()) offerSseEvent("warn", message);
    }

    public String incrementAndCheckTurnCount() {
        int count = turnCount.incrementAndGet();
        return count >= MAX_TURNS_WARN
                ? "对话已进行 " + count + " 轮，上下文接近上限，建议新建对话以保持最佳效果。"
                : null;
    }

    public void resetTurnCount() { turnCount.set(0); }
    public int getTurnCount() { return turnCount.get(); }
    public AiRuntimeStats getRuntimeStats() { return runtimeStats; }
    public void resetRuntimeStats() { runtimeStats = new AiRuntimeStats(); }

    public AiExecutionPolicy getExecutionPolicy() {
        AiExecutionPolicy policy = executionPolicy;
        return policy != null ? policy : AiExecutionPolicy.defaultPolicy();
    }

    public void setExecutionPolicy(AiExecutionPolicy policy) {
        executionPolicy = policy != null ? policy : AiExecutionPolicy.defaultPolicy();
    }

    public void addPlan(AiPlan plan) {
        if (plan != null) planHistory.add(plan);
    }

    public AiPlan getCurrentPlan() {
        return planHistory.isEmpty() ? null : planHistory.get(planHistory.size() - 1);
    }

    public List<AiPlan> getPlanHistory() {
        return Collections.unmodifiableList(planHistory);
    }

    /** 当前任务已激活的 Skill；供动态 ToolProvider 按需追加能力。 */
    public void activateSkill(String name) {
        if (name != null && !name.isBlank()) activatedSkills.add(name.trim());
    }

    public Set<String> getActivatedSkills() {
        return Set.copyOf(activatedSkills);
    }

    public void resetRuntimeState() {
        executingThread = null;
        stopCallback = null;
        executionClaimed.set(false);
        stopRequested.set(false);
        waitingForUserInput.set(false);
        terminalControlRequested.set(false);
        terminalControlName = null;
        runStatus = AiRunStatus.IDLE;
        stopReason = null;
        taskTimeoutAt = 0L;
        activatedSkills.clear();
        resetTurnCount();
        clearSseEvents();
    }

    public static final class ToolLease implements AutoCloseable {
        private final Lock lock;
        private boolean closed;

        private ToolLease(Lock lock) { this.lock = lock; }

        @Override
        public void close() {
            if (!closed) {
                closed = true;
                lock.unlock();
            }
        }
    }
}
