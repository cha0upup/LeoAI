package org.leo.ai.thread;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.leo.dao.mapper.AiConversationMapper;
import org.leo.dao.mapper.AiConversationMapper.HistoryRow;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class AiMessageRepositoryTest {
    private final AiConversationMapper mapper = mock(AiConversationMapper.class);
    private final AiMessageRepository messages = new AiMessageRepository(mapper);

    @ParameterizedTest
    @CsvSource({"7,20,7,20,20", "-8,0,0,1,1", "0,201,0,200,200", "0,-1,0,200,1"})
    void boundsHistoryPagesConsistentlyWithoutChangingModelContextLimits(
            int offset, int limit, int expectedOffset, int historyLimit, int contextLimit) {
        messages.historyPage("thread", offset, limit, null);
        messages.historyPage("thread", offset, limit, 10L);
        messages.contextMessages("thread", limit);

        verify(mapper).listMessageHistory("thread", null, expectedOffset, historyLimit);
        verify(mapper).listMessageHistory("thread", 10L, 0, historyLimit);
        verify(mapper).recentContextMessages("thread", contextLimit);
        verifyNoMoreInteractions(mapper);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void readsThePageAndOlderMessageFlagInOneMapperCall(boolean hasMore) {
        var row = new HistoryRow();
        row.setMessageSeq(12L);
        row.setMoreAvailable(hasMore);
        when(mapper.listMessageHistory("thread", null, 0, 1)).thenReturn(List.of(row));

        var page = messages.historyPage("thread", 0, 1, null);

        assertEquals(12L, page.nextBeforeSequence());
        assertEquals(hasMore, page.hasMore());
        assertEquals(12L, page.messages().get(0).get("sequence"));
        verify(mapper).listMessageHistory("thread", null, 0, 1);
        verifyNoMoreInteractions(mapper);
    }
}
