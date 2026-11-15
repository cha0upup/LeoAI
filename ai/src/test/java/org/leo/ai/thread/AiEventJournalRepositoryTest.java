package org.leo.ai.thread;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.leo.core.entity.AiEventRecord;
import org.leo.core.entity.AiSseEvent;
import org.leo.core.session.AiThread;
import org.leo.dao.mapper.AiConversationMapper;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AiEventJournalRepositoryTest {
    private final AiConversationMapper mapper = mock(AiConversationMapper.class);
    private final AiEventJournalRepository repository = new AiEventJournalRepository(mapper);

    @ParameterizedTest
    @CsvSource({"-1, 0, 0, 200", "3, -1, 3, 200", "3, 1, 3, 1", "3, 5000, 3, 2000"})
    void replayBoundsAreAppliedAtThePersistenceBoundary(long cursor, int limit,
                                                        long expectedCursor, int expectedLimit) {
        assertTrue(repository.listAfter("thread-1", cursor, limit).isEmpty());

        verify(mapper).listEventsAfter("thread-1", expectedCursor, expectedLimit);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = " ")
    void blankThreadIdsDoNotReadTheJournal(String threadId) {
        assertTrue(repository.listAfter(threadId, 0, 200).isEmpty());
        assertEquals(0L, repository.lastSequence(threadId));
        assertEquals(0L, repository.latestTurnStartSequence(threadId));
        verifyNoInteractions(mapper);
    }

    @Test
    void attachesPersistentJournalAndContinuesThreadSequence() {
        when(mapper.findLastEventSeq("thread-1")).thenReturn(41L);
        when(mapper.insertEvent(
                anyString(), any(), eq("thread-1"), eq("turn-1"),
                eq("item-1"), eq("subagent-1"), eq(42L),
                anyLong(), eq("delta"), eq("{\"text\":\"hello\"}"),
                isNull(), anyLong())).thenReturn(1);
        AiThread thread = new AiThread("thread-1", "test");
        thread.bindActiveTurnId("turn-1");
        thread.bindActiveItemId("item-1");
        thread.bindActiveRunId("run-1");

        repository.attach("thread-1", thread);
        AiSseEvent event = thread.recordSseEvent(
                "delta", Map.of("text", "hello"), "subagent-1");

        assertEquals(42L, event.seq());
        verify(mapper).insertEvent(
                anyString(), eq("run-1"), eq("thread-1"), eq("turn-1"),
                eq("item-1"), eq("subagent-1"), eq(42L), anyLong(),
                eq("delta"), eq("{\"text\":\"hello\"}"), isNull(), anyLong());
    }

    @Test
    void restoresPersistedEventsWithRoutingMetadata() {
        AiEventRecord row = new AiEventRecord();
        row.setEventId("event-1");
        row.setThreadId("thread-1");
        row.setTurnId("turn-1");
        row.setItemId("item-1");
        row.setRunId("run-1");
        row.setSubagentInvocationId("subagent-1");
        row.setEventSeq(9L);
        row.setTimestamp(100L);
        row.setName("node");
        row.setDataJson("{\"kind\":\"thinking\"}");
        when(mapper.listEventsAfter("thread-1", 8L, 200))
                .thenReturn(List.of(row));

        AiSseEvent event = repository
                .listAfter("thread-1", 8L, 200).get(0);

        assertEquals(9L, event.seq());
        assertEquals("turn-1", event.turnId());
        assertEquals("item-1", event.itemId());
        assertEquals("run-1", event.runId());
        assertEquals("subagent-1", event.subagentInvocationId());
        assertEquals("thinking",
                ((Map<?, ?>) event.data()).get("kind"));
    }

    @Test
    void rejectsEventWhenRuntimeLeaseIsStale() {
        when(mapper.findLastEventSeq("thread-1")).thenReturn(0L);
        when(mapper.insertEvent(
                anyString(), any(), eq("thread-1"), any(), any(), any(),
                eq(1L), anyLong(), eq("delta"), anyString(),
                eq("stale-token"), anyLong())).thenReturn(0);
        AiThread thread = new AiThread("thread-1", "test");
        repository.attach("thread-1", thread);
        thread.bindActiveLeaseToken("stale-token");

        assertThrows(IllegalStateException.class,
                () -> thread.recordSseEvent("delta", "hello"));
    }

}
