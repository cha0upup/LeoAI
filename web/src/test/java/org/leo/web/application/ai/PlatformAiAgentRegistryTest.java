package org.leo.web.application.ai;

import dev.langchain4j.model.chat.StreamingChatModel;
import org.junit.jupiter.api.Test;
import org.leo.ai.agent.PlatformAiAgent;
import org.leo.ai.channel.AiModelFailoverService;
import org.leo.ai.channel.DynamicModelProvider;
import org.leo.ai.platform.PlatformAiState;
import org.leo.core.entity.AiModelConfig;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlatformAiAgentRegistryTest {

    @Test
    void reusesAgentUntilThePlannedRuntimeChangesAndSupportsEviction() {
        DynamicModelProvider modelProvider = mock(DynamicModelProvider.class);
        AiModelFailoverService failoverService = mock(AiModelFailoverService.class);
        AiModelConfig config = config(7);
        StreamingChatModel streamingModel = mock(StreamingChatModel.class);
        DynamicModelProvider.ModelRuntime modelRuntime = runtime(streamingModel);
        PlatformAiAgent firstAgent = mock(PlatformAiAgent.class);
        PlatformAiAgent secondAgent = mock(PlatformAiAgent.class);
        when(failoverService.selectForExecution(config)).thenReturn(
                new AiModelFailoverService.ModelSelection(
                        config, "运行时提示"));
        when(modelProvider.plannedRuntimeCacheKey(config, "medium")).thenReturn("runtime-a");
        when(modelProvider.plannedRuntimeCacheKey(config, "high")).thenReturn("runtime-b");
        when(modelProvider.buildRuntime(config, "medium")).thenReturn(modelRuntime);
        when(modelProvider.buildRuntime(config, "high")).thenReturn(modelRuntime);
        List<DynamicModelProvider.ModelRuntime> created = new ArrayList<>();
        PlatformAiAgentRegistry registry = new PlatformAiAgentRegistry(
                modelProvider, failoverService, runtime -> {
                    created.add(runtime);
                    return created.size() == 1 ? firstAgent : secondAgent;
                });
        PlatformAiState state = new PlatformAiState("state-1");

        PlatformAiAgentRegistry.AiRuntime first = registry.resolve(state, config, "medium");
        PlatformAiAgentRegistry.AiRuntime reused = registry.resolve(state, config, "medium");
        PlatformAiAgentRegistry.AiRuntime rebuilt = registry.resolve(state, config, "high");

        assertSame(first, reused);
        assertNotSame(first, rebuilt);
        assertSame(secondAgent, rebuilt.aiAgent());
        assertEquals(List.of(modelRuntime, modelRuntime), created);
        assertEquals("运行时提示", first.failoverMessage());
        verify(modelProvider, times(2)).buildRuntime(eq(config), org.mockito.ArgumentMatchers.any());

        registry.evict(state.getStateId());
        PlatformAiAgentRegistry.AiRuntime afterEviction = registry.resolve(state, config, "high");
        assertNotSame(rebuilt, afterEviction);
        assertEquals(3, created.size());
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
