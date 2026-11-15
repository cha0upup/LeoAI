package org.leo.service.audit;

import org.junit.jupiter.api.Test;
import org.leo.core.entity.AuditLog;
import org.leo.core.entity.AuditLogQuery;
import org.leo.dao.mapper.AuditLogMapper;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AuditLogServiceTest {
    private final AuditLogMapper mapper = mock(AuditLogMapper.class);
    private final AuditLogService logs = new AuditLogService(mapper);

    @Test
    void readingPagesAndCountsDoesNotRewriteTheCallersQuery() {
        AuditLogQuery query = new AuditLogQuery();
        query.setUserId(" user ");
        query.setKeyword(" search ");
        query.setStatus(" ");
        query.setLimit(2001);
        query.setOffset(-5);
        var row = new AuditLog();
        when(mapper.searchAuditLogs(any())).thenReturn(List.of(row));
        when(mapper.countAuditLogs(any())).thenReturn(3);

        assertEquals(List.of(row), logs.searchAuditLogs(query));
        assertEquals(3, logs.countAuditLogs(query));

        assertEquals(" user ", query.getUserId());
        assertEquals(" search ", query.getKeyword());
        assertEquals(" ", query.getStatus());
        assertEquals(2001, query.getLimit());
        assertEquals(-5, query.getOffset());
        var normalized = ArgumentCaptor.forClass(AuditLogQuery.class);
        verify(mapper).searchAuditLogs(normalized.capture());
        assertNotSame(query, normalized.getValue());
        assertEquals("user", normalized.getValue().getUserId());
        assertEquals("search", normalized.getValue().getKeyword());
        assertNull(normalized.getValue().getStatus());
        assertEquals(1000, normalized.getValue().getLimit());
        assertEquals(0, normalized.getValue().getOffset());
    }

    @Test
    void plainListingUsesTheSamePaginationBounds() {
        when(mapper.findAllAuditLogs(1000, 0)).thenReturn(List.of(new AuditLog()));

        assertEquals(1, logs.findAllAuditLogs(2001, -5).size());

        verify(mapper).findAllAuditLogs(1000, 0);
    }

    @Test
    void blankFiltersRemainIneligibleForFilteredDeletion() {
        AuditLogQuery query = new AuditLogQuery();
        query.setKeyword(" ");

        assertThrows(IllegalArgumentException.class, () -> logs.deleteAuditLogsByFilter(query));

        assertEquals(" ", query.getKeyword());
        verifyNoInteractions(mapper);
    }

    @Test
    void missingQueriesAndRowsHaveAnEmptyDefaultPage() {
        when(mapper.searchAuditLogs(any())).thenReturn(null);
        when(mapper.countAuditLogs(any())).thenReturn(null);

        assertTrue(logs.searchAuditLogs(null).isEmpty());
        assertEquals(0, logs.countAuditLogs(null));

        var query = ArgumentCaptor.forClass(AuditLogQuery.class);
        verify(mapper).searchAuditLogs(query.capture());
        assertEquals(50, query.getValue().getLimit());
        assertEquals(0, query.getValue().getOffset());
    }
}
