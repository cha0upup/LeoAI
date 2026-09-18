package org.leo.web.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.leo.ai.thread.AiConversationStoreService;
import org.leo.core.ai.AiRuntimeState;
import org.leo.core.entity.AiSseEvent;
import org.leo.core.entity.AiUserInputRequest;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AiThreadQueryServiceTest {

    private final AiConversationStoreService store = mock(AiConversationStoreService.class);
    private final AiTurnProtocolService protocol = mock(AiTurnProtocolService.class);
    private final AiThreadQueryService queries = new AiThreadQueryService(store, protocol);

    @ParameterizedTest
    @CsvSource(value = {"null,null,0,50", "-8,15,0,15", "7,20,7,20"}, nullValues = "null")
    void preservesMessagePagination(Integer offset, Integer limit, int expectedOffset, int expectedLimit) {
        List<Map<String, Object>> messages = List.of(Map.of("id", "message-1", "content", "hello"));
        when(store.listMessages("thread", expectedOffset, expectedLimit)).thenReturn(messages);
        when(store.countMessages("thread")).thenReturn(30);

        assertEquals(Map.of("messages", messages, "total", 30, "offset", expectedOffset, "limit", expectedLimit),
                queries.messages("thread", offset, limit));
        verify(store).listMessages("thread", expectedOffset, expectedLimit);
    }

    @ParameterizedTest
    @CsvSource(value = {"null,15,20,20", "0,25,20,25", "-1,15,20,20", "5,15,20,5"}, nullValues = "null")
    void usesLatestTurnStartOnlyWhenNoPositiveCursorWasRequested(
            Long cursor, long memoryStart, long persistedStart, long expectedCursor) {
        AiRuntimeState runtime = new AiRuntimeState();
        runtime.configureEventJournal(memoryStart, null);
        runtime.claimExecution();
        when(store.findLatestTurnStartSeq("thread")).thenReturn(persistedStart);
        when(store.findLastEventSeq("thread")).thenReturn(50L);
        when(protocol.snapshotThread("thread", "running"))
                .thenReturn(new AiTurnProtocolService.ThreadSnapshot("running", true, null, List.of()));

        Map<String, Object> data = queries.events("thread", runtime, "running", cursor, null);

        verify(store).listEventsAfter("thread", expectedCursor, 200);
        if (cursor != null && cursor > 0) verify(store, never()).findLatestTurnStartSeq("thread");
        assertEquals(50L, data.get("lastSeq"));
        assertEquals("running", data.get("status"));
        assertEquals(true, data.get("executing"));
    }

    @ParameterizedTest
    @ValueSource(longs = {3, 80})
    void keepsHighestEventSequenceAndLetsProtocolOverrideRuntimeStatus(long memorySequence) {
        AiRuntimeState runtime = new AiRuntimeState();
        runtime.configureEventJournal(memorySequence, null);
        runtime.claimExecution();
        runtime.stop("user stopped");
        when(store.findLastEventSeq("thread")).thenReturn(50L);
        AiUserInputRequest question = mock(AiUserInputRequest.class);
        when(question.toMap()).thenReturn(Map.of("id", "question-1"));
        when(protocol.snapshotThread("thread", "cancelled"))
                .thenReturn(new AiTurnProtocolService.ThreadSnapshot("waiting_for_user", false, null, List.of(), question));

        Map<String, Object> data = queries.events("thread", runtime, "cancelled", 2L, 10);

        assertEquals(Math.max(memorySequence, 50L), data.get("lastSeq"));
        assertEquals("waiting_for_user", data.get("status"));
        assertEquals("waiting_for_user", data.get("runStatus"));
        assertEquals(false, data.get("executing"));
        assertEquals("user stopped", data.get("stopReason"));
        assertEquals(Map.of("id", "question-1"), data.get("pendingUserInput"));
        verify(store).listEventsAfter("thread", 2L, 10);
    }

    @Test
    void queriesPersistedOnlyThreadAndPreservesEventIdentifiers() {
        AiSseEvent tagged = new AiSseEvent(41L, 100L, "patch", Map.of("kind", "tool"),
                "subagent-1", "turn-1", "item-1", "run-1");
        AiSseEvent untagged = new AiSseEvent(42L, 101L, "delta", "hello", null, null, null, null);
        when(store.findLatestTurnStartSeq("thread")).thenReturn(40L);
        when(store.findLastEventSeq("thread")).thenReturn(42L);
        when(store.listEventsAfter("thread", 40L, 200)).thenReturn(List.of(tagged, untagged));
        when(protocol.snapshotThread("thread", "completed"))
                .thenReturn(new AiTurnProtocolService.ThreadSnapshot("completed", false, null, List.of()));

        Map<String, Object> data = queries.events("thread", null, "completed", null, null);

        assertEquals(List.of(
                Map.of("seq", 41L, "timestamp", 100L, "name", "patch", "data", Map.of("kind", "tool"),
                        "subagentInvocationId", "subagent-1", "turnId", "turn-1", "itemId", "item-1", "runId", "run-1"),
                Map.of("seq", 42L, "timestamp", 101L, "name", "delta", "data", "hello")), data.get("events"));
        assertEquals(42L, data.get("lastSeq"));
        assertEquals("completed", data.get("status"));
        assertFalse((boolean) data.get("executing"));
        assertTrue(data.containsKey("stopReason"));
        assertNull(data.get("stopReason"));
        assertEquals(List.of(), data.get("queuedTurns"));
        assertEquals(0, data.get("pendingTurnCount"));
    }

    @Test
    void preservesNullStatusForHistoricalThreadsWithoutRuntimeStatus() {
        when(protocol.snapshotThread("thread", null))
                .thenReturn(new AiTurnProtocolService.ThreadSnapshot(null, false, null, List.of()));

        Map<String, Object> data = queries.events("thread", null, null, null, null);

        verify(protocol).snapshotThread("thread", null);
        assertTrue(data.containsKey("status"));
        assertTrue(data.containsKey("runStatus"));
        assertNull(data.get("status"));
        assertNull(data.get("runStatus"));
    }
}
