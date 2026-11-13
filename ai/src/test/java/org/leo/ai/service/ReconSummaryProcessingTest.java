package org.leo.ai.service;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.leo.core.session.AgentNodeSession;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReconSummaryProcessingTest {

    @Test
    void unchangedSummaryReusesItsDigestAndAnAppendInvalidatesIt() {
        ChatModel model = mock(ChatModel.class);
        when(model.chat(any(ChatRequest.class))).thenReturn(response("digest"));
        AgentNodeSession session = new AgentNodeSession();
        String summary = "x".repeat(ReconSummaryDigestService.DIGEST_THRESHOLD);
        session.setReconSummary(summary);
        ReconSummaryDigestService service = new ReconSummaryDigestService(model);

        assertEquals("digest", service.generateAndSave(session));
        session.setReconSummary(summary);
        assertEquals("digest", service.generateAndSave(session));
        service.generateAndSaveAsync(session);
        assertTrue(session.hasFreshReconSummaryDigest());
        verify(model).chat(any(ChatRequest.class));

        session.appendReconSummary("additional context");
        assertTrue(session.isReconSummaryDigestDirty());
        service.generateAndSaveAsync(session);
        assertTrue(session.hasFreshReconSummaryDigest());
        verify(model, times(2)).chat(any(ChatRequest.class));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aSummaryChangedDuringGenerationRejectsTheOutdatedResult(boolean async) {
        ChatModel model = mock(ChatModel.class);
        AgentNodeSession session = new AgentNodeSession();
        session.setReconSummary("x".repeat(ReconSummaryDigestService.DIGEST_THRESHOLD));
        when(model.chat(any(ChatRequest.class))).thenAnswer(invocation -> {
            session.setReconSummary("updated summary");
            return response("outdated digest");
        });
        ReconSummaryDigestService service = new ReconSummaryDigestService(model);

        if (async) service.generateAndSaveAsync(session);
        else assertNull(service.generateAndSave(session));

        assertEquals("updated summary", session.getReconSummary());
        assertNull(session.getReconSummaryDigest());
        assertTrue(session.isReconSummaryDigestDirty());
    }

    @Test
    void digestKeepsCredentialsInModelInputAndOutput() {
        ChatModel model = mock(ChatModel.class);
        when(model.chat(any(ChatRequest.class))).thenAnswer(invocation -> {
            ChatRequest request = invocation.getArgument(0);
            SystemMessage system = assertInstanceOf(SystemMessage.class, request.messages().get(0));
            UserMessage user = assertInstanceOf(UserMessage.class, request.messages().get(1));
            assertTrue(system.text().contains("保留完整原值"));
            assertTrue(user.singleText().contains("app:source-secret@db.internal"));
            return response("· JDBC app/password=digest-secret@db.internal");
        });
        AgentNodeSession session = new AgentNodeSession();
        session.setReconSummary("jdbc:mysql://app:source-secret@db.internal/app");

        String digest = new ReconSummaryDigestService(model).generateAndSave(session);

        assertEquals("· JDBC app/password=digest-secret@db.internal", digest);
        assertEquals(digest, session.getReconSummaryDigest());
    }

    @Test
    void organizerKeepsCredentialsInModelInputAndOutput() {
        ChatModel model = mock(ChatModel.class);
        when(model.chat(any(ChatRequest.class))).thenAnswer(invocation -> {
            ChatRequest request = invocation.getArgument(0);
            UserMessage user = assertInstanceOf(UserMessage.class, request.messages().get(1));
            assertTrue(user.singleText().contains("Authorization: Bearer source-token"));
            return response("## 已发现凭据\n- Authorization: Bearer report-token");
        });

        String organized = new ReconSummaryOrganizeService(model)
                .organize("Authorization: Bearer source-token");

        assertTrue(organized.contains("Bearer report-token"));
    }

    private static ChatResponse response(String text) {
        return ChatResponse.builder().aiMessage(AiMessage.from(text)).build();
    }
}
