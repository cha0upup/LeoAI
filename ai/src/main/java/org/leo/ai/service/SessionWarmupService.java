package org.leo.ai.service;

import org.leo.ai.concurrent.AiBackgroundExecutor;
import org.leo.ai.util.AgentNodeSessionUtils;
import org.leo.ai.util.ToolResultUtils;
import org.leo.core.agent.capability.BasicInfoCapable;
import org.leo.core.agent.capability.CommandCapable;
import org.leo.core.agent.capability.NetworkInfoCapable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;

/**
 * 会话预热服务。
 *
 * <p>在 AI 线程创建或恢复时异步预填基础缓存（basicInfo、OS 平台类型、环境变量、
 * 网络拓扑），使后续工具调用无需等待首次探测，减少 AI 推理轮次中的阻塞时间。
 *
 * <p>预热是幂等的：同一 session 只会执行一次，重复调用直接跳过。
 * 如果 agent 节点尚未就绪（连接断开等），预热静默失败，不影响正常流程。
 */
@Service
public class SessionWarmupService {

    private static final Logger logger = LoggerFactory.getLogger(SessionWarmupService.class);

    private static final String BASIC_INFO_CACHE_KEY = "basic-info";
    private static final String OS_PLATFORM_CACHE_KEY = "os-platform";
    private static final String ENV_VARS_CACHE_KEY = "env-vars";
    private static final String NETWORK_INFO_CACHE_KEY = "network-info:all";

    /** 已触发预热的 sessionId 集合，防止重复执行。 */
    private final Set<String> warmedSessions = ConcurrentHashMap.newKeySet();

    private final AiBackgroundExecutor backgroundExecutor;

    public SessionWarmupService(AiBackgroundExecutor backgroundExecutor) {
        this.backgroundExecutor = backgroundExecutor;
    }

    /**
     * 异步触发会话预热。幂等，同一 session 只执行一次。
     *
     * @param sessionId agent 会话 ID
     */
    public void warmupAsync(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return;
        if (!warmedSessions.add(sessionId)) return; // 已预热过

        try {
            backgroundExecutor.submitWarmup(() -> {
                try {
                    doWarmup(sessionId);
                } catch (Exception e) {
                    logger.debug("会话预热失败（不影响正常使用），sessionId={}, err={}", sessionId, e.getMessage());
                }
            });
        } catch (RejectedExecutionException e) {
            warmedSessions.remove(sessionId);
            logger.debug("会话预热队列繁忙，已释放预热标记，sessionId={}", sessionId);
        }
    }

    /**
     * 移除预热标记（会话销毁时调用，允许重新预热）。
     */
    public void evict(String sessionId) {
        if (sessionId != null) {
            warmedSessions.remove(sessionId);
        }
    }

    private void doWarmup(String sessionId) throws Exception {
        // 1. basicInfo — 最关键，包含 OS 类型、主机名、用户等
        Object cachedBasicInfo = AgentNodeSessionUtils.getAiContextValue(sessionId, BASIC_INFO_CACHE_KEY);
        if (cachedBasicInfo == null) {
            BasicInfoCapable node = AgentNodeSessionUtils.requireCapability(sessionId, BasicInfoCapable.class);
            Map<String, Object> basicInfo = node.getBasicInfo();
            if (basicInfo != null) {
                AgentNodeSessionUtils.putAiContextValue(sessionId, BASIC_INFO_CACHE_KEY, basicInfo);
                // 从 basicInfo 中提取 OS 平台，供环境变量和文件工具选择目标侧命令。
                detectAndCacheOsPlatform(sessionId, basicInfo);
            }
        } else {
            // basicInfo 已有，确保 OS 平台也已缓存
            Object cachedPlatform = AgentNodeSessionUtils.getAiContextValue(sessionId, OS_PLATFORM_CACHE_KEY);
            if (cachedPlatform == null && cachedBasicInfo instanceof Map) {
                detectAndCacheOsPlatform(sessionId, (Map<?, ?>) cachedBasicInfo);
            }
        }

        // 2. 环境变量 — 高频使用，预填后 getEnvVars 直接命中缓存
        Object cachedEnv = AgentNodeSessionUtils.getAiContextValue(sessionId, ENV_VARS_CACHE_KEY);
        if (cachedEnv == null) {
            String platform = (String) AgentNodeSessionUtils.getAiContextValue(sessionId, OS_PLATFORM_CACHE_KEY);
            String cmd = "windows".equals(platform) ? "set" : "env";
            CommandCapable node = AgentNodeSessionUtils.requireCapability(sessionId, CommandCapable.class);
            Map<String, Object> envResult = node.execSimpleCommand(cmd);
            if (envResult != null && !envResult.containsKey("error") && !envResult.containsKey("exception")) {
                // 预热阶段即压缩环境变量（Java classpath 可达数千字符），后续缓存命中直接使用压缩版本
                ToolResultUtils.compressMapField(envResult, "data", ToolResultUtils.DEFAULT_COMMAND_OUTPUT_THRESHOLD);
                AgentNodeSessionUtils.putAiContextValue(sessionId, ENV_VARS_CACHE_KEY, envResult);
            }
        }

        // 3. 网络拓扑预热 — AI 侦察任务高频使用
        Object cachedNetwork = AgentNodeSessionUtils.getAiContextValue(sessionId, NETWORK_INFO_CACHE_KEY);
        if (cachedNetwork == null) {
            try {
                NetworkInfoCapable node = AgentNodeSessionUtils.requireCapability(sessionId, NetworkInfoCapable.class);
                Map<String, Object> networkInfo = node.collectNetworkInfo();
                if (networkInfo != null && !networkInfo.containsKey("error")) {
                    AgentNodeSessionUtils.putAiContextValue(sessionId, NETWORK_INFO_CACHE_KEY, networkInfo);
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
    private void detectAndCacheOsPlatform(String sessionId, Map<?, ?> basicInfo) {
        String text = basicInfo.toString().toLowerCase();
        if (text.contains("windows")) {
            AgentNodeSessionUtils.putAiContextValue(sessionId, OS_PLATFORM_CACHE_KEY, "windows");
        } else if (text.contains("linux") || text.contains("mac") || text.contains("darwin") || text.contains("unix")) {
            AgentNodeSessionUtils.putAiContextValue(sessionId, OS_PLATFORM_CACHE_KEY, "unix");
        }
    }
}
