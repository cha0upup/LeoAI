package org.leo.web.application.ai;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.leo.ai.platform.PlatformAiState;
import org.leo.ai.platform.PlatformAiStateStore;
import org.leo.ai.thread.AiConversationStoreService;
import org.leo.ai.thread.AiEventJournalRepository;
import org.leo.ai.thread.AiMessageRepository;
import org.leo.core.ai.AiRuntimeState;
import org.leo.core.entity.AiSseEvent;
import org.leo.core.entity.AiThreadRecord;
import org.leo.core.entity.AiUserInputRequest;
import org.leo.core.session.AgentNodeSession;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AiThreadQueryServiceTest {

    private static final String PLATFORM_THREAD_ID = "platform-query-test";

    private final AiConversationStoreService store = mock(AiConversationStoreService.class);
    private final AiMessageRepository messageRepository = mock(AiMessageRepository.class);
    private final AiEventJournalRepository journal = mock(AiEventJournalRepository.class);
    private final AiTurnProtocolService protocol = mock(AiTurnProtocolService.class);
    private final AiThreadQueryService queries = new AiThreadQueryService(store, messageRepository, journal, protocol);

    @BeforeEach
    void useFallbackProtocolStatus() {
        when(protocol.snapshotThread(anyString(), nullable(String.class))).thenAnswer(invocation ->
                new AiTurnProtocolService.ThreadSnapshot(invocation.getArgument(1), false, null, List.of(), null));
    }

    @AfterEach
    void releasePlatformState() {
        PlatformAiStateStore.remove(PLATFORM_THREAD_ID);
    }

    @Test
    void transientThreadListIncludesEachRuntimeOnceAndKeepsMessageCounts() {
        AgentNodeSession session = new AgentNodeSession();
        session.restoreAiThread("older", "older", 1L, 10L);
        session.restoreAiThread("newer", "newer", 2L, 20L);
        String activeThreadId = session.getActiveThreadId();
        when(messageRepository.count("older")).thenReturn(4);
        when(messageRepository.count("newer")).thenReturn(7);

        Map<String, Object> data = queries.listNodeThreads(session);
        List<?> threads = (List<?>) data.get("threads");

        assertEquals(2, threads.size());
        Map<?, ?> first = (Map<?, ?>) threads.get(0);
        Map<?, ?> second = (Map<?, ?>) threads.get(1);
        assertEquals("newer", first.get("threadId"));
        assertEquals(7, first.get("messageCount"));
        assertEquals("older", second.get("threadId"));
        assertEquals(4, second.get("messageCount"));
        assertFalse(first.containsKey("hasCheckpoint"));
        assertFalse(first.containsKey("configName"));
        assertEquals(activeThreadId, data.get("activeThreadId"));
        assertEquals(activeThreadId, session.getActiveThreadId());
        verify(store, never()).listAgentThreads(anyString(), anyString(), anyString());
        verify(store, never()).attachEventJournal(anyString(), any());
    }

    @Test
    void persistedAndRuntimeThreadsMergeWithoutLosingConfiguration() {
        AgentNodeSession session = new AgentNodeSession();
        session.setCreateByUser("user-1");
        session.setCacheMode(true);
        session.setAgentId("agent-1");
        session.bindHostId("host-1");
        var runtime = session.restoreAiThread("shared", "runtime title", 1L, 100L);
        runtime.setAiConfigId(2);
        runtime.setParentThreadId("runtime-parent");
        session.restoreAiThread("runtime-only", "runtime only", 1L, 50L);
        AiThreadRecord shared = new AiThreadRecord();
        shared.setThreadId("shared");
        shared.setTitle("stored shared title");
        shared.setCreatedAt(999L);
        shared.setLastActiveAt(999L);
        shared.setConfigId(7);
        shared.setParentThreadId("stored-parent");
        shared.setMessageCount(8);
        shared.setConfigName("channel");
        shared.setConfigProtocol("openai");
        shared.setConfigModel("model");
        AiThreadRecord stored = new AiThreadRecord();
        stored.setThreadId("stored-only");
        stored.setTitle("stored title");
        stored.setLastActiveAt(200L);
        when(store.listAgentThreads("user-1", "agent-1", "host-1"))
                .thenReturn(List.of(shared, stored));

        List<?> threads = (List<?>) queries.listNodeThreads(session).get("threads");

        assertEquals(3, threads.size());
        Map<?, ?> storedItem = (Map<?, ?>) threads.get(0);
        Map<?, ?> sharedItem = (Map<?, ?>) threads.get(1);
        assertEquals("stored-only", storedItem.get("threadId"));
        assertEquals(false, storedItem.get("inMemory"));
        assertEquals(0, storedItem.get("messageCount"));
        assertEquals("idle", storedItem.get("runStatus"));
        assertEquals("shared", sharedItem.get("threadId"));
        assertEquals("runtime title", sharedItem.get("title"));
        assertEquals(1L, sharedItem.get("createdAt"));
        assertEquals(100L, sharedItem.get("lastActiveAt"));
        assertEquals(2, sharedItem.get("configId"));
        assertEquals("runtime-parent", sharedItem.get("parentThreadId"));
        assertEquals(true, sharedItem.get("inMemory"));
        assertFalse(sharedItem.containsKey("hasCheckpoint"));
        assertFalse(storedItem.containsKey("hasCheckpoint"));
        assertFalse(((Map<?, ?>) threads.get(2)).containsKey("hasCheckpoint"));
        assertEquals(8, sharedItem.get("messageCount"));
        assertEquals("channel", sharedItem.get("configName"));
        assertEquals("openai", sharedItem.get("configProtocol"));
        assertEquals("model", sharedItem.get("configModel"));
        verify(messageRepository, never()).count("shared");
        verify(store, never()).attachEventJournal(anyString(), any());
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(longs = {1L, Long.MAX_VALUE})
    void platformListPreservesConfigurationAndUsesLatestActivityAndProtocolState(Long lastActiveAt) {
        AiThreadRecord record = new AiThreadRecord();
        record.setThreadId(PLATFORM_THREAD_ID);
        record.setConfigId(7);
        record.setConfigName("channel");
        record.setConfigProtocol("openai");
        record.setConfigModel("model");
        record.setLastActiveAt(lastActiveAt);
        AiThreadRecord stored = new AiThreadRecord();
        stored.setThreadId(PLATFORM_THREAD_ID + "-stored");
        stored.setLastActiveAt(2L);
        AiThreadRecord undated = new AiThreadRecord();
        undated.setThreadId(PLATFORM_THREAD_ID + "-undated");
        when(store.listPlatformThreads("user-1")).thenReturn(lastActiveAt != null && lastActiveAt > 2L
                ? List.of(record, stored, undated) : List.of(stored, record, undated));
        PlatformAiState state = PlatformAiStateStore.getOrCreate(PLATFORM_THREAD_ID);
        state.setAiConfigId(99);
        when(protocol.snapshotThread(PLATFORM_THREAD_ID, state.getRunStatus()))
                .thenReturn(new AiTurnProtocolService.ThreadSnapshot("queued", true, null, List.of(), null));

        List<Map<String, Object>> threads = queries.listPlatformThreads("user-1");
        assertEquals(List.of(PLATFORM_THREAD_ID, stored.getThreadId(), undated.getThreadId()),
                threads.stream().map(thread -> thread.get("threadId")).toList());
        Map<String, Object> item = threads.get(0);

        assertEquals("queued", item.get("runStatus"));
        assertEquals(true, item.get("executing"));
        assertEquals(7, item.get("configId"));
        assertEquals("channel", item.get("configName"));
        assertEquals("openai", item.get("configProtocol"));
        assertEquals("model", item.get("configModel"));
        assertEquals(0, item.get("messageCount"));
        assertEquals(Math.max(lastActiveAt != null ? lastActiveAt : 0L, state.getLastActiveAt()),
                item.get("lastActiveAt"));
        assertFalse(item.containsKey("inMemory"));
        assertFalse(item.containsKey("parentThreadId"));
        assertFalse(state.isExecuting());
        verify(store, never()).attachEventJournal(anyString(), any());
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"completed", "failed"})
    void platformListPassesPersistedStatusDirectlyToTheProtocolSnapshot(String status) {
        AiThreadRecord record = new AiThreadRecord();
        record.setThreadId(PLATFORM_THREAD_ID);
        record.setConfigId(7);
        record.setRunStatus(status);
        record.setLastActiveAt(100L);
        when(store.listPlatformThreads("user-1")).thenReturn(List.of(record));

        Map<String, Object> item = queries.listPlatformThreads("user-1").get(0);

        assertEquals(status, item.get("runStatus"));
        assertEquals(false, item.get("executing"));
        assertEquals(100L, item.get("lastActiveAt"));
        assertNull(PlatformAiStateStore.get(PLATFORM_THREAD_ID));
        verify(protocol).snapshotThread(PLATFORM_THREAD_ID, status);
    }

    @Test
    void returnsStableCursorMetadataAndIgnoresOffset() {
        List<Map<String, Object>> messages = List.of(
                Map.of("messageId", "m-10", "sequence", 10L),
                Map.of("messageId", "m-11", "sequence", 11L));
        when(messageRepository.listBefore("thread", 20L, -1)).thenReturn(messages);
        when(messageRepository.hasBefore("thread", 10L)).thenReturn(true);

        Map<String, Object> page = queries.messages("thread", 7, -1, 20L);

        assertEquals(messages, page.get("messages"));
        assertEquals(10L, page.get("nextBeforeSequence"));
        assertEquals(true, page.get("hasMore"));
        verify(messageRepository).listBefore("thread", 20L, -1);
        verify(messageRepository, never()).count(anyString());
        assertEquals(java.util.Set.of("messages", "nextBeforeSequence", "hasMore"), page.keySet());
        verify(messageRepository, never()).list(anyString(), anyInt(), anyInt());
    }

    @Test
    void initialAndEmptyHistoryPagesKeepCursorContract() {
        Map<String, Object> page = queries.messages("thread", null, null, null);
        assertEquals(List.of(), page.get("messages"));
        assertNull(page.get("nextBeforeSequence"));
        assertEquals(false, page.get("hasMore"));
        verify(messageRepository).list("thread", 0, 50);
        verify(messageRepository, never()).hasBefore(anyString(), anyLong());
    }

    @ParameterizedTest
    @ValueSource(longs = {0L, -1L, Long.MIN_VALUE})
    void rejectsInvalidHistoryCursorBeforeReadingStorage(long cursor) {
        assertThrows(org.leo.web.application.UseCaseException.class,
                () -> queries.messages("thread", 0, 50, cursor));
        verifyNoInteractions(store, messageRepository, journal, protocol);
    }

    @Test
    void forwardsExplicitOffsetAndLimitToTheRepository() {
        List<Map<String, Object>> messages = List.of(Map.of("messageId", "message-1", "sequence", 1L, "content", "hello"));
        when(messageRepository.list("thread", -8, 500)).thenReturn(messages);

        assertEquals(Map.of("messages", messages,
                        "nextBeforeSequence", 1L, "hasMore", false),
                queries.messages("thread", -8, 500, null));
        verify(messageRepository, never()).count(anyString());
    }

    @ParameterizedTest
    @CsvSource(value = {"null,15,20,20", "0,25,20,25", "-1,15,20,20", "5,15,20,5"}, nullValues = "null")
    void usesLatestTurnStartOnlyWhenNoPositiveCursorWasRequested(
            Long cursor, long memoryStart, long persistedStart, long expectedCursor) {
        AiRuntimeState runtime = new AiRuntimeState();
        runtime.configureEventJournal(memoryStart, null);
        runtime.claimExecution();
        when(journal.latestTurnStartSequence("thread")).thenReturn(persistedStart);
        when(journal.lastSequence("thread")).thenReturn(50L);
        when(protocol.snapshotThread("thread", "running"))
                .thenReturn(new AiTurnProtocolService.ThreadSnapshot("running", true, null, List.of(), null));

        Map<String, Object> data = queries.events("thread", runtime, "running", cursor, null);

        verify(journal).listAfter("thread", expectedCursor, 200);
        if (cursor != null && cursor > 0) verify(journal, never()).latestTurnStartSequence("thread");
        assertEquals(50L, data.get("lastSeq"));
        assertEquals("running", data.get("runStatus"));
        assertFalse(data.containsKey("status"));
        assertEquals(true, data.get("executing"));
    }

    @ParameterizedTest
    @ValueSource(longs = {3, 80})
    void keepsHighestEventSequenceAndLetsProtocolOverrideRuntimeStatus(long memorySequence) {
        AiRuntimeState runtime = new AiRuntimeState();
        runtime.configureEventJournal(memorySequence, null);
        runtime.claimExecution();
        runtime.stop("user stopped");
        when(journal.lastSequence("thread")).thenReturn(50L);
        AiUserInputRequest question = mock(AiUserInputRequest.class);
        when(question.toMap()).thenReturn(Map.of("id", "question-1"));
        when(protocol.snapshotThread("thread", "cancelled"))
                .thenReturn(new AiTurnProtocolService.ThreadSnapshot("waiting_for_user", false, null, List.of(), question));

        Map<String, Object> data = queries.events("thread", runtime, "cancelled", 2L, 10);

        assertEquals(Math.max(memorySequence, 50L), data.get("lastSeq"));
        assertEquals("waiting_for_user", data.get("runStatus"));
        assertFalse(data.containsKey("status"));
        assertEquals(false, data.get("executing"));
        assertEquals("user stopped", data.get("stopReason"));
        assertEquals(Map.of("id", "question-1"), data.get("pendingUserInput"));
        verify(journal).listAfter("thread", 2L, 10);
    }

    @Test
    void queriesPersistedOnlyThreadAndPreservesEventIdentifiers() {
        AiSseEvent tagged = new AiSseEvent(41L, 100L, "patch", Map.of("kind", "tool"),
                "subagent-1", "turn-1", "item-1", "run-1");
        AiSseEvent untagged = new AiSseEvent(42L, 101L, "delta", "hello", null, null, null, null);
        when(journal.latestTurnStartSequence("thread")).thenReturn(40L);
        when(journal.lastSequence("thread")).thenReturn(42L);
        when(journal.listAfter("thread", 40L, 200)).thenReturn(List.of(tagged, untagged));
        when(protocol.snapshotThread("thread", "completed"))
                .thenReturn(new AiTurnProtocolService.ThreadSnapshot("completed", false, null, List.of(), null));

        Map<String, Object> data = queries.events("thread", null, "completed", null, null);

        assertEquals(List.of(
                Map.of("seq", 41L, "timestamp", 100L, "name", "patch", "data", Map.of("kind", "tool"),
                        "subagentInvocationId", "subagent-1", "turnId", "turn-1", "itemId", "item-1", "runId", "run-1"),
                Map.of("seq", 42L, "timestamp", 101L, "name", "delta", "data", "hello")), data.get("events"));
        assertEquals(42L, data.get("lastSeq"));
        assertEquals("completed", data.get("runStatus"));
        assertFalse(data.containsKey("status"));
        assertFalse((boolean) data.get("executing"));
        assertTrue(data.containsKey("stopReason"));
        assertNull(data.get("stopReason"));
        assertEquals(List.of(), data.get("queuedTurns"));
        assertEquals(0, data.get("pendingTurnCount"));
    }

    @Test
    void omitsThreadStatusForHistoricalThreadsWithoutRuntimeStatus() {
        when(protocol.snapshotThread("thread", null))
                .thenReturn(new AiTurnProtocolService.ThreadSnapshot(null, false, null, List.of(), null));

        Map<String, Object> data = queries.events("thread", null, null, null, null);

        verify(protocol).snapshotThread("thread", null);
        assertTrue(data.containsKey("runStatus"));
        assertFalse(data.containsKey("status"));
        assertNull(data.get("runStatus"));
    }
}
