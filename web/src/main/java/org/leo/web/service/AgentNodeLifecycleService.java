package org.leo.web.service;

import org.leo.core.entity.Agent;
import org.leo.core.entity.User;
import org.leo.core.agent.AbstractAgentNode;
import org.leo.core.agent.capability.HostScopedCapable;
import org.leo.core.agent.capability.LoadedComponentCacheCapable;
import org.leo.core.session.AgentNodeSession;
import org.leo.core.session.AgentNodeSessionContainer;
import org.leo.core.repository.session.AgentReconRepository;
import org.leo.core.rpc.AgentRpcEnvelopeMapper;
import org.leo.service.AgentService;
import org.leo.service.agentnode.AgentNodeFactory;
import org.leo.web.dto.agentnode.AgentInitResponse;
import org.leo.web.exception.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Agent 会话生命周期服务。
 *
 * <p>把连接构建、会话创建等流程从 Controller 中拆出，
 * Controller 只保留 HTTP 参数和响应编排。
 * AI 对话按需创建，避免每次连接都持久化一个空对话。
 */
@Service
public class AgentNodeLifecycleService {

    private static final Logger logger = LoggerFactory.getLogger(AgentNodeLifecycleService.class);

    private final AgentService agentService;
    private final AgentNodeFactory agentNodeFactory;
    private final AgentCacheService cacheService;
    private final AgentReconRepository reconRepository;
    private final SessionLifecycleManager sessionLifecycleManager;

    public AgentNodeLifecycleService(AgentService agentService,
                                      AgentNodeFactory agentNodeFactory,
                                      AgentCacheService cacheService,
                                      AgentReconRepository reconRepository,
                                      SessionLifecycleManager sessionLifecycleManager) {
        this.agentService = agentService;
        this.agentNodeFactory = agentNodeFactory;
        this.cacheService = cacheService;
        this.reconRepository = reconRepository;
        this.sessionLifecycleManager = sessionLifecycleManager;
    }

    public AgentInitResponse initLiveSession(Agent agent, User user,
                                              String projectId, String selectedHostId) throws Exception {
        String sessionId = UUID.randomUUID().toString();

        AbstractAgentNode node = null;
        boolean connectionOk = false;
        int attempts = selectedHostId == null || selectedHostId.isBlank() ? 1 : 8;
        for (int attempt = 0; attempt < attempts && !connectionOk; attempt++) {
            node = agentNodeFactory.createLiveNode(agent, user);
            connectionOk = doInitConn(node, sessionId,
                    user != null ? user.getUserId() : null, projectId, selectedHostId);
            if (!connectionOk) {
                try {
                    node.close();
                } catch (Exception ex) {
                    logger.debug("关闭未命中目标 HostId 的节点失败: {}", ex.getMessage());
                }
            }
        }
        if (!connectionOk) {
            logger.warn("Agent初始化失败，无主机回复，agentId: {}", agent.getAgentId());
            throw ApiException.serverError("Agent初始化失败，无主机回复");
        }

        agentService.updateLastHeartbeat(agent.getAgentId());
        logger.info("Agent初始化成功，agentId: {}, sessionId: {}", agent.getAgentId(), sessionId);
        AgentNodeSession session = AgentNodeSessionContainer.getSession(sessionId);
        return new AgentInitResponse(sessionId, projectId, false,
                session != null ? session.getCapabilities() : List.of());
    }

    public AgentInitResponse initCacheSession(Agent agent, User user, String projectId, String selectedHostId) {
        String userId = user.getUserId();
        String agentId = agent.getAgentId();
        String hostId = cacheService.requireSelectedHostId(userId, agentId, selectedHostId);

        String sessionId = UUID.randomUUID().toString();
        AgentNodeSession session = new AgentNodeSession(sessionId, null,
                System.currentTimeMillis(), userId);
        session.setCacheMode(true);
        session.setAgentId(agentId);
        session.setProjectId(projectId);
        if (hostId != null) session.bindHostId(hostId);

        try {
            String savedSummary = reconRepository.load(userId, agentId);
            if (savedSummary != null) {
                session.setReconSummary(savedSummary);
            }
        } catch (Exception ex) {
            logger.warn("缓存模式回填数据失败, agentId={}: {}", agentId, ex.getMessage());
        }

        sessionLifecycleManager.register(session);

        logger.info("缓存模式 session 已创建, agentId={}, sessionId={}", agentId, sessionId);
        return new AgentInitResponse(sessionId, projectId, true, session.getCapabilities());
    }

    private boolean doInitConn(AbstractAgentNode node, String sessionId,
                               String userId, String projectId, String selectedHostId) throws Exception {
        Map<String, Object> result = node.testConnection();
        if (!AgentRpcEnvelopeMapper.isCurrentPingResult(result)) return false;

        String hostId = ((String) result.get("hostId")).trim();
        List<?> components = (List<?>) result.get("components");
        if (selectedHostId != null && !selectedHostId.isBlank()
                && !selectedHostId.trim().equals(hostId)) {
            return false;
        }

        seedNodeContext(node, hostId, components);

        AgentNodeSession session = new AgentNodeSession(sessionId, node,
                System.currentTimeMillis(), userId);
        session.setProjectId(projectId);
        session.bindHostId(hostId);
        loadPersistedReconSummary(session, node, userId);
        sessionLifecycleManager.register(session);

        logger.debug("测试连接成功，hostId: {}, sessionId: {}", hostId, sessionId);
        return true;
    }

    private void seedNodeContext(AbstractAgentNode node, String hostId, List<?> components) {
        if (node instanceof LoadedComponentCacheCapable componentCache) {
            componentCache.addLoadedComponent(hostId, parseLoadedComponents(components));
        }
        if (node instanceof HostScopedCapable hostScopedNode) {
            hostScopedNode.setHostId(hostId);
        }
    }

    private Set<String> parseLoadedComponents(List<?> components) {
        Set<String> result = new LinkedHashSet<>();
        for (Object item : components) result.add(((String) item).trim());
        return result;
    }

    private void loadPersistedReconSummary(AgentNodeSession session, AbstractAgentNode node, String userId) {
        try {
            if (node.getAgent() == null) {
                return;
            }
            String agentId = node.getAgent().getAgentId();
            String savedSummary = reconRepository.load(userId, agentId);
            if (savedSummary != null) {
                session.setReconSummary(savedSummary);
                logger.debug("已回填侦察摘要, agentId={}, length={}", agentId, savedSummary.length());
            }
        } catch (Exception ex) {
            logger.warn("回填侦察摘要失败, sessionId={}: {}", session.getSessionId(), ex.getMessage());
        }
    }

}
