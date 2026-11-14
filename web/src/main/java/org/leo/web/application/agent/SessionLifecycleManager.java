package org.leo.web.application.agent;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.leo.core.entity.User;
import org.leo.core.infrastructure.persistence.session.AgentNodeSessionWorkDirUtil;
import org.leo.core.security.AccessPolicy;
import org.leo.core.session.AgentNodeSession;
import org.leo.core.session.AgentNodeSessionContainer;
import org.leo.service.discovery.NetworkProbeWorkflowService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Application-level entry point for Agent session lifecycle operations.
 *
 * <p>The core container remains the low-level registry. Web controllers,
 * schedulers and session creators use this service so registration, explicit
 * destruction and expiration follow one path and fire the same destroy hooks.
 */
@Service
public final class SessionLifecycleManager {

    private static final Logger logger = LoggerFactory.getLogger(SessionLifecycleManager.class);
    private final NetworkProbeWorkflowService workflowService;
    private final Consumer<String> sessionDestroyListener = this::cleanupDestroyedSession;

    public SessionLifecycleManager(NetworkProbeWorkflowService workflowService) {
        this.workflowService = workflowService;
    }

    @PostConstruct
    void registerDestroyHook() {
        AgentNodeSessionContainer.registerDestroyListener(sessionDestroyListener);
    }

    @PreDestroy
    void unregisterDestroyHook() {
        AgentNodeSessionContainer.unregisterDestroyListener(sessionDestroyListener);
    }

    public void register(AgentNodeSession session) {
        if (session == null) throw new IllegalArgumentException("session不能为空");
        String sessionId = requireId(session.getSessionId());
        AgentNodeSessionContainer.addSession(sessionId, session);
    }

    /**
     * Destroys a session and, when requested, its session work directory.
     * Removing from the container fires all registered module cleanup hooks.
     */
    public boolean destroy(String sessionId, boolean deleteWorkDir) {
        String normalized = requireId(sessionId);
        if (deleteWorkDir && !AgentNodeSessionWorkDirUtil.deleteSessionWorkDir(normalized)) {
            throw new IllegalStateException("删除会话目录失败: " + normalized);
        }
        return AgentNodeSessionContainer.removeSession(normalized);
    }

    public List<String> evictExpired(long maxIdleMs) {
        if (maxIdleMs < 1L) throw new IllegalArgumentException("maxIdleMs必须大于0");
        long cutoff = System.currentTimeMillis() - maxIdleMs;
        List<String> expired = new ArrayList<>();
        for (Map.Entry<String, AgentNodeSession> entry
                : AgentNodeSessionContainer.getAllSessions().entrySet()) {
            AgentNodeSession session = entry.getValue();
            if (session != null && session.getLastActiveTime() < cutoff
                    && destroy(entry.getKey(), true)) {
                expired.add(entry.getKey());
            }
        }
        return expired;
    }

    public Map<String, Integer> countByProject(User user) {
        Map<String, Integer> counts = new HashMap<>();
        for (AgentNodeSession session : AgentNodeSessionContainer.getAllSessions().values()) {
            if (session == null) continue;
            String projectId = session.getProjectId();
            if (projectId != null && AccessPolicy.canAccessSession(session, user)) {
                counts.merge(projectId, 1, Integer::sum);
            }
        }
        return counts;
    }

    /** Removes a deleted project association while keeping sessions and their resources alive. */
    public int detachProject(String projectId) {
        int changed = 0;
        for (AgentNodeSession session : AgentNodeSessionContainer.getAllSessions().values()) {
            if (session != null && projectId.equals(session.getProjectId())) {
                session.setProjectId(null);
                changed++;
            }
        }
        return changed;
    }

    private String requireId(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId不能为空");
        }
        return sessionId.trim();
    }

    private void cleanupDestroyedSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return;
        workflowService.cleanupSession(sessionId);
        logger.info("[SessionLifecycle] stopped session {}; scan history retained", sessionId);
    }
}
