package org.leo.web.application.ai;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.leo.ai.platform.PlatformAiState;
import org.leo.ai.platform.PlatformAiStateStore;
import org.leo.ai.thread.AiConversationStoreService;
import org.leo.ai.thread.AiSubagentInvocationRepository;
import org.leo.core.entity.AiModelConfig;
import org.leo.core.entity.AiSubagentInvocation;
import org.leo.core.entity.AiThreadRecord;
import org.leo.core.entity.User;
import org.leo.web.application.UseCaseException;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PlatformAiThreadServiceTest {

    private static final String THREAD_ID = "platform-ai-owned";
    private static final String SESSION_ID = "http-session";
    private String createdThreadId;

    @AfterEach
    void clearState() {
        if (createdThreadId != null) PlatformAiStateStore.remove(createdThreadId);
        PlatformAiStateStore.remove(THREAD_ID);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void invalidChannelLeavesTheExistingConversationUntouched(boolean recreate) {
        Fixture fixture = fixture("user-1");
        PlatformAiState existing = PlatformAiStateStore.create(THREAD_ID);
        existing.claimExecution();
        when(fixture.channels.optional(99)).thenThrow(new UseCaseException(UseCaseException.Kind.NOT_FOUND, "模型已删除"));

        assertThrows(UseCaseException.class, () -> {
            if (recreate) fixture.service.createAgent(SESSION_ID, THREAD_ID, user("user-1"), 99);
            else fixture.service.createThread(SESSION_ID, user("user-1"), "新对话", 99);
        });

        assertSame(existing, PlatformAiStateStore.get(THREAD_ID));
        assertFalse(existing.isStopRequested());
        verifyNoInteractions(fixture.conversationStore, fixture.agentRegistry);
    }

    @ParameterizedTest
    @CsvSource(value = {
            "true,null,平台 AI,7",
            "true,null,平台 AI,null",
            "false,null,新对话,7",
            "false,' ',新对话,null",
            "false,custom title,custom title,7"
    }, nullValues = "null")
    void createsFreshThreadsWithoutAnExistenceLookup(
            boolean recreate, String title, String expectedTitle, Integer configId) {
        Fixture fixture = fixture("user-1");
        AiModelConfig config = null;
        if (configId != null) {
            config = new AiModelConfig();
            config.setId(configId);
        }
        when(fixture.channels.optional(configId)).thenReturn(config);

        String threadId;
        if (recreate) {
            threadId = fixture.service.createAgent(SESSION_ID, null, user("user-1"), configId).getStateId();
        } else {
            Map<String, Object> result = fixture.service.createThread(SESSION_ID, user("user-1"), title, configId);
            assertEquals(expectedTitle, result.get("title"));
            assertEquals(configId, result.get("configId"));
            threadId = (String) result.get("threadId");
        }
        createdThreadId = threadId;
        PlatformAiState state = PlatformAiStateStore.get(threadId);
        assertNotNull(state);
        assertEquals(configId, state.getAiConfigId());
        verify(fixture.conversationStore).createPlatformThread(
                eq("user-1"), eq(SESSION_ID), eq(threadId),
                eq(expectedTitle), eq(state.getCreatedAt()), same(config));
        verify(fixture.conversationStore).attachEventJournal(threadId, state);
        verify(fixture.conversationStore, never()).findThread(anyString());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void deletionAndRecreationStopTheOldRuntimeAndEvictItsAgent(boolean recreate) {
        Fixture fixture = fixture("user-1");
        PlatformAiState previous = PlatformAiStateStore.create(THREAD_ID);
        previous.claimExecution();

        if (recreate) {
            PlatformAiState replacement = fixture.service.createAgent(SESSION_ID, THREAD_ID, user("user-1"), null);
            createdThreadId = replacement.getStateId();
            assertNotEquals(THREAD_ID, createdThreadId);
            assertSame(replacement, PlatformAiStateStore.get(createdThreadId));
            verify(fixture.conversationStore, never()).deleteThread(THREAD_ID);
        } else {
            fixture.service.deleteThread(user("user-1"), THREAD_ID);
            verify(fixture.conversationStore).deleteThread(THREAD_ID);
        }

        assertNull(PlatformAiStateStore.get(THREAD_ID));
        assertTrue(previous.isStopRequested());
        assertEquals(recreate ? "平台 AI 会话已重建" : "线程已删除", previous.getStopReason());
        verify(fixture.agentRegistry).evict(THREAD_ID);
    }

    @Test
    void activatesOnlyThreadsOwnedByTheCurrentUser() {
        Fixture fixture = fixture("user-1");

        PlatformAiState state =
                fixture.service.activateThread(user("user-1"), THREAD_ID);

        assertEquals(7, state.getAiConfigId());
        assertSame(state, PlatformAiStateStore.get(THREAD_ID));
        verify(fixture.conversationStore).attachEventJournal(THREAD_ID, state);
    }

    @Test
    void hidesForeignThreadsAndDoesNotMutateThem() {
        Fixture fixture = fixture("other-user");

        assertThrows(UseCaseException.class,
                () -> fixture.service.deleteThread(user("user-1"), THREAD_ID));

        verify(fixture.conversationStore, never()).deleteThread(THREAD_ID);
        verify(fixture.agentRegistry, never()).evict(THREAD_ID);
    }

    @ParameterizedTest
    @ValueSource(strings = {"absent", "idle", "running"})
    void eventsUsePersistedStatusUnlessRuntimeIsExecuting(String runtimeState) {
        Fixture fixture = fixture("user-1");
        fixture.record.setRunStatus("failed");
        if (!"absent".equals(runtimeState)) {
            PlatformAiState state = PlatformAiStateStore.create(THREAD_ID);
            state.configureEventJournal(3L, null);
            if ("running".equals(runtimeState)) state.claimExecution();
        }
        when(fixture.conversationStore.findLastEventSeq(THREAD_ID)).thenReturn(20L);

        Map<String, Object> data = fixture.service.events(user("user-1"), THREAD_ID, 1L, null);

        assertEquals("running".equals(runtimeState) ? "running" : "failed", data.get("runStatus"));
        assertFalse(data.containsKey("status"));
        assertEquals(20L, data.get("lastSeq"));
        assertNull(data.get("stopReason"));
        verify(fixture.conversationStore).listEventsAfter(THREAD_ID, 1L, 200);
    }

    @Test
    void deniesHistoryQueriesBeforeReadingForeignThreadData() {
        Fixture fixture = fixture("other-user");
        assertEquals(UseCaseException.Kind.NOT_FOUND, assertThrows(UseCaseException.class,
                () -> fixture.service.messages(user("user-1"), THREAD_ID, null, null, null)).getKind());
        assertEquals(UseCaseException.Kind.NOT_FOUND, assertThrows(UseCaseException.class,
                () -> fixture.service.events(user("user-1"), THREAD_ID, null, null)).getKind());
        assertEquals(UseCaseException.Kind.NOT_FOUND, assertThrows(UseCaseException.class,
                () -> fixture.service.subagentInvocations(user("user-1"), THREAD_ID)).getKind());
        verify(fixture.conversationStore, never()).listMessages(anyString(), anyInt(), anyInt());
        verify(fixture.conversationStore, never()).listEventsAfter(anyString(), anyLong(), anyInt());
        verifyNoInteractions(fixture.invocations);
    }

    @Test
    void readsSubagentHistoryForTheOwnedPlatformThread() {
        Fixture fixture = fixture("user-1");
        AiSubagentInvocation invocation = new AiSubagentInvocation();
        invocation.setInvocationId("invocation-1");
        invocation.setParentThreadId(THREAD_ID);
        when(fixture.invocations.listByParentThread(THREAD_ID)).thenReturn(List.of(invocation));

        assertEquals(List.of(invocation), fixture.service.subagentInvocations(user("user-1"), THREAD_ID));

        verify(fixture.invocations).listByParentThread(THREAD_ID);
    }

    private Fixture fixture(String ownerId) {
        AiConversationStoreService conversationStore =
                mock(AiConversationStoreService.class);
        AiSubagentInvocationRepository invocations = mock(AiSubagentInvocationRepository.class);
        PlatformAiAgentRegistry agentRegistry = mock(PlatformAiAgentRegistry.class);
        AiThreadRecord record = new AiThreadRecord();
        record.setThreadId(THREAD_ID);
        record.setScope(AiConversationStoreService.SCOPE_PLATFORM);
        record.setUserId(ownerId);
        record.setConfigId(7);
        when(conversationStore.findThread(THREAD_ID)).thenReturn(record);
        AiTurnProtocolService protocol = mock(AiTurnProtocolService.class);
        when(protocol.snapshotThread(anyString(), nullable(String.class))).thenAnswer(invocation ->
                new AiTurnProtocolService.ThreadSnapshot(invocation.getArgument(1), false, null, List.of(), null));
        AiModelChannelResolver channels = mock(AiModelChannelResolver.class);
        PlatformAiThreadService service = new PlatformAiThreadService(
                channels, conversationStore, invocations,
                agentRegistry, new AiThreadQueryService(conversationStore, protocol));
        return new Fixture(service, conversationStore, invocations, agentRegistry, record, channels);
    }

    private User user(String id) {
        User user = new User();
        user.setUserId(id);
        return user;
    }

    private record Fixture(PlatformAiThreadService service,
                           AiConversationStoreService conversationStore,
                           AiSubagentInvocationRepository invocations,
                           PlatformAiAgentRegistry agentRegistry,
                           AiThreadRecord record,
                           AiModelChannelResolver channels) {
    }
}
