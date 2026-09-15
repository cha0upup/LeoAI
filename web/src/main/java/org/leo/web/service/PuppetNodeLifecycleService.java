package org.leo.web.service;

import org.leo.core.entity.Puppet;
import org.leo.core.entity.User;
import org.leo.core.puppet.AbstractPuppetNode;
import org.leo.core.puppet.capability.HostScopedCapable;
import org.leo.core.puppet.capability.LoadedComponentCacheCapable;
import org.leo.core.session.PuppetNodeSession;
import org.leo.core.session.PuppetNodeSessionContainer;
import org.leo.core.repository.session.PuppetReconRepository;
import org.leo.service.PuppetService;
import org.leo.service.puppetnode.PuppetNodeFactory;
import org.leo.web.dto.puppetnode.PuppetInitResponse;
import org.leo.web.exception.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.lang.reflect.Array;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Puppet 会话生命周期服务。
 *
 * <p>把连接构建、会话创建等流程从 Controller 中拆出，
 * Controller 只保留 HTTP 参数和响应编排。
 * AI 对话按需创建，避免每次连接都持久化一个空对话。
 */
@Service
public class PuppetNodeLifecycleService {

    private static final Logger logger = LoggerFactory.getLogger(PuppetNodeLifecycleService.class);

    private final PuppetService puppetService;
    private final PuppetNodeFactory puppetNodeFactory;
    private final PuppetCacheService cacheService;
    private final PuppetReconRepository reconRepository;
    private final SessionLifecycleManager sessionLifecycleManager;

    public PuppetNodeLifecycleService(PuppetService puppetService,
                                      PuppetNodeFactory puppetNodeFactory,
                                      PuppetCacheService cacheService,
                                      PuppetReconRepository reconRepository,
                                      SessionLifecycleManager sessionLifecycleManager) {
        this.puppetService = puppetService;
        this.puppetNodeFactory = puppetNodeFactory;
        this.cacheService = cacheService;
        this.reconRepository = reconRepository;
        this.sessionLifecycleManager = sessionLifecycleManager;
    }

    public PuppetInitResponse initLiveSession(Puppet puppet, User user) throws Exception {
        return initLiveSession(puppet, user, null, null);
    }

    public PuppetInitResponse initLiveSession(Puppet puppet, User user, String projectId) throws Exception {
        return initLiveSession(puppet, user, projectId, null);
    }

    public PuppetInitResponse initLiveSession(Puppet puppet, User user,
                                              String projectId, String selectedHostId) throws Exception {
        String sessionId = UUID.randomUUID().toString();

        AbstractPuppetNode node = null;
        boolean connectionOk = false;
        int attempts = selectedHostId == null || selectedHostId.isBlank() ? 1 : 8;
        for (int attempt = 0; attempt < attempts && !connectionOk; attempt++) {
            node = puppetNodeFactory.createLiveNode(puppet, user);
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
            logger.warn("Puppet初始化失败，无主机回复，puppetId: {}", puppet.getPuppetId());
            throw ApiException.serverError("Puppet初始化失败，无主机回复");
        }

        puppetService.updateLastHeartbeat(puppet.getPuppetId());
        logger.info("Puppet初始化成功，puppetId: {}, sessionId: {}", puppet.getPuppetId(), sessionId);
        PuppetNodeSession session = PuppetNodeSessionContainer.getSession(sessionId);
        return new PuppetInitResponse(sessionId, projectId, false,
                session != null ? session.getCapabilities() : List.of());
    }

    public PuppetInitResponse initCacheSession(Puppet puppet, User user) {
        return initCacheSession(puppet, user, null, null);
    }

    public PuppetInitResponse initCacheSession(Puppet puppet, User user, String projectId) {
        return initCacheSession(puppet, user, projectId, null);
    }

