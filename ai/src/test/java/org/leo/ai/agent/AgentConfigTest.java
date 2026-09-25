package org.leo.ai.agent;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.service.TokenStream;
import org.junit.jupiter.api.Test;
import org.leo.ai.channel.DelegatingChatModel;
import org.leo.ai.config.AiAgentProperties;
import org.leo.ai.memory.ManagedConversationMemory;
import org.leo.ai.runtime.AiTurnTelemetryRegistry;
import org.leo.ai.service.AiPlanCoordinator;
import org.leo.ai.service.AutoReconAppendService;
import org.leo.ai.service.SkillRegistryService;
import org.leo.ai.thread.AiConversationStoreService;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentConfigTest {

    @Test
    void buildsThreadAgentsWithoutAnActiveDefaultModelAndKeepsTheirMemorySeparate() throws Exception {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.register(AgentConfig.class, AiAgentFactory.class,
                    AiChatMemoryProviderFactory.class, ManagedConversationMemory.class,
                    AiAgentProperties.class, AiTurnTelemetryRegistry.class,
                    AiToolErrorHandler.class, AiToolCatalog.class);
            for (Class<?> type : List.of(
                    PuppetNodeSystemPromptProvider.class, PlatformSystemPromptProvider.class,
                    PuppetNodeToolBundle.class, PlatformToolBundle.class,
                    AutoReconAppendService.class, AiToolAuthorizationPolicy.class,
                    AgentRuntimeResolver.class, AiPlanCoordinator.class,
                    AiConversationStoreService.class, SkillRegistryService.class)) {
                registerMock(context, type);
            }
            context.refresh();
            assertNull(context.getBean(DelegatingChatModel.class).getDelegate());
            assertTrue(context.getBeansOfType(ChatMemoryProvider.class).isEmpty());
            assertTrue(context.getBeansOfType(StreamingChatModel.class).isEmpty());
            assertTrue(context.getBeansOfType(PuppetNodeAgent.class).isEmpty());
            assertTrue(context.getBeansOfType(PlatformAgent.class).isEmpty());
            when(context.getBean(PuppetNodeSystemPromptProvider.class).getSystemMessage(any()))
                    .thenReturn("Test assistant");
            when(context.getBean(PlatformSystemPromptProvider.class).getSystemMessage(any()))
                    .thenReturn("Test assistant");

            StreamingChatModel model = new StreamingChatModel() {
                @Override
                public void chat(ChatRequest request, StreamingChatResponseHandler handler) {
                    handler.onCompleteResponse(ChatResponse.builder()
                            .aiMessage(AiMessage.from("answer")).build());
                }
            };
            AiAgentFactory factory = context.getBean(AiAgentFactory.class);
            PuppetNodeAgent puppet = factory.createPuppetNodeAgent(
                    model, false, 32_768);
            PlatformAgent platform = factory.createPlatformAgent(model, false, 65_536, null);

            assertEquals("answer", complete(puppet.chat("thread-1", "puppet message")));
            assertEquals("answer", complete(platform.chat("thread-1", "platform message")));
            assertEquals("puppet message", UserMessage.findLast(
                    puppet.getChatMemory("thread-1").messages()).orElseThrow().singleText());
            assertEquals("platform message", UserMessage.findLast(
                    platform.getChatMemory("thread-1").messages()).orElseThrow().singleText());
        }
    }

    private static String complete(TokenStream stream) throws Exception {
        CompletableFuture<ChatResponse> result = new CompletableFuture<>();
        stream.onPartialResponse(ignored -> {})
                .onCompleteResponse(result::complete)
                .onError(result::completeExceptionally)
                .start();
        return result.get(5, TimeUnit.SECONDS).aiMessage().text();
    }

    private static <T> void registerMock(AnnotationConfigApplicationContext context, Class<T> type) {
        context.registerBean(type, () -> mock(type));
    }
}
