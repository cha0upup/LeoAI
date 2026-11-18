package org.leo.web.controller.platform.admin;

import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.leo.core.entity.AuditLog;
import org.leo.core.entity.AuditLogQuery;
import org.leo.core.entity.User;
import org.leo.dao.mapper.AuditLogMapper;
import org.leo.service.audit.AuditLogService;
import org.leo.service.audit.AuditPolicyService;
import org.leo.web.security.PermissionService;
import org.leo.web.testing.SqliteTestDatabase;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Path;
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

    @Test
    void teamStatisticsKeepTheOriginalAttributionAfterMembershipAndAccountChanges(@TempDir Path temp) throws Exception {
        var source = SqliteTestDatabase.create(temp.resolve("audit.db"));
        var jdbc = new JdbcTemplate(source);
        var transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
        transaction.executeWithoutResult(status -> {
            jdbc.update("""
                    INSERT INTO users (user_id, user_name, password, team_id, create_time, update_time)
                    VALUES ('lead-a', 'lead-a', 'fixture', 'team-a', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
                           ('lead-b', 'lead-b', 'fixture', 'team-b', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
                           ('member', 'member', 'fixture', 'team-a', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                    """);
            jdbc.update("""
                    INSERT INTO teams (team_id, team_name, leader_id, create_time, update_time)
                    VALUES ('team-a', 'A', 'lead-a', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
                           ('team-b', 'B', 'lead-b', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                    """);
        });
        var configuration = SqliteTestDatabase.myBatisConfiguration(source);
        configuration.addMapper(AuditLogMapper.class);
        try (var session = new SqlSessionFactoryBuilder().build(configuration).openSession(true)) {
            var stored = new AuditLogService(session.getMapper(AuditLogMapper.class));
            String older = "2000-01-02 03:04:05";
            String now = jdbc.queryForObject("SELECT datetime('now')", String.class);
            insertAudit(stored, "member", older);
            jdbc.update("UPDATE users SET team_id='team-b' WHERE user_id='member'");
            insertAudit(stored, "member", now);
            jdbc.update("DELETE FROM users WHERE user_id='member'");
            insertAudit(stored, "member", now);
            transaction.executeWithoutResult(status -> {
                jdbc.update("UPDATE users SET team_id=NULL WHERE user_id='lead-a'");
                jdbc.update("DELETE FROM teams WHERE team_id='team-a'");
            });

            MockMvc storedMvc = MockMvcBuilders.standaloneSetup(
                    new AuditLogController(stored, mock(AuditPolicyService.class), permissions)).build();
            storedMvc.perform(get("/platform/admin/audit-logs/statistics/team").param("teamId", "team-a"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.totalOperations").value(1))
                    .andExpect(jsonPath("$.data.recentOperations").value(0))
                    .andExpect(jsonPath("$.data.lastOperation").value(older));
            storedMvc.perform(get("/platform/admin/audit-logs/statistics/team").param("teamId", "team-b"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.totalOperations").value(1))
                    .andExpect(jsonPath("$.data.recentOperations").value(1))
                    .andExpect(jsonPath("$.data.lastOperation").value(now));
            assertEquals(1, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM audit_logs WHERE actor_team_id IS NULL", Integer.class));
        }
    }

    private static void insertAudit(AuditLogService service, String userId, String timestamp) {
        AuditLog row = new AuditLog();
        row.setUserId(userId);
        row.setOperationType("TEST");
        row.setOperationName("Test");
        row.setCreateTime(timestamp);
        assertTrue(service.insertAuditLog(row));
    }
}
