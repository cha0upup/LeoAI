package org.leo.ai.thread;

import org.junit.jupiter.api.Test;
import org.leo.core.entity.AiSubagentInvocation;
import org.leo.dao.mapper.AiConversationMapper;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class AiSubagentInvocationRepositoryTest {
    @Test
    void waitingRemainsOpenAndCancellationReceivesCompletionTime() {
        AiConversationMapper mapper = mock(AiConversationMapper.class);
        AiSubagentInvocationRepository repository = new AiSubagentInvocationRepository(mapper);
        AiSubagentInvocation row = new AiSubagentInvocation();
        row.setInvocationId("invocation-1");
        row.setStatus(AiSubagentInvocation.STATUS_WAITING_FOR_USER);

        repository.update(row);

        assertNull(row.getCompletedAt());
        verify(mapper).updateSubagentInvocation(row);
        row.setStatus(AiSubagentInvocation.STATUS_CANCELLED);
        repository.update(row);
        assertNotNull(row.getCompletedAt());
    }
}
