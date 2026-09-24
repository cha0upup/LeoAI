package org.leo.web.service;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import org.junit.jupiter.api.Test;
import org.leo.ai.agent.AiAgentFactory;
import org.leo.ai.agent.PuppetNodeAgent;
import org.leo.ai.channel.AiModelFailoverService;
import org.leo.ai.channel.DynamicModelProvider;
import org.leo.core.entity.AiModelConfig;
import org.leo.core.session.AiThread;
import org.leo.core.session.PuppetNodeSession;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PuppetNodeAiAgentRegistryTest {

    @Test
    void scopesCacheBySessionAndThreadAndRebuildsOnRuntimeChange() {
        AiAgentFactory agentFactory = mock(AiAgentFactory.class);
        DynamicModelProvider modelProvider = mock(DynamicModelProvider.class);
        AiModelFailoverService failoverService = mock(AiModelFailoverService.class);
        AiModelConfig config = config(9);
        StreamingChatModel streamingModel = mock(StreamingChatModel.class);
        ChatModel chatModel = mock(ChatModel.class);
        DynamicModelProvider.ModelRuntime modelRuntime = runtime(streamingModel, chatModel);
        PuppetNodeAgent firstAgent = mock(PuppetNodeAgent.class);
        PuppetNodeAgent secondAgent = mock(PuppetNodeAgent.class);
        when(failoverService.selectForExecution(config)).thenReturn(
                new AiModelFailoverService.ModelSelection(
                        config, config, false, null, List.of(9)));
        when(modelProvider.plannedRuntimeCacheKey(config, "low")).thenReturn("runtime-a");
        when(modelProvider.plannedRuntimeCacheKey(config, "high")).thenReturn("runtime-b");
        when(modelProvider.buildRuntime(eq(config), any())).thenReturn(modelRuntime);
        when(agentFactory.createPuppetNodeAgent(
                same(streamingModel), same(chatModel), eq(true), eq(16_384)))
                .thenReturn(firstAgent, secondAgent);
        PuppetNodeAiAgentRegistry registry = new PuppetNodeAiAgentRegistry(
                agentFactory, modelProvider, failoverService);
        PuppetNodeSession session = new PuppetNodeSession();
        session.setSessionId("session-1");
        AiThread thread = new AiThread("thread-1", "test");

        PuppetNodeAiAgentRegistry.Runtime first = registry.resolve(session, thread, config, "low");
        PuppetNodeAiAgentRegistry.Runtime reused = registry.resolve(session, thread, config, "low");
        PuppetNodeAiAgentRegistry.Runtime rebuilt = registry.resolve(session, thread, config, "high");

        assertSame(first, reused);
        assertNotSame(first, rebuilt);
        assertSame(secondAgent, rebuilt.agent());
        assertSame(rebuilt, registry.find(session, thread.getThreadId()));
        verify(modelProvider, times(2)).buildRuntime(eq(config), any());

        registry.evict(session, thread.getThreadId());
        assertNotSame(rebuilt, registry.resolve(session, thread, config, "high"));
        verify(modelProvider, times(3)).buildRuntime(eq(config), any());
    }

    private static AiModelConfig config(int id) {
        AiModelConfig config = new AiModelConfig();
        config.setId(id);
        config.setName("model-" + id);
        return config;
    }

    private static DynamicModelProvider.ModelRuntime runtime(
            StreamingChatModel streamingModel, ChatModel chatModel) {
        return new DynamicModelProvider.ModelRuntime(
                streamingModel, chatModel, "chat-completions", "openai",
                "https://example.test", "test-model", 4096,
                false, null, true, false, 16_384);
    }
}
