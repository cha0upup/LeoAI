package org.leo.web.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.leo.ai.channel.AiModelConfigService;
import org.leo.ai.service.SessionWarmupService;
import org.leo.ai.thread.AiConversationStoreService;
import org.leo.core.entity.AiThreadRecord;
import org.leo.core.session.AiThread;
import org.leo.core.session.AgentNodeSession;
import org.leo.web.exception.ApiException;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NodeAiThreadServiceTest {

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
                eq("user-1"), eq("agent-1"), eq("session-1"), same(thread), isNull());
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
                eq("user-1"), eq("agent-1"), eq("session-1"), same(initialThread), isNull());
        verify(fixture.sessionWarmupService).warmupAsync("session-1");
    }

    @Test
    void doesNotRecreatePersistedThread() {
        Fixture fixture = fixture();
        AgentNodeSession session = cacheSession();
        AiThread thread = session.createAiThread("persisted-thread", "已有对话");
        AiThreadRecord record = ownedRecord(thread.getThreadId());
        when(fixture.conversationStore.findThread(thread.getThreadId())).thenReturn(record);

        NodeAiThreadService.ThreadResolution resolution =
                fixture.service.ensureThreadReady(session, thread.getThreadId(), null);

        assertSame(thread, resolution.thread());
        assertNull(resolution.errorMessage());
        verify(fixture.conversationStore, never()).createAgentThread(
                eq("user-1"), eq("agent-1"), eq("session-1"), same(thread), isNull());
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
        record.setTitle("历史对话");
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
        verify(fixture.conversationStore, never()).createAgentThread(
                eq("user-1"), eq("agent-1"), eq("session-1"), same(thread), isNull());
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

    @Test
    void eventsKeepDatabaseSequenceWhenRuntimeSequenceIsBehind() {
        Fixture fixture = fixture();
        AgentNodeSession session = cacheSession();
        AiThread thread = session.createAiThread("thread-1", "test");
        thread.configureEventJournal(3L, null);
        when(fixture.conversationStore.findLastEventSeq("thread-1")).thenReturn(20L);

        Map<String, Object> data = fixture.service.threadEvents(session, "thread-1", 1L, null);

        assertEquals(20L, data.get("lastSeq"));
        assertEquals("idle", data.get("runStatus"));
        assertFalse(data.containsKey("status"));
        verify(fixture.conversationStore).listEventsAfter("thread-1", 1L, 200);
        verify(fixture.conversationStore).attachEventJournal("thread-1", thread);
    }

    @Test
    void eventsDoNotRestoreAnotherNodesThread() {
        Fixture fixture = fixture();
        AiThreadRecord foreign = new AiThreadRecord();
        foreign.setThreadId("foreign");
        foreign.setAgentId("other-agent");
        foreign.setUserId("user-1");
        when(fixture.conversationStore.findThread("foreign")).thenReturn(foreign);

        assertEquals(404, assertThrows(ApiException.class,
                () -> fixture.service.threadEvents(cacheSession(), "foreign", null, null)).getCode());
        verify(fixture.conversationStore, never()).listEventsAfter(anyString(), anyLong(), anyInt());
    }

    @Test
    void historyKeepsExistingPaginationShape() {
        Fixture fixture = fixture();
        when(fixture.conversationStore.findThread("thread-1")).thenReturn(ownedRecord("thread-1"));
        List<Map<String, Object>> messages = List.of(Map.of("messageId", "message-1", "sequence", 1L));
        when(fixture.conversationStore.listMessages("thread-1", 0, 50))
                .thenReturn(messages);
        when(fixture.conversationStore.countMessages("thread-1")).thenReturn(1);

        assertEquals(Map.of("messages", messages, "total", 1, "offset", 0, "limit", 50,
                        "nextBeforeSequence", 1L, "hasMore", false),
                fixture.service.threadMessages(cacheSession(), "thread-1", -2, null, null));
    }

    @ParameterizedTest
    @CsvSource({
            "agent,other-agent,user-1",
            "agent,agent-1,other-user",
            "platform,agent-1,user-1",
            "agent,agent-1,"
    })
    void rejectsForeignThreadsForEveryEntryEvenWhenCached(String scope, String agentId, String userId) {
        Fixture fixture = fixture();
        AgentNodeSession session = cacheSession();
        AiThread cached = session.createAiThread("foreign", "original");
        AiThreadRecord record = ownedRecord("foreign");
        record.setScope(scope);
        record.setAgentId(agentId);
        record.setUserId(userId);
        when(fixture.conversationStore.findThread("foreign")).thenReturn(record);

        List<Runnable> operations = List.of(
                () -> fixture.service.threadMessages(session, "foreign", 0, 50, null),
                () -> fixture.service.threadMessages(session, "foreign", 0, 50, 20L),
                () -> fixture.service.renameThread(session, "foreign", "changed"),
                () -> fixture.service.deleteThread(session, "foreign"),
                () -> fixture.service.requireThread(session, "foreign"),
                () -> fixture.service.ensureThreadReady(session, "foreign", null),
                () -> fixture.service.resetThread(session, "foreign", null),
                () -> fixture.service.switchChannel(session, "foreign", null));
        for (Runnable operation : operations) {
            assertEquals(404, assertThrows(ApiException.class, operation::run).getCode());
        }
        assertSame(cached, session.getAiThread("foreign"));
        assertEquals("original", cached.getTitle());
        verify(fixture.conversationStore, never()).listMessages(anyString(), anyInt(), anyInt());
        verify(fixture.conversationStore, never()).deleteThread(anyString());
        verify(fixture.conversationStore, never()).renameThread(anyString(), anyString());
    }

    @Test
    void rejectsMissingThreadBeforeReadingOrMutatingStorage() {
        Fixture fixture = fixture();
        AgentNodeSession session = cacheSession();
        assertThrows(ApiException.class, () -> fixture.service.threadMessages(session, "missing", 0, 50, null));
        assertThrows(ApiException.class, () -> fixture.service.renameThread(session, "missing", "changed"));
        assertThrows(ApiException.class, () -> fixture.service.deleteThread(session, "missing"));
        verify(fixture.conversationStore, never()).deleteThread(anyString());
    }

    private static AiThreadRecord ownedRecord(String threadId) {
        AiThreadRecord record = new AiThreadRecord();
        record.setThreadId(threadId);
        record.setScope(AiConversationStoreService.SCOPE_AGENT);
        record.setAgentId("agent-1");
        record.setUserId("user-1");
        return record;
    }

    @Test
    void transientThreadListIncludesEachRuntimeOnceAndKeepsMessageCounts() {
        Fixture fixture = fixture();
        AgentNodeSession session = new AgentNodeSession();
        session.restoreAiThread("older", "older", 1L, 10L);
        session.restoreAiThread("newer", "newer", 2L, 20L);
        when(fixture.conversationStore.countMessages("older")).thenReturn(4);
        when(fixture.conversationStore.countMessages("newer")).thenReturn(7);

        Map<String, Object> data = fixture.service.listThreads(session);
        List<?> threads = (List<?>) data.get("threads");

        assertEquals(2, threads.size());
        Map<?, ?> first = (Map<?, ?>) threads.get(0);
        Map<?, ?> second = (Map<?, ?>) threads.get(1);
        assertEquals("newer", first.get("threadId"));
        assertEquals(7, first.get("messageCount"));
        assertEquals("older", second.get("threadId"));
        assertEquals(4, second.get("messageCount"));
        assertFalse(first.containsKey("hasCheckpoint"));
        verify(fixture.conversationStore, never()).listAgentThreads(anyString(), anyString());
    }

    @Test
    void persistedAndRuntimeThreadsMergeWithoutLosingConfiguration() {
        Fixture fixture = fixture();
        AgentNodeSession session = cacheSession();
        session.restoreAiThread("shared", "runtime title", 1L, 100L);
        session.restoreAiThread("runtime-only", "runtime only", 1L, 50L);
        AiThreadRecord shared = new AiThreadRecord();
        shared.setThreadId("shared");
        shared.setMessageCount(8);
        shared.setConfigName("channel");
        shared.setConfigProtocol("openai");
        shared.setConfigModel("model");
        AiThreadRecord stored = new AiThreadRecord();
        stored.setThreadId("stored-only");
        stored.setTitle("stored title");
        stored.setLastActiveAt(200L);
        when(fixture.conversationStore.listAgentThreads("user-1", "agent-1"))
                .thenReturn(List.of(shared, stored));

        List<?> threads = (List<?>) fixture.service.listThreads(session).get("threads");

        assertEquals(3, threads.size());
        Map<?, ?> storedItem = (Map<?, ?>) threads.get(0);
        Map<?, ?> sharedItem = (Map<?, ?>) threads.get(1);
        assertEquals("stored-only", storedItem.get("threadId"));
        assertEquals(false, storedItem.get("inMemory"));
        assertEquals(0, storedItem.get("messageCount"));
        assertEquals("idle", storedItem.get("runStatus"));
        assertEquals("shared", sharedItem.get("threadId"));
        assertEquals("runtime title", sharedItem.get("title"));
        assertEquals(true, sharedItem.get("inMemory"));
        assertFalse(sharedItem.containsKey("hasCheckpoint"));
        assertFalse(storedItem.containsKey("hasCheckpoint"));
        assertFalse(((Map<?, ?>) threads.get(2)).containsKey("hasCheckpoint"));
        assertEquals(8, sharedItem.get("messageCount"));
        assertEquals("channel", sharedItem.get("configName"));
        assertEquals("openai", sharedItem.get("configProtocol"));
        assertEquals("model", sharedItem.get("configModel"));
    }

    private static AgentNodeSession cacheSession() {
        AgentNodeSession session = new AgentNodeSession();
        session.setSessionId("session-1");
        session.setCreateByUser("user-1");
        session.setCacheMode(true);
        session.setAgentId("agent-1");
        return session;
    }

    private static Fixture fixture() {
        AiConversationStoreService conversationStore = mock(AiConversationStoreService.class);
        SessionWarmupService sessionWarmupService = mock(SessionWarmupService.class);
        AiTurnProtocolService protocol = mock(AiTurnProtocolService.class);
        when(protocol.snapshotThread(anyString(), nullable(String.class))).thenAnswer(invocation ->
                new AiTurnProtocolService.ThreadSnapshot(invocation.getArgument(1), false, null, List.of(), null));
        NodeAiAgentRegistry agentRegistry = mock(NodeAiAgentRegistry.class);
        NodeAiThreadService service = new NodeAiThreadService(
                mock(AiModelConfigService.class),
                mock(AiModelChannelResolver.class),
                conversationStore,
                sessionWarmupService,
                agentRegistry,
                new AiThreadQueryService(conversationStore, protocol));
        return new Fixture(service, conversationStore, sessionWarmupService, agentRegistry);
    }

    private record Fixture(NodeAiThreadService service,
                           AiConversationStoreService conversationStore,
                           SessionWarmupService sessionWarmupService,
                           NodeAiAgentRegistry agentRegistry) {
    }
}
