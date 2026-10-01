package org.leo.web.service;

import org.leo.ai.agent.AiAgentFactory;
import org.leo.ai.agent.NodeAiAgent;
import org.leo.ai.channel.AiModelFailoverService;
import org.leo.ai.channel.DynamicModelProvider;
import org.leo.core.entity.AiModelConfig;
import org.leo.core.session.AiThread;
import org.leo.core.session.AgentNodeSession;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Consumer;

/** 按 Agent Session/Thread 和模型运行时缓存节点 Agent。 */
@Component
public class NodeAiAgentRegistry {

    private final AiAgentFactory agentFactory;
    private final DynamicModelProvider modelProvider;
    private final AiModelFailoverService failoverService;
    private final ConcurrentMap<String, AiRuntime> aiRuntimes = new ConcurrentHashMap<>();
    private final Consumer<String> sessionDestroyListener = this::evictSession;

    public NodeAiAgentRegistry(AiAgentFactory agentFactory,
                                     DynamicModelProvider modelProvider,
                                     AiModelFailoverService failoverService) {
        this.agentFactory = agentFactory;
        this.modelProvider = modelProvider;
        this.failoverService = failoverService;
    }

    @PostConstruct
    void registerSessionCleanup() {
        org.leo.core.session.AgentNodeSessionContainer.registerDestroyListener(
                sessionDestroyListener);
    }

    @PreDestroy
    void unregisterSessionCleanup() {
        org.leo.core.session.AgentNodeSessionContainer.unregisterDestroyListener(
                sessionDestroyListener);
        aiRuntimes.clear();
    }

    public AiRuntime resolve(AgentNodeSession session,
                           AiThread thread,
                           AiModelConfig requested,
                           String reasoningEffort) {
        AiModelFailoverService.ModelSelection selection =
                failoverService.selectForExecution(requested);
        AiModelConfig effective = selection.effectiveConfig();
        String conversationKey = conversationCacheKey(session, thread != null ? thread.getThreadId() : null);
        String modelRuntimeCacheKey = requested.getId() + "->" + effective.getId() + ":"
                + modelProvider.plannedRuntimeCacheKey(effective, reasoningEffort);
        return aiRuntimes.compute(conversationKey, (ignored, cached) -> {
            if (cached != null && modelRuntimeCacheKey.equals(cached.modelRuntimeCacheKey())) {
                return cached;
            }
            DynamicModelProvider.ModelRuntime modelRuntime =
                    modelProvider.buildRuntime(effective, reasoningEffort);
            NodeAiAgent aiAgent = agentFactory.createNodeAiAgent(
                    modelRuntime.streamingModel(),
                    modelRuntime.supportsFunctionCalling(),
                    modelRuntime.contextWindowTokens());
            return new AiRuntime(
                    modelRuntimeCacheKey,
                    aiAgent,
                    DynamicModelProvider.runtimeSnapshotJson(effective, modelRuntime),
                    effective.getId(),
                    selection.message());
        });
    }

    public AiRuntime find(AgentNodeSession session, String threadId) {
        return aiRuntimes.get(conversationCacheKey(session, threadId));
    }

    public void evict(AgentNodeSession session, String threadId) {
        aiRuntimes.remove(conversationCacheKey(session, threadId));
    }

    public void evictSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return;
        String prefix = sessionId + ":";
        aiRuntimes.keySet().removeIf(key -> key.startsWith(prefix));
    }

    static String conversationCacheKey(AgentNodeSession session, String threadId) {
        String sessionId = session != null ? session.getSessionId() : "";
        return sessionId + ":" + (threadId != null ? threadId : "");
    }

    public record AiRuntime(String modelRuntimeCacheKey,
                          NodeAiAgent aiAgent,
                          String runtimeJson,
                          Integer effectiveConfigId,
                          String failoverMessage) {
    }
}
