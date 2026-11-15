package org.leo.ai.service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.leo.ai.concurrent.AiBackgroundExecutor;
import org.leo.ai.util.AgentNodeSessionUtils;
import org.leo.ai.util.ToolResultUtils;
import org.leo.core.agent.capability.BasicInfoCapable;
import org.leo.core.agent.capability.CommandCapable;
import org.leo.core.agent.capability.NetworkInfoCapable;
import org.leo.core.session.AgentNodeSession;
import org.leo.core.session.AgentNodeSessionContainer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;

/**
 * 会话预热服务。
 *
 * <p>在 AI 线程创建或恢复时异步预填基础缓存（basicInfo、OS 平台类型、环境变量、
 * 网络拓扑），使后续工具调用无需等待首次探测，减少 AI 推理轮次中的阻塞时间。
 *
 * <p>预热是幂等的：成功后跳过同一会话实例的重复请求。
 * 如果 agent 节点尚未就绪（连接断开等），预热静默失败并释放标记，允许后续重试。
 */
@Service
public class SessionWarmupService {

    private static final Logger logger = LoggerFactory.getLogger(SessionWarmupService.class);

    private static final String BASIC_INFO_CACHE_KEY = "basic-info";
    private static final String OS_PLATFORM_CACHE_KEY = "os-platform";
    private static final String ENV_VARS_CACHE_KEY = "env-vars";
    private static final String NETWORK_INFO_CACHE_KEY = "network-info:all";

    private final Map<String, Warmup> warmups = new ConcurrentHashMap<>();
    private final Consumer<String> sessionDestroyListener = this::evict;

    private final AiBackgroundExecutor backgroundExecutor;

    public SessionWarmupService(AiBackgroundExecutor backgroundExecutor) {
        this.backgroundExecutor = backgroundExecutor;
    }

    @PostConstruct
    void registerSessionCleanup() {
        AgentNodeSessionContainer.registerDestroyListener(sessionDestroyListener);
    }

    @PreDestroy
    void unregisterSessionCleanup() {
        AgentNodeSessionContainer.unregisterDestroyListener(sessionDestroyListener);
        warmups.clear();
    }

    /**
     * 异步触发会话预热；同一会话实例仅成功执行一次，失败可重试。
     *
     * @param sessionId agent 会话 ID
     */
    public void warmupAsync(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return;
        AgentNodeSession session = AgentNodeSessionContainer.getSession(sessionId);
        if (session == null) return;
        Warmup warmup = new Warmup(session);
        if (warmups.compute(sessionId, (id, current) ->
                current != null && current.session == session ? current : warmup) != warmup) return;

        try {
            backgroundExecutor.submitWarmup(() -> {
                if (warmups.get(sessionId) != warmup
                        || AgentNodeSessionContainer.getSession(sessionId) != session) {
                    warmups.remove(sessionId, warmup);
                    return;
                }
                try {
                    doWarmup(session);
                } catch (Exception e) {
                    warmups.remove(sessionId, warmup);
                    logger.debug("会话预热失败（不影响正常使用），sessionId={}, err={}", sessionId, e.getMessage());
                }
            });
        } catch (RejectedExecutionException e) {
            warmups.remove(sessionId, warmup);
            logger.debug("会话预热队列繁忙，已释放预热标记，sessionId={}", sessionId);
        }
    }

    /**
     * 移除预热标记（会话销毁时调用，允许重新预热）。
     */
    public void evict(String sessionId) {
        if (sessionId != null) {
            warmups.remove(sessionId);
        }
    }

    private void doWarmup(AgentNodeSession session) throws Exception {
        String sessionId = session.getSessionId();
        // 1. basicInfo — 最关键，包含 OS 类型、主机名、用户等
        Object cachedBasicInfo = session.getAiContextValue(BASIC_INFO_CACHE_KEY);
        if (cachedBasicInfo == null) {
            BasicInfoCapable node = AgentNodeSessionUtils.requireCapability(session, BasicInfoCapable.class);
            Map<String, Object> basicInfo = node.getBasicInfo();
            if (basicInfo != null) {
                session.putAiContextValue(BASIC_INFO_CACHE_KEY, basicInfo);
                // 从 basicInfo 中提取 OS 平台，供环境变量和文件工具选择目标侧命令。
                detectAndCacheOsPlatform(session, basicInfo);
            }
        } else {
            // basicInfo 已有，确保 OS 平台也已缓存
            Object cachedPlatform = session.getAiContextValue(OS_PLATFORM_CACHE_KEY);
            if (cachedPlatform == null && cachedBasicInfo instanceof Map<?, ?> basicInfo) {
                detectAndCacheOsPlatform(session, basicInfo);
            }
        }

        // 2. 环境变量 — 高频使用，预填后 getEnvVars 直接命中缓存
        Object cachedEnv = session.getAiContextValue(ENV_VARS_CACHE_KEY);
        if (cachedEnv == null) {
            String platform = (String) session.getAiContextValue(OS_PLATFORM_CACHE_KEY);
            String cmd = "windows".equals(platform) ? "set" : "env";
            CommandCapable node = AgentNodeSessionUtils.requireCapability(session, CommandCapable.class);
            Map<String, Object> envResult = node.execSimpleCommand(cmd);
            if (envResult != null && !envResult.containsKey("error") && !envResult.containsKey("exception")) {
                // 预热阶段即压缩环境变量（Java classpath 可达数千字符），后续缓存命中直接使用压缩版本
                ToolResultUtils.compressMapField(envResult, "data", ToolResultUtils.DEFAULT_COMMAND_OUTPUT_THRESHOLD);
                session.putAiContextValue(ENV_VARS_CACHE_KEY, envResult);
            }
        }

        // 3. 网络拓扑预热 — AI 侦察任务高频使用
        Object cachedNetwork = session.getAiContextValue(NETWORK_INFO_CACHE_KEY);
        if (cachedNetwork == null) {
            try {
                NetworkInfoCapable node = AgentNodeSessionUtils.requireCapability(session, NetworkInfoCapable.class);
                Map<String, Object> networkInfo = node.collectNetworkInfo();
                if (networkInfo != null && !networkInfo.containsKey("error")) {
                    session.putAiContextValue(NETWORK_INFO_CACHE_KEY, networkInfo);
                }
            } catch (Exception e) {
                logger.debug("预热网络拓扑失败（非关键），sessionId={}, err={}", sessionId, e.getMessage());
            }
        }

        logger.debug("会话预热完成，sessionId={}", sessionId);
    }

    /**
     * 从 basicInfo 文本中提取 OS 平台类型并写入缓存。
     */
    private void detectAndCacheOsPlatform(AgentNodeSession session, Map<?, ?> basicInfo) {
        String text = basicInfo.toString().toLowerCase();
        if (text.contains("windows")) {
            session.putAiContextValue(OS_PLATFORM_CACHE_KEY, "windows");
        } else if (text.contains("linux") || text.contains("mac") || text.contains("darwin") || text.contains("unix")) {
            session.putAiContextValue(OS_PLATFORM_CACHE_KEY, "unix");
        }
    }

    private static final class Warmup {
        private final AgentNodeSession session;

        private Warmup(AgentNodeSession session) {
            this.session = session;
        }
    }
}
