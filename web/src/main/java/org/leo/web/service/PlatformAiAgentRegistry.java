package org.leo.web.service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.leo.ai.agent.AiAgentFactory;
import org.leo.ai.agent.PlatformAgent;
import org.leo.ai.channel.AiModelFailoverService;
import org.leo.ai.channel.DynamicModelProvider;
import org.leo.ai.platform.PlatformAiState;
import org.leo.core.entity.AiModelConfig;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Consumer;

/** 按平台会话和模型运行时缓存 PlatformAgent。 */
@Component
public class PlatformAiAgentRegistry {

    private final AiAgentFactory agentFactory;
    private final DynamicModelProvider modelProvider;
    private final AiModelFailoverService failoverService;
    private final PlatformPuppetAiBridgeTools bridgeTools;
    private final ConcurrentMap<String, Runtime> agents = new ConcurrentHashMap<>();
    private final Consumer<String> stateDestroyListener = this::evict;

    public PlatformAiAgentRegistry(AiAgentFactory agentFactory,
                                   DynamicModelProvider modelProvider,
                                   AiModelFailoverService failoverService,
                                   PlatformPuppetAiBridgeTools bridgeTools) {
        this.agentFactory = agentFactory;
        this.modelProvider = modelProvider;
        this.failoverService = failoverService;
        this.bridgeTools = bridgeTools;
    }

    @PostConstruct
    void registerStateCleanup() {
        org.leo.ai.platform.PlatformAiStateStore.registerDestroyListener(
                stateDestroyListener);
    }

    @PreDestroy
    void unregisterStateCleanup() {
        org.leo.ai.platform.PlatformAiStateStore.unregisterDestroyListener(
                stateDestroyListener);
        agents.clear();
    }

    public Runtime resolve(PlatformAiState state,
                           AiModelConfig requested,
                           String reasoningEffort) {
        AiModelFailoverService.ModelSelection selection =
                failoverService.selectForExecution(requested);
        AiModelConfig effective = selection.effectiveConfig();
        String stateId = state != null ? state.getStateId() : "";
        String cacheKey = requested.getId() + "->" + effective.getId() + ":"
                + modelProvider.plannedRuntimeCacheKey(effective, reasoningEffort);
        return agents.compute(stateId, (ignored, cached) -> {
            if (cached != null && cacheKey.equals(cached.cacheKey())) {
                return cached;
            }
            DynamicModelProvider.ModelRuntime modelRuntime =
                    modelProvider.buildRuntime(effective, reasoningEffort);
            PlatformAgent agent = agentFactory.createPlatformAgent(
                    modelRuntime.streamingModel(),
                    modelRuntime.supportsFunctionCalling(),
                    modelRuntime.contextWindowTokens(),
                    bridgeTools);
            return new Runtime(
                    cacheKey,
                    agent,
                    DynamicModelProvider.runtimeSnapshotJson(effective, modelRuntime),
                    effective.getId(),
                    selection.message());
        });
    }

    public Runtime find(String stateId) {
        return stateId != null ? agents.get(stateId) : null;
    }

    public void evict(String stateId) {
        if (stateId != null) agents.remove(stateId);
    }

    public record Runtime(String cacheKey,
                          PlatformAgent agent,
                          String runtimeJson,
                          Integer effectiveConfigId,
                          String failoverMessage) {
    }
}
