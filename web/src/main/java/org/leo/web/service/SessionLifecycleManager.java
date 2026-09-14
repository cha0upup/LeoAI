package org.leo.web.service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.leo.core.session.PuppetNodeSession;
import org.leo.core.session.PuppetNodeSessionContainer;
import org.leo.core.util.session.PuppetNodeSessionWorkDirUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.function.Consumer;

/**
 * Application-level entry point for Puppet session lifecycle operations.
 *
 * <p>The core container remains the low-level registry. Web controllers,
 * schedulers and session creators use this service so registration, explicit
 * destruction and expiration follow one path and fire the same destroy hooks.
 */
@Service
public final class SessionLifecycleManager {

    private static final Logger logger = LoggerFactory.getLogger(SessionLifecycleManager.class);
    private final NetworkProbeWorkflowService workflowService;
    private final NetworkProbeResultStore resultStore;
    private final Consumer<String> sessionDestroyListener = this::cleanupDestroyedSession;

    @Autowired
    public SessionLifecycleManager(NetworkProbeWorkflowService workflowService,
                                   NetworkProbeResultStore resultStore) {
        this.workflowService = workflowService;
        this.resultStore = resultStore;
    }

    /** Compatibility constructor for unit tests that only exercise registration. */
    SessionLifecycleManager() {
        this.workflowService = null;
        this.resultStore = null;
    }

    @PostConstruct
    void registerDestroyHook() {
        PuppetNodeSessionContainer.registerDestroyListener(sessionDestroyListener);
    }

    @PreDestroy
    void unregisterDestroyHook() {
        PuppetNodeSessionContainer.unregisterDestroyListener(sessionDestroyListener);
    }

    public PuppetNodeSession get(String sessionId) {
        return PuppetNodeSessionContainer.getSession(requireId(sessionId));
    }

    public void register(PuppetNodeSession session) {
        if (session == null) throw new IllegalArgumentException("session不能为空");
        String sessionId = requireId(session.getSessionId());
        PuppetNodeSessionContainer.addSession(sessionId, session);
    }

    /**
     * Destroys a session and, when requested, its session work directory.
     * Removing from the container fires all registered module cleanup hooks.
     */
    public boolean destroy(String sessionId, boolean deleteWorkDir) {
        String normalized = requireId(sessionId);
        if (deleteWorkDir && !PuppetNodeSessionWorkDirUtil.deleteSessionWorkDir(normalized)) {
            throw new IllegalStateException("删除会话目录失败: " + normalized);
        }
        return PuppetNodeSessionContainer.removeSession(normalized);
    }

    public List<String> evictExpired(long maxIdleMs) {
        if (maxIdleMs < 1L) throw new IllegalArgumentException("maxIdleMs必须大于0");
        long cutoff = System.currentTimeMillis() - maxIdleMs;
        List<String> expired = new ArrayList<>();
        for (Map.Entry<String, PuppetNodeSession> entry
                : PuppetNodeSessionContainer.getAllSession().entrySet()) {
            PuppetNodeSession session = entry.getValue();
            if (session != null && session.getLastActiveTime() < cutoff
                    && destroy(entry.getKey(), true)) {
                expired.add(entry.getKey());
            }
        }
        return expired;
    }

    private String requireId(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId不能为空");
        }
        return sessionId.trim();
    }

    private void cleanupDestroyedSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return;
        if (workflowService != null) workflowService.cleanupSession(sessionId);
        if (resultStore != null) {
            int deleted = resultStore.deleteTasksBySession(sessionId);
            logger.info("[SessionLifecycle] cleaned session {}: {} scan task(s)", sessionId, deleted);
        }
    }
}
