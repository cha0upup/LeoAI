package org.leo.ai.puppetnode;

import org.leo.ai.agent.AiStateAccessor;
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
import java.util.concurrent.LinkedBlockingQueue;

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
    public void offerWarnMessage(String message) {
        AiThread thread = resolveThread();
        if (thread != null) {
            thread.offerSystemWarn(message);
        }
    }

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
        if (session == null) return null;
        if (threadId == null || threadId.isBlank()) return null;
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
