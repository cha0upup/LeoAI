package org.leo.web.service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.leo.ai.agent.AiAgentFactory;
import org.leo.ai.agent.PlatformAiAgent;
import org.leo.ai.channel.AiModelFailoverService;
import org.leo.ai.channel.DynamicModelProvider;
import org.leo.ai.platform.PlatformAiState;
import org.leo.core.entity.AiModelConfig;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Consumer;

/** 按平台会话和模型运行时缓存 PlatformAiAgent。 */
@Component
public class PlatformAiAgentRegistry {

    private final AiAgentFactory agentFactory;
    private final DynamicModelProvider modelProvider;
    private final AiModelFailoverService failoverService;
    private final PlatformNodeAiBridgeTools bridgeTools;
    private final ConcurrentMap<String, AiRuntime> aiRuntimes = new ConcurrentHashMap<>();
    private final Consumer<String> stateDestroyListener = this::evict;

    public PlatformAiAgentRegistry(AiAgentFactory agentFactory,
                                   DynamicModelProvider modelProvider,
                                   AiModelFailoverService failoverService,
                                   PlatformNodeAiBridgeTools bridgeTools) {
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
        aiRuntimes.clear();
    }

    public AiRuntime resolve(PlatformAiState state,
                           AiModelConfig requested,
                           String reasoningEffort) {
        AiModelFailoverService.ModelSelection selection =
                failoverService.selectForExecution(requested);
        AiModelConfig effective = selection.effectiveConfig();
        String stateId = state != null ? state.getStateId() : "";
        String modelRuntimeCacheKey = requested.getId() + "->" + effective.getId() + ":"
                + modelProvider.plannedRuntimeCacheKey(effective, reasoningEffort);
        return aiRuntimes.compute(stateId, (ignored, cached) -> {
            if (cached != null && modelRuntimeCacheKey.equals(cached.modelRuntimeCacheKey())) {
                return cached;
            }
            DynamicModelProvider.ModelRuntime modelRuntime =
                    modelProvider.buildRuntime(effective, reasoningEffort);
            PlatformAiAgent aiAgent = agentFactory.createPlatformAiAgent(
                    modelRuntime.streamingModel(),
                    modelRuntime.supportsFunctionCalling(),
                    modelRuntime.contextWindowTokens(),
                    bridgeTools);
            return new AiRuntime(
                    modelRuntimeCacheKey,
                    aiAgent,
                    DynamicModelProvider.runtimeSnapshotJson(effective, modelRuntime),
                    effective.getId(),
                    selection.message());
        });
    }

    public void evict(String stateId) {
        if (stateId != null) aiRuntimes.remove(stateId);
    }

    public record AiRuntime(String modelRuntimeCacheKey,
                          PlatformAiAgent aiAgent,
                          String runtimeJson,
                          Integer effectiveConfigId,
                          String failoverMessage) {
    }
}
