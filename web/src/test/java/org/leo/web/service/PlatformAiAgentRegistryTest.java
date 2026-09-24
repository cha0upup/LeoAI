package org.leo.web.service;

import dev.langchain4j.model.chat.StreamingChatModel;
import org.junit.jupiter.api.Test;
import org.leo.ai.agent.AiAgentFactory;
import org.leo.ai.agent.PlatformAgent;
import org.leo.ai.channel.AiModelFailoverService;
import org.leo.ai.channel.DynamicModelProvider;
import org.leo.ai.platform.PlatformAiState;
import org.leo.core.entity.AiModelConfig;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlatformAiAgentRegistryTest {

    @Test
    void reusesAgentUntilThePlannedRuntimeChangesAndSupportsEviction() {
        AiAgentFactory agentFactory = mock(AiAgentFactory.class);
        DynamicModelProvider modelProvider = mock(DynamicModelProvider.class);
        AiModelFailoverService failoverService = mock(AiModelFailoverService.class);
        PlatformPuppetAiBridgeTools bridgeTools = mock(PlatformPuppetAiBridgeTools.class);
        AiModelConfig config = config(7);
        StreamingChatModel streamingModel = mock(StreamingChatModel.class);
        DynamicModelProvider.ModelRuntime modelRuntime = runtime(streamingModel);
        PlatformAgent firstAgent = mock(PlatformAgent.class);
        PlatformAgent secondAgent = mock(PlatformAgent.class);
        when(failoverService.selectForExecution(config)).thenReturn(
                new AiModelFailoverService.ModelSelection(
                        config, config, false, "运行时提示", List.of(7)));
        when(modelProvider.plannedRuntimeCacheKey(config, "medium")).thenReturn("runtime-a");
        when(modelProvider.plannedRuntimeCacheKey(config, "high")).thenReturn("runtime-b");
        when(modelProvider.buildRuntime(config, "medium")).thenReturn(modelRuntime);
        when(modelProvider.buildRuntime(config, "high")).thenReturn(modelRuntime);
        when(agentFactory.createPlatformAgent(
                same(streamingModel), eq(true), eq(32_768), same(bridgeTools)))
                .thenReturn(firstAgent, secondAgent, secondAgent);
        PlatformAiAgentRegistry registry = new PlatformAiAgentRegistry(
                agentFactory, modelProvider, failoverService, bridgeTools);
        PlatformAiState state = new PlatformAiState("state-1");

        PlatformAiAgentRegistry.Runtime first = registry.resolve(state, config, "medium");
        PlatformAiAgentRegistry.Runtime reused = registry.resolve(state, config, "medium");
        PlatformAiAgentRegistry.Runtime rebuilt = registry.resolve(state, config, "high");

        assertSame(first, reused);
        assertNotSame(first, rebuilt);
        assertSame(secondAgent, rebuilt.agent());
        assertEquals("运行时提示", first.failoverMessage());
        verify(modelProvider, times(2)).buildRuntime(eq(config), org.mockito.ArgumentMatchers.any());

        registry.evict(state.getStateId());
        PlatformAiAgentRegistry.Runtime afterEviction = registry.resolve(state, config, "high");
        assertNotSame(rebuilt, afterEviction);
        verify(modelProvider, times(3)).buildRuntime(eq(config), org.mockito.ArgumentMatchers.any());
    }

    private static AiModelConfig config(int id) {
        AiModelConfig config = new AiModelConfig();
        config.setId(id);
        config.setName("model-" + id);
        return config;
    }

    private static DynamicModelProvider.ModelRuntime runtime(StreamingChatModel streamingModel) {
        return new DynamicModelProvider.ModelRuntime(
                streamingModel, null, "responses", "openai", "https://example.test",
                "test-model", 4096, false, null, true, false, 32_768);
    }
}
