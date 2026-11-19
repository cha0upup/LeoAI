package org.leo.service.audit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.leo.core.entity.AuditLog;
import org.leo.core.entity.AuditLogQuery;
import org.leo.dao.mapper.AuditLogMapper;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

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

    @ParameterizedTest
    @ValueSource(ints = {1, 3})
    void trendQueriesWholeCalendarDaysAndFillsMissingCounts(int days) {
        when(mapper.countAuditLogsByDay(anyString(), anyString())).thenAnswer(call -> {
            String startTime = call.getArgument(0);
            return List.of(Map.of("day", startTime.substring(0, 10), "count", 2L));
        });

        var trend = logs.getTrendStatistics(days);

        assertEquals(days, trend.size());
        LocalDate start = LocalDate.parse((String) trend.get(0).get("day"));
        verify(mapper).countAuditLogsByDay(start + " 00:00:00", start.plusDays(days) + " 00:00:00");
        for (int i = 0; i < days; i++) {
            assertEquals(start.plusDays(i).toString(), trend.get(i).get("day"));
            assertEquals(i == 0 ? 2 : 0, trend.get(i).get("count"));
        }
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
