package org.leo.web.service;

import org.junit.jupiter.api.Test;
import org.leo.ai.channel.AiModelConfigService;
import org.leo.ai.service.SessionWarmupService;
import org.leo.ai.thread.AiConversationStoreService;
import org.leo.core.entity.AiThreadRecord;
import org.leo.core.session.AiThread;
import org.leo.core.session.PuppetNodeSession;
import org.leo.core.repository.session.PuppetAiCheckpointRepository;
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

class PuppetNodeAiThreadServiceTest {

    @Test
    void createsAndPersistsFirstThreadOnDemand() {
        Fixture fixture = fixture();
        PuppetNodeSession session = cacheSession();

        Map<String, Object> info = fixture.service.createThread(session, null, null);

        AiThread thread = session.getAiThread((String) info.get("threadId"));
        assertNotNull(thread);
        assertEquals(1, session.listAiThreads().size());
        assertSame(thread, session.getActiveThread());
        assertEquals("对话 1", thread.getTitle());
        verify(fixture.conversationStore).createPuppetThread(
                eq("user-1"), eq("puppet-1"), eq("session-1"), same(thread), isNull());
        verify(fixture.conversationStore).attachEventJournal(thread.getThreadId(), thread);
        verify(fixture.sessionWarmupService).warmupAsync("session-1");
    }

    @Test
    void persistsTransientInMemoryThreadBeforeFirstUse() {
        Fixture fixture = fixture();
        PuppetNodeSession session = cacheSession();
        AiThread initialThread = session.createAiThread("initial-thread", "对话 1");
        when(fixture.conversationStore.findThread(initialThread.getThreadId())).thenReturn(null);

        PuppetNodeAiThreadService.ThreadResolution resolution =
                fixture.service.ensureThreadReady(session, initialThread.getThreadId(), null);

        assertSame(initialThread, resolution.thread());
        assertNull(resolution.errorMessage());
        verify(fixture.conversationStore).createPuppetThread(
                eq("user-1"), eq("puppet-1"), eq("session-1"), same(initialThread), isNull());
        verify(fixture.sessionWarmupService).warmupAsync("session-1");
    }

    @Test
    void doesNotRecreatePersistedThread() {
        Fixture fixture = fixture();
        PuppetNodeSession session = cacheSession();
        AiThread thread = session.createAiThread("persisted-thread", "已有对话");
        AiThreadRecord record = new AiThreadRecord();
        record.setThreadId(thread.getThreadId());
        when(fixture.conversationStore.findThread(thread.getThreadId())).thenReturn(record);

        PuppetNodeAiThreadService.ThreadResolution resolution =
                fixture.service.ensureThreadReady(session, thread.getThreadId(), null);

        assertSame(thread, resolution.thread());
        assertNull(resolution.errorMessage());
        verify(fixture.conversationStore, never()).createPuppetThread(
                eq("user-1"), eq("puppet-1"), eq("session-1"), same(thread), isNull());
    }

    @Test
    void eventsKeepDatabaseSequenceWhenRuntimeSequenceIsBehind() {
        Fixture fixture = fixture();
        PuppetNodeSession session = cacheSession();
        AiThread thread = session.createAiThread("thread-1", "test");
        thread.configureEventJournal(3L, null);
        when(fixture.conversationStore.findLastEventSeq("thread-1")).thenReturn(20L);

        Map<String, Object> data = fixture.service.threadEvents(session, "thread-1", 1L, null);

        assertEquals(20L, data.get("lastSeq"));
        assertEquals("idle", data.get("status"));
        assertFalse(data.containsKey("elapsedMs"));
        verify(fixture.conversationStore).listEventsAfter("thread-1", 1L, 200);
        verify(fixture.conversationStore).attachEventJournal("thread-1", thread);
    }

    @Test
    void eventsDoNotRestoreAnotherNodesThread() {
        Fixture fixture = fixture();
        AiThreadRecord foreign = new AiThreadRecord();
        foreign.setThreadId("foreign");
        foreign.setPuppetId("other-puppet");
        foreign.setUserId("user-1");
        when(fixture.conversationStore.findThread("foreign")).thenReturn(foreign);

        assertEquals(404, assertThrows(ApiException.class,
                () -> fixture.service.threadEvents(cacheSession(), "foreign", null, null)).getCode());
        verify(fixture.conversationStore, never()).listEventsAfter(anyString(), anyLong(), anyInt());
    }

    @Test
    void historyKeepsExistingPaginationShape() {
        Fixture fixture = fixture();
        when(fixture.conversationStore.listMessages("thread-1", 0, 50))
                .thenReturn(List.of(Map.of("id", "message-1")));
        when(fixture.conversationStore.countMessages("thread-1")).thenReturn(1);

        assertEquals(Map.of("messages", List.of(Map.of("id", "message-1")), "total", 1, "offset", 0, "limit", 50),
                fixture.service.threadMessages(cacheSession(), "thread-1", -2, null));
    }

    @Test
    void transientThreadListIncludesEachRuntimeOnceAndKeepsMessageCounts() {
        Fixture fixture = fixture();
        PuppetNodeSession session = new PuppetNodeSession();
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
        verify(fixture.conversationStore, never()).listPuppetThreads(anyString(), anyString());
    }

    @Test
    void persistedAndRuntimeThreadsMergeWithoutLosingConfigurationOrCheckpointFlags() {
        Fixture fixture = fixture();
        PuppetNodeSession session = cacheSession();
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
        when(fixture.conversationStore.listPuppetThreads("user-1", "puppet-1"))
                .thenReturn(List.of(shared, stored));
        when(fixture.checkpoints.exists("user-1", "puppet-1", "shared")).thenReturn(true);

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
        assertEquals(true, sharedItem.get("hasCheckpoint"));
        assertEquals(8, sharedItem.get("messageCount"));
        assertEquals("channel", sharedItem.get("configName"));
        assertEquals("openai", sharedItem.get("configProtocol"));
        assertEquals("model", sharedItem.get("configModel"));
    }

    private static PuppetNodeSession cacheSession() {
        PuppetNodeSession session = new PuppetNodeSession();
        session.setSessionId("session-1");
        session.setCreateByUser("user-1");
        session.setCacheMode(true);
        session.setPuppetId("puppet-1");
        return session;
    }

    private static Fixture fixture() {
        AiConversationStoreService conversationStore = mock(AiConversationStoreService.class);
        SessionWarmupService sessionWarmupService = mock(SessionWarmupService.class);
        AiTurnProtocolService protocol = mock(AiTurnProtocolService.class);
        when(protocol.snapshotThread(anyString(), nullable(String.class))).thenAnswer(invocation ->
                new AiTurnProtocolService.ThreadSnapshot(invocation.getArgument(1), false, null, List.of()));
        PuppetAiCheckpointRepository checkpoints = mock(PuppetAiCheckpointRepository.class);
        PuppetNodeAiThreadService service = new PuppetNodeAiThreadService(
                mock(AiModelConfigService.class),
                mock(AiModelChannelResolver.class),
                conversationStore,
                sessionWarmupService,
                mock(PuppetNodeAiAgentRegistry.class),
                new AiThreadQueryService(conversationStore, protocol), checkpoints);
        return new Fixture(service, conversationStore, sessionWarmupService, checkpoints);
    }

    private record Fixture(PuppetNodeAiThreadService service,
                           AiConversationStoreService conversationStore,
                           SessionWarmupService sessionWarmupService,
                           PuppetAiCheckpointRepository checkpoints) {
    }
}
