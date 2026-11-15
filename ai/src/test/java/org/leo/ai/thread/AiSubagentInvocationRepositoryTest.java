package org.leo.ai.thread;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.leo.core.entity.AiSubagentInvocation;
import org.leo.dao.mapper.AiConversationMapper;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class AiSubagentInvocationRepositoryTest {
    private final AiConversationMapper mapper = mock(AiConversationMapper.class);
    private final AiSubagentInvocationRepository repository = new AiSubagentInvocationRepository(mapper);

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void insertFillsMissingDefaultsAndPreservesExplicitCreationMetadata(boolean explicit) {
        AiSubagentInvocation row = new AiSubagentInvocation();
        row.setInvocationId("invocation-1");
        if (explicit) {
            row.setStatus(AiSubagentInvocation.STATUS_RUNNING);
            row.setCreatedAt(42L);
        }

        repository.insert(row);

        assertEquals(explicit ? AiSubagentInvocation.STATUS_RUNNING : AiSubagentInvocation.STATUS_PENDING,
                row.getStatus());
        if (explicit) assertEquals(42L, row.getCreatedAt());
        else assertNotNull(row.getCreatedAt());
        verify(mapper).insertSubagentInvocation(row);
    }

    @ParameterizedTest
    @ValueSource(strings = {"pending", "running", "waiting_for_user"})
    void openInvocationsClearCompletionTime(String status) {
        AiSubagentInvocation row = new AiSubagentInvocation();
        row.setInvocationId("invocation-1");
        row.setStatus(status);
        row.setCompletedAt(42L);

        repository.update(row);

        assertNull(row.getCompletedAt());
        verify(mapper).updateSubagentInvocation(row);
    }

    @ParameterizedTest
    @ValueSource(strings = {"completed", "failed", "cancelled"})
    void terminalInvocationsFillCompletionTimeOnce(String status) {
        AiSubagentInvocation row = new AiSubagentInvocation();
        row.setInvocationId("invocation-1");
        row.setStatus(status);

        repository.update(row);

        assertNotNull(row.getCompletedAt());
        row.setCompletedAt(42L);
        repository.update(row);

        assertEquals(42L, row.getCompletedAt());
        verify(mapper, times(2)).updateSubagentInvocation(row);
    }
}
