package org.leo.web.controller.platform.admin;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.leo.core.entity.AuditLog;
import org.leo.core.entity.AuditLogQuery;
import org.leo.core.entity.User;
import org.leo.service.audit.AuditLogService;
import org.leo.service.audit.AuditPolicyService;
import org.leo.web.security.PermissionService;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AuditLogControllerTest {
    private final AuditLogService logs = mock(AuditLogService.class);
    private final PermissionService permissions = mock(PermissionService.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        when(permissions.requireLogin(any())).thenReturn(new User());
        mvc = MockMvcBuilders.standaloneSetup(new AuditLogController(logs, mock(AuditPolicyService.class), permissions)).build();
    }

    @ParameterizedTest
    @CsvSource({",,50,0", "0,-4,50,0", "25,9,25,9", "2001,9,1000,9",
            "invalid,invalid,50,0", "2147483648,2147483648,50,0"})
    void responsePagingMatchesTheNormalizedQueryWithoutRelyingOnServiceMutation(
            String limit, String offset, int expectedLimit, int expectedOffset) throws Exception {
        AuditLog row = new AuditLog();
        row.setLogId("log");
        when(logs.searchAuditLogs(any())).thenReturn(List.of(row));
        when(logs.countAuditLogs(any())).thenReturn(3);
        var request = get("/platform/admin/audit-logs").param("userId", " user ").param("status", "\t");
        if (limit != null) request.param("limit", limit);
        if (offset != null) request.param("offset", offset);

        mvc.perform(request).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.logs[0].logId").value("log"))
                .andExpect(jsonPath("$.data.total").value(3))
                .andExpect(jsonPath("$.data.limit").value(expectedLimit))
                .andExpect(jsonPath("$.data.offset").value(expectedOffset));

        var query = ArgumentCaptor.forClass(AuditLogQuery.class);
        verify(logs).searchAuditLogs(query.capture());
        assertEquals("user", query.getValue().getUserId());
        assertNull(query.getValue().getStatus());
        assertEquals(expectedLimit, query.getValue().getLimit());
        assertEquals(expectedOffset, query.getValue().getOffset());
        verify(logs).countAuditLogs(same(query.getValue()));
    }
}
