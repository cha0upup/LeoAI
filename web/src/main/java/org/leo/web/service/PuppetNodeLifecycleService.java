package org.leo.web.service;

import org.leo.core.entity.Puppet;
import org.leo.core.entity.User;
import org.leo.core.net.Communication;
import org.leo.core.net.layer.UrlStrategy;
import org.leo.core.net.layer.PaddingStrategy;
import org.leo.core.net.layer.HeaderNoiseStrategy;
import org.leo.core.net.layer.TlsFingerprintStrategy;
import org.leo.core.net.impl.HttpCommunication;
import org.leo.core.puppet.impl.JavaPuppetNode;
import org.leo.core.session.PuppetNodeSession;
import org.leo.core.session.PuppetNodeSessionContainer;
import org.leo.core.util.json.JsonUtil;
import org.leo.core.util.session.PuppetNodeSessionWorkDirUtil;
import org.leo.service.PuppetConnService;
import org.leo.service.PuppetService;
import org.leo.web.dto.puppetnode.PuppetInitResponse;
import org.leo.web.exception.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Puppet 会话生命周期服务。
 *
 * <p>把连接构建、会话创建、AI 初始线程等流程从 Controller 中拆出，
 * Controller 只保留 HTTP 参数和响应编排。
 */
@Service
public class PuppetNodeLifecycleService {

    private static final Logger logger = LoggerFactory.getLogger(PuppetNodeLifecycleService.class);

    private final PuppetConnService puppetConnService;
    private final PuppetService puppetService;

    public PuppetNodeLifecycleService(PuppetConnService puppetConnService,
                                      PuppetService puppetService) {
        this.puppetConnService = puppetConnService;
        this.puppetService = puppetService;
    }

    public PuppetInitResponse initLiveSession(Puppet puppet, User user) throws Exception {
        Puppet transportPuppet = puppetConnService.resolveTransportPuppet(puppet);
        Communication communication = puppetConnService.getCommunication(
                transportPuppet, puppetConnService.getProxy(transportPuppet));

        if (communication == null) {
            throw ApiException.serverError("无法创建通信连接，puppetId: " + puppet.getPuppetId());
        }

        // 应用 TLS 指纹伪装策略（需在 initService 前）
        applyTlsFingerprintStrategy(puppet, communication);

        String sessionId = UUID.randomUUID().toString();

        JavaPuppetNode javaPuppetNode = new JavaPuppetNode();
        javaPuppetNode.setPuppet(puppet);
        javaPuppetNode.setUser(user);
        puppetConnService.buildRequestAndResponseChain(puppet, javaPuppetNode);
        javaPuppetNode.setCommunication(communication);
        if (puppet.getMaxReqCount() != null && puppet.getMaxReqCount() > 0) {
            javaPuppetNode.setMaxReqCount(puppet.getMaxReqCount());
        }
        javaPuppetNode.initService();
        applyUrlStrategy(puppet, javaPuppetNode);
        applyPaddingStrategy(puppet, javaPuppetNode);
        applyHeaderNoiseStrategy(puppet, javaPuppetNode);

        boolean connectionOk = doInitConn(javaPuppetNode, sessionId, user != null ? user.getUserId() : null);
        if (!connectionOk) {
            logger.warn("Puppet初始化失败，无主机回复，puppetId: {}", puppet.getPuppetId());
            throw ApiException.serverError("Puppet初始化失败，无主机回复");
        }

        puppetService.updateLastHeartbeat(puppet.getPuppetId());
        logger.info("Puppet初始化成功，puppetId: {}, sessionId: {}", puppet.getPuppetId(), sessionId);
        return new PuppetInitResponse(sessionId, false);
    }

    public PuppetInitResponse initCacheSession(Puppet puppet, User user) {
        String userId = user.getUserId();
        String puppetId = puppet.getPuppetId();
        if (!PuppetNodeSessionWorkDirUtil.hasPuppetCache(userId, puppetId)) {
            throw ApiException.badRequest("无本地缓存，无法进入缓存模式");
        }

        String sessionId = UUID.randomUUID().toString();
        PuppetNodeSession session = new PuppetNodeSession(sessionId, null,
                System.currentTimeMillis(), userId);
        session.setCacheMode(true);
        session.setPuppetId(puppetId);

        try {
            String savedSummary = PuppetNodeSessionWorkDirUtil.loadReconSummary(userId, puppetId);
            if (savedSummary != null) {
                session.setReconSummary(savedSummary);
            }
        } catch (Exception ex) {
            logger.warn("缓存模式回填数据失败, puppetId={}: {}", puppetId, ex.getMessage());
        }

        PuppetNodeSessionContainer.addSession(sessionId, session);
        createInitialAiThread(session, puppetId);

        logger.info("缓存模式 session 已创建, puppetId={}, sessionId={}", puppetId, sessionId);
        return new PuppetInitResponse(sessionId, true);
    }

    private boolean doInitConn(JavaPuppetNode javaPuppetNode, String sessionId, String userId) throws Exception {
        int maxCount = javaPuppetNode.getMaxReqCount() > 0 ? javaPuppetNode.getMaxReqCount() : 1;
        String hostId = null;

        for (int i = 0; i < maxCount; i++) {
            Map<String, Object> result = javaPuppetNode.testConnection();
            if (result != null && Integer.valueOf(200).equals(result.get("code"))) {
                hostId = (String) result.get("hostId");
                if (hostId != null) {
                    Set<String> loadedComponent = new HashSet<String>(Arrays.asList((String[]) result.get("components")));
                    javaPuppetNode.addLoadedComponent(hostId, loadedComponent);
                    javaPuppetNode.setHostId(hostId);

                    PuppetNodeSession session = new PuppetNodeSession(sessionId, javaPuppetNode,
                            System.currentTimeMillis(), userId);
                    session.setCurrentHostId(hostId);

                    loadPersistedReconSummary(session, javaPuppetNode, userId);
                    createInitialAiThread(session, javaPuppetNode.getPuppet().getPuppetId());

                    PuppetNodeSessionContainer.addSession(session.getSessionId(), session);

                    logger.debug("测试连接成功，hostId: {}, sessionId: {}", hostId, sessionId);
                    // 无论是否负载均衡，首次成功即返回（避免重复创建 session）
                    return true;
                }
            }
        }
        return false;
    }

