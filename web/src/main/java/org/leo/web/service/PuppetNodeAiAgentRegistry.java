package org.leo.web.service;

import org.leo.ai.agent.AiAgentFactory;
import org.leo.ai.agent.PuppetNodeAgent;
import org.leo.ai.channel.AiModelFailoverService;
import org.leo.ai.channel.DynamicModelProvider;
import org.leo.core.entity.AiModelConfig;
import org.leo.core.session.AiThread;
import org.leo.core.session.PuppetNodeSession;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Consumer;

/** 按 Puppet Session/Thread 和模型运行时缓存节点 Agent。 */
@Component
public class PuppetNodeAiAgentRegistry {

    private final AiAgentFactory agentFactory;
    private final DynamicModelProvider modelProvider;
    private final AiModelFailoverService failoverService;
    private final ConcurrentMap<String, Runtime> agents = new ConcurrentHashMap<>();
    private final Consumer<String> sessionDestroyListener = this::evictSession;

    public PuppetNodeAiAgentRegistry(AiAgentFactory agentFactory,
                                     DynamicModelProvider modelProvider,
                                     AiModelFailoverService failoverService) {
        this.agentFactory = agentFactory;
        this.modelProvider = modelProvider;
        this.failoverService = failoverService;
    }

    @PostConstruct
    void registerSessionCleanup() {
        org.leo.core.session.PuppetNodeSessionContainer.registerDestroyListener(
                sessionDestroyListener);
    }

    @PreDestroy
    void unregisterSessionCleanup() {
        org.leo.core.session.PuppetNodeSessionContainer.unregisterDestroyListener(
                sessionDestroyListener);
        agents.clear();
    }

    public Runtime resolve(PuppetNodeSession session,
                           AiThread thread,
                           AiModelConfig requested,
                           String reasoningEffort) {
        AiModelFailoverService.ModelSelection selection =
                failoverService.selectForExecution(requested);
        AiModelConfig effective = selection.effectiveConfig();
        String agentKey = cacheKey(session, thread != null ? thread.getThreadId() : null);
        String runtimeKey = requested.getId() + "->" + effective.getId() + ":"
                + modelProvider.plannedRuntimeCacheKey(effective, reasoningEffort);
        return agents.compute(agentKey, (ignored, cached) -> {
            if (cached != null && runtimeKey.equals(cached.cacheKey())) {
                return cached;
            }
            DynamicModelProvider.ModelRuntime modelRuntime =
                    modelProvider.buildRuntime(effective, reasoningEffort);
            PuppetNodeAgent agent = agentFactory.createPuppetNodeAgent(
                    modelRuntime.streamingModel(),
                    modelRuntime.supportsFunctionCalling(),
                    modelRuntime.contextWindowTokens());
            return new Runtime(
                    runtimeKey,
                    agent,
                    DynamicModelProvider.runtimeSnapshotJson(effective, modelRuntime),
                    effective.getId(),
                    selection.message());
        });
    }

    public Runtime find(PuppetNodeSession session, String threadId) {
        return agents.get(cacheKey(session, threadId));
    }

    public void evict(PuppetNodeSession session, String threadId) {
        agents.remove(cacheKey(session, threadId));
    }

    public void evictSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return;
        String prefix = sessionId + ":";
        agents.keySet().removeIf(key -> key.startsWith(prefix));
    }

    static String cacheKey(PuppetNodeSession session, String threadId) {
        String sessionId = session != null ? session.getSessionId() : "";
        return sessionId + ":" + (threadId != null ? threadId : "");
    }

    public record Runtime(String cacheKey,
                          PuppetNodeAgent agent,
                          String runtimeJson,
                          Integer effectiveConfigId,
                          String failoverMessage) {
    }
}
