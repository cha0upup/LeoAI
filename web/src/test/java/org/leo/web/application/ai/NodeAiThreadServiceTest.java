package org.leo.web.application.ai;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.leo.ai.service.SessionWarmupService;
import org.leo.ai.thread.AiConversationStoreService;
import org.leo.ai.thread.AiEventJournalRepository;
import org.leo.ai.thread.AiMessageRepository;
import org.leo.core.entity.AiModelConfig;
import org.leo.core.entity.AiPlanStatus;
import org.leo.core.entity.AiThreadRecord;
import org.leo.core.session.AgentNodeSession;
import org.leo.core.session.AiThread;
import org.leo.web.application.UseCaseException;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class NodeAiThreadServiceTest {

    @ParameterizedTest
    @CsvSource({"load,false", "reset,false", "switch,false", "load,true", "reset,true", "switch,true"})
    void restoresOnlyMissingThreadsAndKeepsExistingJournalBindings(String action, boolean inMemory) {
        Fixture fixture = fixture();
        AgentNodeSession session = cacheSession();
        for (int index = 0; index < 20; index++) {
            session.restoreAiThread("resident-" + index, "resident", 1L, 100L + index);
        }
        AiThread oldest = session.getAiThread("resident-0");
        AiThread cached = inMemory ? session.createAiThread("thread", "runtime title") : null;
        if (cached != null) {
            cached.setAiConfigId(2);
        } else {
            AiThreadRecord record = ownedRecord("thread");
            record.setTitle("stored title");
            record.setConfigId(2);
            record.setCreatedAt(1L);
            record.setLastActiveAt(1L);
            when(fixture.conversationStore.findThread("thread")).thenReturn(record);
        }
        AiModelConfig config = new AiModelConfig();
        config.setId(inMemory ? 2 : 7);
        when(fixture.channels.optional(config.getId())).thenReturn(config);
        Integer requestedConfig = inMemory ? null : 7;
        switch (action) {
            case "load" -> fixture.service.requireThread(session, "thread");
            case "reset" -> fixture.service.resetThread(session, "thread", requestedConfig);
            case "switch" -> fixture.service.switchChannel(session, "thread", requestedConfig);
            default -> throw new IllegalArgumentException(action);
        }

        AiThread thread = session.getAiThread("thread");
        assertNotNull(thread);
        assertSame(thread, session.getActiveThread());
        assertEquals(20, session.listAiThreads().size());
        assertNull(session.getAiThread("resident-0"));
        assertTrue(oldest.isStopRequested());
        assertEquals(inMemory ? "runtime title" : "stored title", thread.getTitle());
        assertEquals("load".equals(action) ? 2 : config.getId(), thread.getAiConfigId());
        assertEquals("reset".equals(action), thread.isStopRequested());
        verify(fixture.conversationStore).findThread("thread");
        if (cached != null) {
            assertSame(cached, thread);
            verify(fixture.conversationStore, never()).attachEventJournal(anyString(), same(thread));
        } else {
            verify(fixture.conversationStore).attachEventJournal("thread", thread);
        }
        if ("load".equals(action)) {
            verifyNoInteractions(fixture.channels, fixture.agentRegistry);
        } else {
            verify(fixture.conversationStore).updateConfig("thread", config);
            verify(fixture.channels).optional(config.getId());
            verify(fixture.agentRegistry).evict(session, "thread");
        }
    }

    @ParameterizedTest
    @CsvSource({"PLANNING,true", "IN_PROGRESS,true", "COMPLETED,false", "FAILED,false"})
    void restoresOnlyActivePlansAndReusesTheLoadedThread(AiPlanStatus status, boolean active) {
        Fixture fixture = fixture();
        AgentNodeSession session = cacheSession();
        when(fixture.conversationStore.findThread("thread")).thenReturn(ownedRecord("thread"));
        when(fixture.messageRepository.findLatestPlanJson("thread"))
                .thenReturn("{\"planId\":\"plan-1\",\"title\":\"stored plan\",\"status\":\"" + status + "\"}");

        AiThread restored = fixture.service.requireThread(session, "thread");

        if (active) {
            assertNotNull(restored.getCurrentPlan());
            assertEquals("plan-1", restored.getCurrentPlan().getPlanId());
            assertEquals("stored plan", restored.getCurrentPlan().getTitle());
            assertEquals(status, restored.getCurrentPlan().getStatus());
        } else {
            assertNull(restored.getCurrentPlan());
        }
        assertSame(restored, fixture.service.requireThread(session, "thread"));
        verify(fixture.messageRepository).findLatestPlanJson("thread");
        verify(fixture.conversationStore).attachEventJournal("thread", restored);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"null", "not-json"})
    void missingOrInvalidPlanDoesNotPreventThreadRestoration(String planJson) {
        Fixture fixture = fixture();
        AgentNodeSession session = cacheSession();
        when(fixture.conversationStore.findThread("thread")).thenReturn(ownedRecord("thread"));
        when(fixture.messageRepository.findLatestPlanJson("thread")).thenReturn(planJson);

        AiThread restored = fixture.service.requireThread(session, "thread");

        assertSame(restored, session.getAiThread("thread"));
        assertNull(restored.getCurrentPlan());
        verify(fixture.conversationStore).attachEventJournal("thread", restored);
    }

    @Test
    void createsAndPersistsFirstThreadOnDemand() {
        Fixture fixture = fixture();
        AgentNodeSession session = cacheSession();

        Map<String, Object> info = fixture.service.createThread(session, null, null);

        AiThread thread = session.getAiThread((String) info.get("threadId"));
        assertNotNull(thread);
        assertEquals(1, session.listAiThreads().size());
        assertSame(thread, session.getActiveThread());
        assertEquals("对话 1", thread.getTitle());
        verify(fixture.conversationStore).createAgentThread(
                eq("user-1"), eq("agent-1"), eq("host-1"), eq("session-1"), same(thread), isNull());
        verify(fixture.conversationStore).attachEventJournal(thread.getThreadId(), thread);
        verify(fixture.sessionWarmupService).warmupAsync("session-1");
    }

    @Test
    void persistsTransientInMemoryThreadBeforeFirstUse() {
        Fixture fixture = fixture();
        AgentNodeSession session = cacheSession();
        AiThread initialThread = session.createAiThread("initial-thread", "对话 1");
        when(fixture.conversationStore.findThread(initialThread.getThreadId())).thenReturn(null);

        NodeAiThreadService.ThreadResolution resolution =
                fixture.service.ensureThreadReady(session, initialThread.getThreadId(), null);

        assertSame(initialThread, resolution.thread());
        assertNull(resolution.errorMessage());
        verify(fixture.conversationStore).createAgentThread(
                eq("user-1"), eq("agent-1"), eq("host-1"), eq("session-1"), same(initialThread), isNull());
        verify(fixture.sessionWarmupService).warmupAsync("session-1");
    }

    @ParameterizedTest
    @CsvSource(value = {"null,null,null,null", "7,2,3,7", "null,2,3,2", "null,null,3,3"}, nullValues = "null")
    void preparesPersistedThreadWithOneChannelResolution(
            Integer requestedConfig, Integer cachedConfig, Integer persistedConfig, Integer expectedConfig) {
        Fixture fixture = fixture();
        AgentNodeSession session = cacheSession();
        AiThread thread = session.createAiThread("persisted-thread", "已有对话");
        thread.setAiConfigId(cachedConfig);
        AiThreadRecord record = ownedRecord(thread.getThreadId());
        record.setConfigId(persistedConfig);
        when(fixture.conversationStore.findThread(thread.getThreadId())).thenReturn(record);
        if (expectedConfig != null) {
            AiModelConfig config = new AiModelConfig();
            config.setId(expectedConfig);
            when(fixture.channels.optional(expectedConfig)).thenReturn(config);
        }

        NodeAiThreadService.ThreadResolution resolution =
                fixture.service.ensureThreadReady(session, thread.getThreadId(), requestedConfig);

        assertSame(thread, resolution.thread());
        assertNull(resolution.errorMessage());
        assertEquals(expectedConfig, thread.getAiConfigId());
        verify(fixture.channels).optional(expectedConfig);
        verify(fixture.conversationStore).attachEventJournal(thread.getThreadId(), thread);
        verify(fixture.conversationStore, never()).createAgentThread(
                eq("user-1"), eq("agent-1"), eq("host-1"), eq("session-1"), same(thread), nullable(AiModelConfig.class));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void channelResolutionFailureStopsBeforePersistenceOrWarmup(boolean inMemory) {
        Fixture fixture = fixture();
        AgentNodeSession session = cacheSession();
        if (inMemory) {
            session.createAiThread("thread", "未持久化").setAiConfigId(2);
        } else {
            AiThreadRecord record = ownedRecord("thread");
            record.setConfigId(2);
            when(fixture.conversationStore.findThread("thread")).thenReturn(record);
        }
        when(fixture.channels.optional(7)).thenThrow(new UseCaseException(UseCaseException.Kind.NOT_FOUND, "模型已删除"));

        NodeAiThreadService.ThreadResolution resolution =
                fixture.service.ensureThreadReady(session, "thread", 7);

        AiThread thread = resolution.thread();
        assertNotNull(thread);
        assertSame(session.getAiThread("thread"), thread);
        assertEquals("模型已删除", resolution.errorMessage());
        assertEquals(2, thread.getAiConfigId());
        verify(fixture.conversationStore, never()).createAgentThread(
                anyString(), anyString(), anyString(), anyString(), same(thread), nullable(AiModelConfig.class));
        verify(fixture.conversationStore, never()).attachEventJournal(anyString(), same(thread));
        verifyNoInteractions(fixture.sessionWarmupService, fixture.agentRegistry);
    }

    @Test
    void restoresThreadFromDatabaseBeforeFirstUse() {
        Fixture fixture = fixture();
        AgentNodeSession session = cacheSession();
        AiThreadRecord record = new AiThreadRecord();
        record.setThreadId("stored-thread");
        record.setScope(AiConversationStoreService.SCOPE_AGENT);
        record.setUserId("user-1");
        record.setAgentId("agent-1");
        record.setHostId("host-1");
        record.setTitle("历史对话");
        record.setSessionId("previous-connection");
        record.setCreatedAt(10L);
        record.setLastActiveAt(20L);
        record.setParentThreadId("parent-thread");
        when(fixture.conversationStore.findThread("stored-thread")).thenReturn(record);

        NodeAiThreadService.ThreadResolution resolution =
                fixture.service.ensureThreadReady(session, "stored-thread", null);

        AiThread thread = resolution.thread();
        assertNotNull(thread);
        assertNull(resolution.errorMessage());
        assertSame(thread, session.getAiThread("stored-thread"));
        assertEquals("历史对话", thread.getTitle());
        assertEquals(10L, thread.getCreatedAt());
        assertEquals(20L, thread.getLastActiveAt());
        assertEquals("parent-thread", thread.getParentThreadId());
        verify(fixture.conversationStore).attachEventJournal("stored-thread", thread);
        verify(fixture.conversationStore, never()).createAgentThread(
                eq("user-1"), eq("agent-1"), eq("host-1"), eq("session-1"), same(thread), isNull());
    }

    @Test
    void deletesThreadFromRuntimeAndDatabase() {
        Fixture fixture = fixture();
        AgentNodeSession session = cacheSession();
        session.createAiThread("thread-1", "test");

        fixture.service.deleteThread(session, "thread-1");

        assertNull(session.getAiThread("thread-1"));
        assertNull(session.getActiveThread());
        verify(fixture.agentRegistry).evict(session, "thread-1");
        verify(fixture.conversationStore).deleteThread("thread-1");
    }

    @ParameterizedTest
    @CsvSource(value = {"true,null,idle", "false,completed,completed", "false,failed,failed", "false,null,idle"}, nullValues = "null")
    void eventHistoryReadsWithoutRestoringOrRebindingRuntime(
            boolean inMemory, String persistedStatus, String expectedStatus) {
        Fixture fixture = fixture();
        AgentNodeSession session = cacheSession();
        AiThread thread = inMemory ? session.createAiThread("thread-1", "test") : null;
        if (thread != null) thread.configureEventJournal(3L, null);
        session.createAiThread("active", "当前对话");
        if (!inMemory) {
            AiThreadRecord record = ownedRecord("thread-1");
            record.setRunStatus(persistedStatus);
            when(fixture.conversationStore.findThread("thread-1")).thenReturn(record);
        }
        when(fixture.eventJournal.lastSequence("thread-1")).thenReturn(20L);

        Map<String, Object> data = fixture.service.threadEvents(session, "thread-1", 1L, null);

        assertEquals(20L, data.get("lastSeq"));
        assertEquals(expectedStatus, data.get("runStatus"));
        assertFalse(data.containsKey("status"));
        assertSame(thread, session.getAiThread("thread-1"));
        assertEquals("active", session.getActiveThreadId());
        verify(fixture.eventJournal).listAfter("thread-1", 1L, 200);
        verify(fixture.conversationStore, never()).attachEventJournal(anyString(), nullable(AiThread.class));
        verify(fixture.messageRepository, never()).findLatestPlanJson(anyString());
    }

    @Test
    void eventsDoNotRestoreAnotherNodesThread() {
        Fixture fixture = fixture();
        AiThreadRecord foreign = new AiThreadRecord();
        foreign.setThreadId("foreign");
        foreign.setAgentId("other-agent");
        foreign.setUserId("user-1");
        when(fixture.conversationStore.findThread("foreign")).thenReturn(foreign);

        assertEquals(UseCaseException.Kind.NOT_FOUND, assertThrows(UseCaseException.class,
                () -> fixture.service.threadEvents(cacheSession(), "foreign", null, null)).getKind());
        verify(fixture.eventJournal, never()).listAfter(anyString(), anyLong(), anyInt());
    }

    @Test
    void historyUsesTheCurrentCursorPageContract() {
        Fixture fixture = fixture();
        when(fixture.conversationStore.findThread("thread-1")).thenReturn(ownedRecord("thread-1"));
        List<Map<String, Object>> messages = List.of(Map.of("messageId", "message-1", "sequence", 1L));
        when(fixture.messageRepository.historyPage("thread-1", 0, 50, null))
                .thenReturn(new AiMessageRepository.HistoryPage(messages, 1L, false));

        assertEquals(Map.of("messages", messages,
                        "nextBeforeSequence", 1L, "hasMore", false),
                fixture.service.threadMessages(cacheSession(), "thread-1", null, null, null));
    }

    @ParameterizedTest
    @CsvSource({
            "agent,other-agent,user-1,host-1",
            "agent,agent-1,other-user,host-1",
            "platform,agent-1,user-1,host-1",
            "agent,agent-1,,host-1",
            "agent,agent-1,user-1,host-other",
            "agent,agent-1,user-1,"
    })
    void rejectsForeignThreadsForEveryEntryEvenWhenCached(String scope, String agentId, String userId, String hostId) {
        Fixture fixture = fixture();
        AgentNodeSession session = cacheSession();
        AiThread cached = session.createAiThread("foreign", "original");
        AiThreadRecord record = ownedRecord("foreign");
        record.setScope(scope);
        record.setAgentId(agentId);
        record.setUserId(userId);
        record.setHostId(hostId);
        when(fixture.conversationStore.findThread("foreign")).thenReturn(record);

        List<Runnable> operations = List.of(
                () -> fixture.service.threadMessages(session, "foreign", 0, 50, null),
                () -> fixture.service.threadMessages(session, "foreign", 0, 50, 20L),
                () -> fixture.service.threadEvents(session, "foreign", 0L, 200),
                () -> fixture.service.renameThread(session, "foreign", "changed"),
                () -> fixture.service.deleteThread(session, "foreign"),
                () -> fixture.service.requireThread(session, "foreign"),
                () -> fixture.service.ensureThreadReady(session, "foreign", null),
                () -> fixture.service.resetThread(session, "foreign", null),
                () -> fixture.service.switchChannel(session, "foreign", null));
        for (Runnable operation : operations) {
            assertEquals(UseCaseException.Kind.NOT_FOUND, assertThrows(UseCaseException.class, operation::run).getKind());
        }
        assertSame(cached, session.getAiThread("foreign"));
        assertEquals("original", cached.getTitle());
        verifyNoInteractions(fixture.messageRepository, fixture.eventJournal);
        verify(fixture.conversationStore, never()).deleteThread(anyString());
        verify(fixture.conversationStore, never()).renameThread(anyString(), anyString());
    }

    @Test
    void rejectsMissingThreadBeforeReadingOrMutatingStorage() {
        Fixture fixture = fixture();
        AgentNodeSession session = cacheSession();
        List<Runnable> operations = List.of(
                () -> fixture.service.threadMessages(session, "missing", 0, 50, null),
                () -> fixture.service.threadEvents(session, "missing", 0L, 200),
                () -> fixture.service.renameThread(session, "missing", "changed"),
                () -> fixture.service.deleteThread(session, "missing"),
                () -> fixture.service.requireThread(session, "missing"),
                () -> fixture.service.resetThread(session, "missing", null),
                () -> fixture.service.switchChannel(session, "missing", null));
        for (Runnable operation : operations) {
            assertEquals(UseCaseException.Kind.NOT_FOUND, assertThrows(UseCaseException.class, operation::run).getKind());
        }
        verifyNoInteractions(fixture.channels, fixture.agentRegistry);
        verify(fixture.conversationStore, never()).deleteThread(anyString());
        verify(fixture.conversationStore, never()).renameThread(anyString(), anyString());
        verifyNoInteractions(fixture.messageRepository, fixture.eventJournal);
        assertEquals(0, session.listAiThreads().size());
    }

    private static AiThreadRecord ownedRecord(String threadId) {
        AiThreadRecord record = new AiThreadRecord();
        record.setThreadId(threadId);
        record.setScope(AiConversationStoreService.SCOPE_AGENT);
        record.setAgentId("agent-1");
        record.setHostId("host-1");
        record.setUserId("user-1");
        return record;
    }

    private static AgentNodeSession cacheSession() {
        AgentNodeSession session = new AgentNodeSession();
        session.setSessionId("session-1");
        session.setCreateByUser("user-1");
        session.setCacheMode(true);
        session.setAgentId("agent-1");
        session.bindHostId("host-1");
        return session;
    }

    @Test
    void missingHostIsRejectedBeforeCreatingAnyThread() {
        Fixture fixture = fixture();
        AgentNodeSession session = new AgentNodeSession();
        session.setCreateByUser("user-1");
        session.setAgentId("agent-1");
        assertThrows(UseCaseException.class, () -> fixture.service.createThread(session, null, null));
        assertEquals(0, session.listAiThreads().size());
        org.mockito.Mockito.verifyNoInteractions(fixture.sessionWarmupService);
    }

    @Test
    void childThreadCreationChecksItsParentHostBeforeMutatingRuntime() {
        Fixture fixture = fixture();
        AgentNodeSession session = cacheSession();
        AiThreadRecord foreign = ownedRecord("parent");
        foreign.setHostId("host-other");
        when(fixture.conversationStore.findThread("parent")).thenReturn(foreign);
        assertThrows(UseCaseException.class,
                () -> fixture.service.createChildThread(session, "child", null, "parent"));
        assertEquals(0, session.listAiThreads().size());
        org.mockito.Mockito.verifyNoInteractions(fixture.sessionWarmupService);
    }

    private static Fixture fixture() {
        AiConversationStoreService conversationStore = mock(AiConversationStoreService.class);
        AiMessageRepository messageRepository = mock(AiMessageRepository.class);
        AiEventJournalRepository eventJournal = mock(AiEventJournalRepository.class);
        SessionWarmupService sessionWarmupService = mock(SessionWarmupService.class);
        AiTurnProtocolService protocol = mock(AiTurnProtocolService.class);
        when(protocol.snapshotThread(anyString(), nullable(String.class))).thenAnswer(invocation ->
                new AiTurnProtocolService.ThreadSnapshot(invocation.getArgument(1), false, null, List.of(), null));
        NodeAiAgentRegistry agentRegistry = mock(NodeAiAgentRegistry.class);
        AiModelChannelResolver channels = mock(AiModelChannelResolver.class);
        NodeAiThreadService service = new NodeAiThreadService(
                channels,
                conversationStore,
                messageRepository,
                sessionWarmupService,
                agentRegistry,
                new AiThreadQueryService(conversationStore, messageRepository, eventJournal, protocol));
        return new Fixture(service, conversationStore, messageRepository, eventJournal, sessionWarmupService, agentRegistry, channels);
    }

    private record Fixture(NodeAiThreadService service,
                           AiConversationStoreService conversationStore,
                           AiMessageRepository messageRepository,
                           AiEventJournalRepository eventJournal,
                           SessionWarmupService sessionWarmupService,
                           NodeAiAgentRegistry agentRegistry,
                           AiModelChannelResolver channels) {
    }
}