    private void loadPersistedReconSummary(PuppetNodeSession session, JavaPuppetNode javaPuppetNode, String userId) {
        try {
            String puppetId = javaPuppetNode.getPuppet().getPuppetId();
            String savedSummary = PuppetNodeSessionWorkDirUtil.loadReconSummary(userId, puppetId);
            if (savedSummary != null) {
                session.setReconSummary(savedSummary);
                logger.debug("已回填侦察摘要, puppetId={}, length={}", puppetId, savedSummary.length());
            }
        } catch (Exception ex) {
            logger.warn("回填侦察摘要失败, sessionId={}: {}", session.getSessionId(), ex.getMessage());
        }
    }

    private void createInitialAiThread(PuppetNodeSession session, String puppetId) {
        try {
            String initThreadId = UUID.randomUUID().toString();
            session.createAiThread(initThreadId, "对话 1");
        } catch (Exception ex) {
            logger.warn("创建初始 AI 线程失败, puppetId={}, sessionId={}: {}",
                    puppetId, session.getSessionId(), ex.getMessage());
        }
    }

    /**
     * 从 Puppet 配置中解析并应用 URL 随机化策略
     */
    private void applyUrlStrategy(Puppet puppet, JavaPuppetNode javaPuppetNode) {
        String urlStrategyJson = puppet.getUrlStrategy();
        if (urlStrategyJson == null || urlStrategyJson.isBlank()) {
            return;
        }
        try {
            UrlStrategy strategy = (UrlStrategy) JsonUtil.fromJsonString(urlStrategyJson, UrlStrategy.class);
            if (strategy != null) {
                javaPuppetNode.setUrlStrategy(strategy);
                logger.debug("已应用 URL 随机化策略, puppetId={}, mode={}",
                        puppet.getPuppetId(), strategy.getMode());
            }
        } catch (Exception e) {
            logger.warn("解析 URL 随机化策略失败, puppetId={}: {}", puppet.getPuppetId(), e.getMessage());
        }
    }

    /**
     * 从 Puppet 配置中解析并应用请求体 Padding 策略
     */
    private void applyPaddingStrategy(Puppet puppet, JavaPuppetNode javaPuppetNode) {
        String paddingJson = puppet.getPaddingStrategy();
        if (paddingJson == null || paddingJson.isBlank()) {
            return;
        }
        try {
            PaddingStrategy strategy = (PaddingStrategy) JsonUtil.fromJsonString(paddingJson, PaddingStrategy.class);
            if (strategy != null) {
                javaPuppetNode.setPaddingStrategy(strategy);
                logger.debug("已应用 Padding 策略, puppetId={}, enabled={}, range=[{}-{}]",
                        puppet.getPuppetId(), strategy.isEnabled(),
                        strategy.getMinBytes(), strategy.getMaxBytes());
            }
        } catch (Exception e) {
            logger.warn("解析 Padding 策略失败, puppetId={}: {}", puppet.getPuppetId(), e.getMessage());
        }
    }

    /**
     * 从 Puppet 配置中解析并应用 Header 噪声注入策略
     */
    private void applyHeaderNoiseStrategy(Puppet puppet, JavaPuppetNode javaPuppetNode) {
        String noiseJson = puppet.getHeaderNoiseStrategy();
        if (noiseJson == null || noiseJson.isBlank()) {
            return;
        }
        try {
            HeaderNoiseStrategy strategy = (HeaderNoiseStrategy) JsonUtil.fromJsonString(noiseJson, HeaderNoiseStrategy.class);
            if (strategy != null) {
                javaPuppetNode.setHeaderNoiseStrategy(strategy);
                logger.debug("已应用 Header 噪声策略, puppetId={}, valueMode={}",
                        puppet.getPuppetId(), strategy.getValueMode());
            }
        } catch (Exception e) {
            logger.warn("解析 Header 噪声策略失败, puppetId={}: {}", puppet.getPuppetId(), e.getMessage());
        }
    }

    /**
     * 从 Puppet 配置中解析并应用 TLS 指纹伪装策略到 Communication。
     * 必须在 initService 之前调用，因为会重建 OkHttpClient。
     */
    private void applyTlsFingerprintStrategy(Puppet puppet, Communication communication) {
        String tlsJson = puppet.getTlsFingerprintStrategy();
        if (tlsJson == null || tlsJson.isBlank()) {
            return;
        }
        if (!(communication instanceof HttpCommunication)) {
            return;
        }
        try {
            TlsFingerprintStrategy strategy = (TlsFingerprintStrategy) JsonUtil.fromJsonString(tlsJson, TlsFingerprintStrategy.class);
            if (strategy != null && strategy.isEnabled()) {
                ((HttpCommunication) communication).setTlsFingerprintStrategy(strategy);
                logger.debug("已应用 TLS 指纹策略, puppetId={}, profile={}",
                        puppet.getPuppetId(), strategy.getProfile());
            }
        } catch (Exception e) {
            logger.warn("解析 TLS 指纹策略失败, puppetId={}: {}", puppet.getPuppetId(), e.getMessage());
        }
    }

}
