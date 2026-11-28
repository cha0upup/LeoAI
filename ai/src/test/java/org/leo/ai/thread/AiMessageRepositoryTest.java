package org.leo.ai.thread;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.leo.dao.mapper.AiConversationMapper;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

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

        verify(mapper).listMessages("thread", expectedOffset, historyLimit);
        verify(mapper).listMessagesBefore("thread", 10L, historyLimit);
        verify(mapper).recentContextMessages("thread", contextLimit);
    }
}
