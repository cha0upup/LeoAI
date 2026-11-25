package org.leo.ai.thread;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.leo.core.session.AiThread;
import org.leo.dao.mapper.AiConversationMapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AiEventJournalRepositoryTest {
    private final AiConversationMapper mapper = mock(AiConversationMapper.class);
    private final AiEventJournalRepository repository = new AiEventJournalRepository(mapper);

    @ParameterizedTest
    @CsvSource({"-1, 0, 0, 200", "3, 1, 3, 1", "3, 5000, 3, 2000"})
    void replayBoundsAreAppliedAtThePersistenceBoundary(long cursor, int limit,
                                                        long expectedCursor, int expectedLimit) {
        assertTrue(repository.listAfter("thread-1", cursor, limit).isEmpty());

        verify(mapper).listEventsAfter("thread-1", expectedCursor, expectedLimit);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = " ")
    void blankThreadIdsDoNotReadTheJournal(String threadId) {
        assertTrue(repository.listAfter(threadId, 0, 200).isEmpty());
        assertEquals(0L, repository.lastSequence(threadId));
        assertEquals(0L, repository.latestTurnStartSequence(threadId));
        verifyNoInteractions(mapper);
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
