package org.leo.ai.puppetnode;

import org.leo.ai.agent.AiStateAccessor;
import org.leo.core.entity.AiConfirmationRequest;
import org.leo.core.entity.AiExecutionPolicy;
import org.leo.core.entity.AiPlan;
import org.leo.core.entity.AiRuntimeStats;
import org.leo.core.entity.AiSseEvent;
import org.leo.core.session.AiThread;
import org.leo.core.session.PuppetNodeSession;
import org.leo.core.session.PuppetNodeSessionContainer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * 基于 PuppetNodeSession 的 {@link AiStateAccessor} 实现。
 *
 * <p>通过 sessionId + threadId 懒加载指定 {@link AiThread}。不要经由
 * {@code activeThreadId} 访问状态，否则同一个 puppet 会话下并发对话时，
 * 日志、确认请求和授权状态会串到最近切换的活跃线程。
 *
 * <p>若 Session 或 Thread 不存在或已销毁，{@link #isValid()} 返回 {@code false}，
 * 所有写入操作均为空操作，不会抛出异常。
 */
public class PuppetNodeAiStateAccessor implements AiStateAccessor {

    private static final Logger log = LoggerFactory.getLogger(PuppetNodeAiStateAccessor.class);

    private final String sessionId;
    private final String threadId;

    public PuppetNodeAiStateAccessor(String sessionId, String threadId) {
        this.sessionId = sessionId;
        this.threadId = threadId;
    }

    @Override
    public AiRuntimeStats getRuntimeStats() {
        AiThread thread = resolveThread();
        return thread != null ? thread.getRuntimeStats() : new AiRuntimeStats();
    }

    @Override
    public AiExecutionPolicy getExecutionPolicy() {
        AiThread thread = resolveThread();
        return thread != null ? thread.getExecutionPolicy() : AiExecutionPolicy.defaultPolicy();
    }

    @Override
    public boolean isValid() {
        return resolveThread() != null;
    }

    @Override
    public boolean isStopRequested() {
        AiThread thread = resolveThread();
        return thread != null && thread.isStopRequested();
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
        AiThread thread = resolveThread();
        if (thread == null) {
            return CompletableFuture.completedFuture(false);
        }
        long requestedAt = System.currentTimeMillis();
        Long expiresAt = timeoutMs > 0 ? requestedAt + timeoutMs : null;
        AiConfirmationRequest req = new AiConfirmationRequest(
                callId, toolName, toolType, toolTypeLabel, argsPreview, impact,
                requestedAt, expiresAt, timeoutMs > 0 ? timeoutMs : null);
        return thread.registerAndAwaitConfirmation(req);
    }

    @Override
    public boolean resolveConfirmation(String callId, boolean approved) {
        AiThread thread = resolveThread();
        return thread != null && thread.resolveConfirmation(callId, approved);
    }

    @Override
    public void cancelAllPendingConfirmations() {
        AiThread thread = resolveThread();
        if (thread != null) {
            thread.cancelAllPendingConfirmations();
        }
    }

    // ── 会话级工具授权 ────────────────────────────────────────────────────────────

    @Override
    public void grantSessionType(String toolType) {
        AiThread thread = resolveThread();
        if (thread != null) {
            thread.grantSessionType(toolType);
        }
    }

    @Override
    public void grantSessionAll() {
        AiThread thread = resolveThread();
        if (thread != null) {
            thread.grantSessionAll();
        }
    }

    @Override
    public boolean isSessionGranted(String toolType) {
        AiThread thread = resolveThread();
        return thread != null && thread.isSessionGranted(toolType);
    }

    @Override
    public void offerWarnMessage(String message) {
        AiThread thread = resolveThread();
        if (thread != null) {
            thread.offerSystemWarn(message);
        }
    }

    // ── 任务计划管理 ──────────────────────────────────────────────────────────

    @Override
    public void setCurrentPlan(AiPlan plan) {
        AiThread thread = resolveThread();
        if (thread != null && plan != null) {
            thread.addPlan(plan);
            thread.offerSseEvent("plan", plan);
        }
    }

    @Override
    public AiPlan getCurrentPlan() {
        AiThread thread = resolveThread();
        return thread != null ? thread.getCurrentPlan() : null;
    }

    @Override
    public List<AiPlan> getPlanHistory() {
        AiThread thread = resolveThread();
        return thread != null ? thread.getPlanHistory() : Collections.emptyList();
    }

    @Override
    public void notifyPlanUpdated() {
        AiThread thread = resolveThread();
        if (thread == null) return;
        AiPlan plan = thread.getCurrentPlan();
        if (plan != null) {
            thread.offerSseEvent("plan", plan);
        }
    }

    @Override
    public LinkedBlockingQueue<AiSseEvent> getAiSseEventQueue() {
        AiThread thread = resolveThread();
        return thread != null ? thread.getSseEventQueue() : null;
    }

    private AiThread resolveThread() {
        PuppetNodeSession session = resolveSession();
        if (session == null) {
            log.warn("AiStateAccessor: session 不存在，sessionId={}, threadId={}", sessionId, threadId);
            return null;
        }
        if (threadId == null || threadId.isBlank()) {
            log.warn("AiStateAccessor: threadId 为空，sessionId={}", sessionId);
            return null;
        }
        AiThread thread = session.getAiThread(threadId);
        if (thread == null) {
            log.warn("AiStateAccessor: thread 不存在，sessionId={}, threadId={}", sessionId, threadId);
        }
        return thread;
    }

    private PuppetNodeSession resolveSession() {
        if (sessionId == null || sessionId.isBlank()) return null;
        return PuppetNodeSessionContainer.getSession(sessionId);
    }
}
