package org.leo.web.service;

import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.leo.ai.platform.PlatformAiState;
import org.leo.ai.platform.PlatformAiStateStore;
import org.leo.ai.thread.AiConversationStoreService;
import org.leo.core.entity.AiThreadRecord;
import org.leo.core.entity.User;
import org.leo.web.exception.ApiException;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlatformAiThreadServiceTest {

    private static final String THREAD_ID = "platform-ai-owned";

    @AfterEach
    void clearState() {
        PlatformAiStateStore.remove(THREAD_ID);
    }

    @Test
    void activatesOnlyThreadsOwnedByTheCurrentUser() {
        Fixture fixture = fixture("user-1");
        HttpSession session = mock(HttpSession.class);

        PlatformAiState state =
                fixture.service.activateThread(session, user("user-1"), THREAD_ID);

        assertEquals(7, state.getAiConfigId());
        verify(session).setAttribute("platformAiStateId", THREAD_ID);
    }

    @Test
    void hidesForeignThreadsAndDoesNotMutateThem() {
        Fixture fixture = fixture("other-user");
        HttpSession session = mock(HttpSession.class);

        assertThrows(ApiException.class,
                () -> fixture.service.deleteThread(session, user("user-1"), THREAD_ID));

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

        assertEquals("running".equals(runtimeState) ? "running" : "failed", data.get("status"));
        assertEquals(20L, data.get("lastSeq"));
        assertEquals(0L, data.get("elapsedMs"));
        assertNull(data.get("stopReason"));
        verify(fixture.conversationStore).listEventsAfter(THREAD_ID, 1L, 200);
    }

    @Test
    void deniesHistoryQueriesBeforeReadingForeignThreadData() {
        Fixture fixture = fixture("other-user");
        assertEquals(404, assertThrows(ApiException.class,
                () -> fixture.service.messages(user("user-1"), THREAD_ID, null, null)).getCode());
        assertEquals(404, assertThrows(ApiException.class,
                () -> fixture.service.events(user("user-1"), THREAD_ID, null, null)).getCode());
        verify(fixture.conversationStore, never()).listMessages(anyString(), anyInt(), anyInt());
        verify(fixture.conversationStore, never()).listEventsAfter(anyString(), anyLong(), anyInt());
    }

    @Test
    void listPreservesConfigurationAndUsesProtocolState() {
        Fixture fixture = fixture("user-1");
        fixture.record.setConfigName("channel");
        fixture.record.setConfigProtocol("openai");
        fixture.record.setConfigModel("model");
        when(fixture.conversationStore.listPlatformThreads("user-1")).thenReturn(List.of(fixture.record));
        PlatformAiState state = PlatformAiStateStore.create(THREAD_ID);
        when(fixture.protocol.snapshotThread(THREAD_ID, state.getRunStatus()))
                .thenReturn(new AiTurnProtocolService.ThreadSnapshot("queued", true, null, List.of()));

        Map<String, Object> item = fixture.service.listThreads(user("user-1")).get(0);

        assertEquals("queued", item.get("runStatus"));
        assertEquals(true, item.get("executing"));
        assertEquals(7, item.get("configId"));
        assertEquals("channel", item.get("configName"));
        assertEquals("openai", item.get("configProtocol"));
        assertEquals("model", item.get("configModel"));
        assertEquals(0, item.get("messageCount"));
        assertFalse(state.isExecuting());
    }

    private Fixture fixture(String ownerId) {
        AiConversationStoreService conversationStore =
                mock(AiConversationStoreService.class);
        PlatformAiAgentRegistry agentRegistry = mock(PlatformAiAgentRegistry.class);
        AiThreadRecord record = new AiThreadRecord();
        record.setThreadId(THREAD_ID);
        record.setScope(AiConversationStoreService.SCOPE_PLATFORM);
        record.setUserId(ownerId);
        record.setConfigId(7);
        when(conversationStore.findThread(THREAD_ID)).thenReturn(record);
        AiTurnProtocolService protocol = mock(AiTurnProtocolService.class);
        when(protocol.snapshotThread(anyString(), nullable(String.class))).thenAnswer(invocation ->
                new AiTurnProtocolService.ThreadSnapshot(invocation.getArgument(1), false, null, List.of()));
        PlatformAiThreadService service = new PlatformAiThreadService(
                mock(AiModelChannelResolver.class), conversationStore,
                agentRegistry, new AiThreadQueryService(conversationStore, protocol));
        return new Fixture(service, conversationStore, agentRegistry, protocol, record);
    }

    private User user(String id) {
        User user = new User();
        user.setUserId(id);
        return user;
    }

    private record Fixture(PlatformAiThreadService service,
                           AiConversationStoreService conversationStore,
                           PlatformAiAgentRegistry agentRegistry,
                           AiTurnProtocolService protocol,
                           AiThreadRecord record) {
    }
}