    public PuppetInitResponse initCacheSession(Puppet puppet, User user, String projectId, String selectedHostId) {
        String userId = user.getUserId();
        String puppetId = puppet.getPuppetId();
        String hostId = cacheService.requireSelectedHostId(userId, puppetId, selectedHostId);

        String sessionId = UUID.randomUUID().toString();
        PuppetNodeSession session = new PuppetNodeSession(sessionId, null,
                System.currentTimeMillis(), userId);
        session.setCacheMode(true);
        session.setPuppetId(puppetId);
        session.setProjectId(projectId);
        if (hostId != null) session.bindHostId(hostId);

        try {
            String savedSummary = reconRepository.load(userId, puppetId);
            if (savedSummary != null) {
                session.setReconSummary(savedSummary);
            }
        } catch (Exception ex) {
            logger.warn("缓存模式回填数据失败, puppetId={}: {}", puppetId, ex.getMessage());
        }

        sessionLifecycleManager.register(session);

        logger.info("缓存模式 session 已创建, puppetId={}, sessionId={}", puppetId, sessionId);
        return new PuppetInitResponse(sessionId, projectId, true, session.getCapabilities());
    }

    private boolean doInitConn(AbstractPuppetNode node, String sessionId,
                               String userId, String projectId, String selectedHostId) throws Exception {
        Map<String, Object> result = node.testConnection();
        if (!isConnectionSuccess(result)) return false;

        String hostId = parseHostId(result.get("hostId"));
        if (requiresHostId(node) && hostId == null) {
            logger.debug("测试连接成功但缺少 hostId，sessionId={}", sessionId);
            return false;
        }
        if (selectedHostId != null && !selectedHostId.isBlank()
                && !selectedHostId.trim().equals(hostId)) {
            return false;
        }

        String boundHostId = selectedHostId != null && !selectedHostId.isBlank()
                ? selectedHostId.trim() : hostId;
        seedNodeContext(node, boundHostId, result.get("components"));

        PuppetNodeSession session = new PuppetNodeSession(sessionId, node,
                System.currentTimeMillis(), userId);
        session.setProjectId(projectId);
        if (boundHostId != null) session.bindHostId(boundHostId);
        loadPersistedReconSummary(session, node, userId);
        sessionLifecycleManager.register(session);

        logger.debug("测试连接成功，hostId: {}, sessionId: {}", hostId, sessionId);
        return true;
    }

    private boolean isConnectionSuccess(Map<String, Object> result) {
        if (result == null) {
            return false;
        }
        Object code = result.get("code");
        if (code instanceof Number number) {
            return number.intValue() == 200;
        }
        return "200".equals(String.valueOf(code));
    }

    private boolean requiresHostId(AbstractPuppetNode node) {
        return node instanceof HostScopedCapable || node instanceof LoadedComponentCacheCapable;
    }

    private void seedNodeContext(AbstractPuppetNode node, String hostId, Object components) {
        if (hostId == null) {
            return;
        }
        if (node instanceof LoadedComponentCacheCapable componentCache) {
            componentCache.addLoadedComponent(hostId, parseLoadedComponents(components));
        }
        if (node instanceof HostScopedCapable hostScopedNode) {
            hostScopedNode.setHostId(hostId);
        }
    }

    private String parseHostId(Object value) {
        if (value == null) {
            return null;
        }
        String hostId = String.valueOf(value).trim();
        return hostId.isBlank() ? null : hostId;
    }

    private Set<String> parseLoadedComponents(Object components) {
        Set<String> result = new LinkedHashSet<>();
        if (components == null) {
            return result;
        }
        if (components instanceof Collection<?> collection) {
            for (Object item : collection) {
                addComponentName(result, item);
            }
            return result;
        }
        Class<?> type = components.getClass();
        if (type.isArray()) {
            int length = Array.getLength(components);
            for (int i = 0; i < length; i++) {
                addComponentName(result, Array.get(components, i));
            }
            return result;
        }
        addComponentName(result, components);
        return result;
    }

    private void addComponentName(Set<String> target, Object value) {
        if (value == null) {
            return;
        }
        String name = String.valueOf(value).trim();
        if (!name.isBlank()) {
            target.add(name);
        }
    }

    private void loadPersistedReconSummary(PuppetNodeSession session, AbstractPuppetNode node, String userId) {
        try {
            if (node.getPuppet() == null) {
                return;
            }
            String puppetId = node.getPuppet().getPuppetId();
            String savedSummary = reconRepository.load(userId, puppetId);
            if (savedSummary != null) {
                session.setReconSummary(savedSummary);
                logger.debug("已回填侦察摘要, puppetId={}, length={}", puppetId, savedSummary.length());
            }
        } catch (Exception ex) {
            logger.warn("回填侦察摘要失败, sessionId={}: {}", session.getSessionId(), ex.getMessage());
        }
    }

}
