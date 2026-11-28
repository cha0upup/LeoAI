package org.leo.ai.channel;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;
import org.leo.ai.service.AiErrorClassifier;
import org.leo.core.entity.AiModelConfig;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AiModelConnectionTestServiceTest {

    private final AiModelConfigService configs = mock(AiModelConfigService.class);
    private final DynamicModelProvider runtimeProvider = mock(DynamicModelProvider.class);
    private final AiErrorClassifier errors = mock(AiErrorClassifier.class);
    private final AiModelFailoverService health = mock(AiModelFailoverService.class);
    private final AiModelConnectionTestService service = new AiModelConnectionTestService(configs, runtimeProvider,
            errors, health);

    @Test
    void testsTheSavedModelWithOneTextRequestAndReturnsAShortPreview() {
        ChatModel chat = stubModel();
        String text = "a".repeat(101);
        when(chat.chat(any(ChatRequest.class))).thenReturn(ChatResponse.builder().aiMessage(AiMessage.from(text)).build());

        var result = service.testConnection(7);

        assertTrue(result.success());
        assertEquals("a".repeat(100) + "...", result.responsePreview());
        assertEquals("fixture-model", result.model());
        assertEquals("custom", result.providerKey());
        assertEquals("responses", result.protocol());
        assertEquals("https://example.test/v1/gateway", result.effectiveBaseUrl());
        assertNull(result.failure());
        var request = ArgumentCaptor.forClass(ChatRequest.class);
        verify(chat).chat(request.capture());
        assertEquals(List.of(new UserMessage("请只回复 OK。")), request.getValue().messages());
        verify(health).recordSuccess(7);
        verify(configs).findById(7);
        verifyNoMoreInteractions(configs, chat, health);
        verifyNoInteractions(errors);
    }

    @Test
    void classifiesRequestFailureAndOnlyReturnsThePublicErrorFields() {
        ChatModel chat = stubModel();
        var failure = new IllegalStateException("fixture raw error");
        var classified = new AiErrorClassifier.Classification("network", "检查模型连接", "fixture raw error", List.of());
        when(chat.chat(any(ChatRequest.class))).thenThrow(failure);
        when(errors.classify(failure)).thenReturn(classified);

        var result = service.testConnection(7);

        assertFalse(result.success());
        assertEquals(new AiModelConnectionTestService.Failure("network", "检查模型连接"), result.failure());
        assertNull(result.responsePreview());
        verify(health).recordFailure(7, classified);
        verifyNoMoreInteractions(health);
    }

    @Test
    void missingModelDoesNotBuildARuntimeOrChangeHealth() {
        assertNull(service.testConnection(7));

        verify(configs).findById(7);
        verifyNoInteractions(runtimeProvider, errors, health);
    }

    private ChatModel stubModel() {
        var config = new AiModelConfig();
        config.setId(7);
        config.setProviderKey("custom");
        config.setModel("fixture-model");
        config.setProtocol("responses");
        config.setBaseUrl("https://example.test/v1");
        config.setCompletionsPath("/gateway/responses");
        var runtime = mock(DynamicModelProvider.ModelRuntime.class);
        var chat = mock(ChatModel.class);
        when(configs.findById(7)).thenReturn(config);
        when(runtimeProvider.buildProbeRuntime(config, false)).thenReturn(runtime);
        when(runtime.chatModel()).thenReturn(chat);
        return chat;
    }
}
