package org.leo.core.entity;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

class AuditLogQueryTest {
    @ParameterizedTest
    @CsvSource({",,50,0", "0,-1,50,0", "-2147483648,0,50,0", "1,7,1,7",
            "1000,0,1000,0", "1001,9,1000,9", "2147483647,2147483647,1000,2147483647"})
    void retainsTheExistingPagingBounds(Integer limit, Integer offset, int expectedLimit, int expectedOffset) {
        AuditLogQuery page = AuditLogQuery.page(limit, offset);
        AuditLogQuery normalized = page.normalized();

        assertEquals(expectedLimit, normalized.getLimit());
        assertEquals(expectedOffset, normalized.getOffset());
        assertEquals(expectedLimit, page.getLimit());
        assertEquals(expectedOffset, page.getOffset());
    }

    @Test
    void normalizesAnIndependentCopyAndLeavesBlankFiltersAbsent() {
        AuditLogQuery source = AuditLogQuery.page(null, null);
        source.setUserId(" user ");
        source.setKeyword("  search  ");
        source.setStatus("\t");
        source.setStartTime(" 2026-01-01 00:00:00 ");

        AuditLogQuery normalized = source.normalized();

        assertNotSame(source, normalized);
        assertEquals("user", normalized.getUserId());
        assertEquals("search", normalized.getKeyword());
        assertEquals("2026-01-01 00:00:00", normalized.getStartTime());
        assertNull(normalized.getStatus());
        assertNull(normalized.getRemark());
        normalized.setUserId("changed");
        assertEquals(" user ", source.getUserId());
        assertEquals("\t", source.getStatus());
    }
}
