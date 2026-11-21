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
import org.leo.dao.mapper.SystemConfigMapper;
import org.leo.service.audit.AuditLogService;
import org.leo.service.audit.AuditPolicyService;
import org.leo.service.config.SystemConfigService;
import org.leo.web.config.ApiResponseStatusAdvice;
import org.leo.web.security.PermissionService;
import org.leo.web.testing.SqliteTestDatabase;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
        var request = get("/platform/admin/audit-logs").param("userId", " user ").param("status", "\t");
        if (limit != null) request.param("limit", limit);
        if (offset != null) request.param("offset", offset);

        mvc.perform(request).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.logs[0].logId").value("log"))
                .andExpect(jsonPath("$.data.total").value(expectedOffset + 1))
                .andExpect(jsonPath("$.data.limit").value(expectedLimit))
                .andExpect(jsonPath("$.data.offset").value(expectedOffset));

        var query = ArgumentCaptor.forClass(AuditLogQuery.class);
        verify(logs).searchAuditLogs(query.capture());
        assertEquals("user", query.getValue().getUserId());
        assertNull(query.getValue().getStatus());
        assertEquals(expectedLimit, query.getValue().getLimit());
        assertEquals(expectedOffset, query.getValue().getOffset());
        verify(logs, never()).countAuditLogs(any());
    }

    @ParameterizedTest
    @CsvSource({"10,0,userId,user-a,3,3,0,log-3", "10,0,userId,missing,0,0,0,", "2,2,userId,user-a,1,3,0,log-1",
            "2,0,userId,user-a,2,3,1,log-3", "2,8,userId,user-a,0,3,1,", "2,0,,,2,4,1,other",
            "2,0,sessionId,session-a,2,2,1,log-3"})
    void auditPagesOnlyCountWhenTheRowsDoNotDetermineTheTotal(int limit, int offset, String filterName,
                                                               String filterValue, int rowCount, int total, int countQueries,
                                                               String firstLogId, @TempDir Path temp) throws Exception {
        var source = SqliteTestDatabase.create(temp.resolve("audit.db"));
        new JdbcTemplate(source).update("""
                INSERT INTO audit_logs (log_id, user_id, session_id, operation_type, operation_name, create_time)
                VALUES ('log-1', 'user-a', 'session-a', 'TEST', 'Test', '2026-10-01 00:00:00'),
                       ('log-2', 'user-a', 'session-b', 'TEST', 'Test', '2026-10-01 00:00:00'),
                       ('log-3', 'user-a', 'session-a', 'TEST', 'Test', '2026-10-01 00:00:00'),
                       ('other', 'user-b', NULL, 'TEST', 'Test', '2026-10-01 00:00:00')
                """);
        var configuration = SqliteTestDatabase.myBatisConfiguration(source);
        configuration.addMapper(AuditLogMapper.class);
        try (var session = new SqlSessionFactoryBuilder().build(configuration).openSession(true)) {
            var mapper = mock(AuditLogMapper.class, delegatesTo(session.getMapper(AuditLogMapper.class)));
            MockMvc storedMvc = MockMvcBuilders.standaloneSetup(new AuditLogController(
                    new AuditLogService(mapper), mock(AuditPolicyService.class), permissions)).build();

            var request = get("/platform/admin/audit-logs")
                    .param("limit", Integer.toString(limit)).param("offset", Integer.toString(offset));
            if (filterName != null) request.param(filterName, filterValue);
            var response = storedMvc.perform(request)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.logs.length()").value(rowCount))
                    .andExpect(jsonPath("$.data.total").value(total));
            if (firstLogId != null) response.andExpect(jsonPath("$.data.logs[0].logId").value(firstLogId));
            verify(mapper).searchAuditLogs(any());
            verify(mapper, times(countQueries)).countAuditLogs(any());
        }
    }

    @Test
    void failedAuditConfigurationWriteKeepsThePersistedAndCachedMode(@TempDir Path temp) throws Exception {
        var source = SqliteTestDatabase.create(temp.resolve("audit-config.db"));
        var configuration = SqliteTestDatabase.myBatisConfiguration(source);
        configuration.addMapper(SystemConfigMapper.class);
        try (var session = new SqlSessionFactoryBuilder().build(configuration).openSession(true)) {
            var config = new SystemConfigService(session.getMapper(SystemConfigMapper.class));
            config.setString(AuditPolicyService.CONFIG_KEY_AUDIT_MODE, AuditPolicyService.MODE_ON, "fixture");
            config.setString(AuditPolicyService.CONFIG_KEY_AUDIT_MODE, AuditPolicyService.MODE_WRITE, "fixture");
            new JdbcTemplate(source).execute("""
                    CREATE TRIGGER skip_fixture_config BEFORE UPDATE ON system_configs
                    BEGIN SELECT RAISE(IGNORE); END
                    """);
            var policy = new AuditPolicyService(config);
            MockMvc storedMvc = MockMvcBuilders.standaloneSetup(new AuditLogController(logs, policy, permissions))
                    .setControllerAdvice(new ApiResponseStatusAdvice()).build();

            storedMvc.perform(post("/platform/admin/audit-logs/mode")
                            .contentType(MediaType.APPLICATION_JSON).content("{\"mode\":\"on\"}"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.code").value(500));

            assertEquals(AuditPolicyService.MODE_WRITE, config.getString(AuditPolicyService.CONFIG_KEY_AUDIT_MODE, null));
            assertEquals(AuditPolicyService.MODE_WRITE, policy.getMode());
        }
    }

    @Test
    void olderAuditConfigurationReadDoesNotOverwriteACompletedUpdate() throws Exception {
        var config = mock(SystemConfigService.class);
        var policy = new AuditPolicyService(config);
        var readingOldMode = new CountDownLatch(1);
        var releaseRead = new CountDownLatch(1);
        when(config.getString(AuditPolicyService.CONFIG_KEY_AUDIT_MODE, AuditPolicyService.MODE_ON)).thenAnswer(call -> {
            readingOldMode.countDown();
            assertTrue(releaseRead.await(5, TimeUnit.SECONDS));
            return AuditPolicyService.MODE_WRITE;
        });

        var executor = Executors.newSingleThreadExecutor();
        try {
            var pendingRead = executor.submit(policy::getMode);
            assertTrue(readingOldMode.await(5, TimeUnit.SECONDS));
            assertEquals(AuditPolicyService.MODE_ON, policy.updateMode(AuditPolicyService.MODE_ON));
            releaseRead.countDown();

            assertEquals(AuditPolicyService.MODE_ON, pendingRead.get(5, TimeUnit.SECONDS));
            assertEquals(AuditPolicyService.MODE_ON, policy.getMode());
        } finally {
            releaseRead.countDown();
            executor.shutdownNow();
        }
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
            var mapper = mock(AuditLogMapper.class, delegatesTo(session.getMapper(AuditLogMapper.class)));
            var stored = new AuditLogService(mapper);
            String older = "2000-01-02 03:04:05";
            String now = jdbc.queryForObject("SELECT datetime('now')", String.class);
            insertAudit(stored, "member", older);
            jdbc.update("UPDATE users SET team_id='team-b' WHERE user_id='member'");
            insertAudit(stored, "member", older);
            insertAudit(stored, "member", now);
            jdbc.update("DELETE FROM users WHERE user_id='member'");
            insertAudit(stored, "member", now);
            transaction.executeWithoutResult(status -> {
                jdbc.update("UPDATE users SET team_id=NULL WHERE user_id='lead-a'");
                jdbc.update("DELETE FROM teams WHERE team_id='team-a'");
            });

            MockMvc storedMvc = MockMvcBuilders.standaloneSetup(
                    new AuditLogController(stored, mock(AuditPolicyService.class), permissions)).build();
            clearInvocations(mapper);
            storedMvc.perform(get("/platform/admin/audit-logs/statistics/team").param("teamId", "team-a"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.totalOperations").value(1))
                    .andExpect(jsonPath("$.data.recentOperations").value(0))
                    .andExpect(jsonPath("$.data.lastOperation").value(older));
            assertEquals(1, mockingDetails(mapper).getInvocations().size());
            storedMvc.perform(get("/platform/admin/audit-logs/statistics/team").param("teamId", "team-b"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.totalOperations").value(2))
                    .andExpect(jsonPath("$.data.recentOperations").value(1))
                    .andExpect(jsonPath("$.data.lastOperation").value(now));
            storedMvc.perform(get("/platform/admin/audit-logs/statistics/team").param("teamId", "team-empty"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.totalOperations").value(0))
                    .andExpect(jsonPath("$.data.recentOperations").value(0))
                    .andExpect(jsonPath("$.data.lastOperation").hasJsonPath())
                    .andExpect(jsonPath("$.data.lastOperation").isEmpty());
            assertEquals(1, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM audit_logs WHERE actor_team_id IS NULL", Integer.class));
        }
    }

    @ParameterizedTest
    @CsvSource({"user, userId, user-a", "agent, agentId, agent-a", "team, teamId, team-a"})
    void subjectStatisticsUseTheApplicationClockAndKeepOneQuery(String subject, String idField, String id,
                                                                @TempDir Path temp) throws Exception {
        var source = SqliteTestDatabase.create(temp.resolve("audit.db"));
        var jdbc = new JdbcTemplate(source);
        var format = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        var utcNow = LocalDateTime.parse(jdbc.queryForObject("SELECT CURRENT_TIMESTAMP", String.class), format);
        var applicationNow = utcNow.plusHours(8);
        var cutoff = applicationNow.minusDays(7);
        String latest = cutoff.format(format);
        jdbc.update("""
                INSERT INTO audit_logs (log_id, user_id, agent_id, actor_team_id, operation_type, operation_name, create_time)
                VALUES ('old', 'user-a', 'agent-a', 'team-a', 'TEST', 'Test', ?),
                       ('recent', 'user-a', 'agent-a', 'team-a', 'TEST', 'Test', ?),
                       ('other', 'user-b', 'agent-b', 'team-b', 'TEST', 'Test', ?)
                """, cutoff.minusSeconds(1).format(format), latest, applicationNow.format(format));
        var configuration = SqliteTestDatabase.myBatisConfiguration(source);
        configuration.addMapper(AuditLogMapper.class);
        try (var session = new SqlSessionFactoryBuilder().build(configuration).openSession(true);
             var clock = mockStatic(LocalDateTime.class, CALLS_REAL_METHODS)) {
            clock.when(LocalDateTime::now).thenReturn(applicationNow);
            var mapper = mock(AuditLogMapper.class, delegatesTo(session.getMapper(AuditLogMapper.class)));
            MockMvc storedMvc = MockMvcBuilders.standaloneSetup(new AuditLogController(
                    new AuditLogService(mapper), mock(AuditPolicyService.class), permissions)).build();
            String path = "/platform/admin/audit-logs/statistics/" + subject;

            var populated = storedMvc.perform(get(path).param(idField, " " + id + " "))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data." + idField).value(id))
                    .andExpect(jsonPath("$.data.totalOperations").value(2))
                    .andExpect(jsonPath("$.data.recentOperations").value(1))
                    .andExpect(jsonPath("$.data.lastOperation").value(latest));
            if ("agent".equals(subject)) populated.andExpect(jsonPath("$.data.hostId").value(id));
            assertEquals(1, mockingDetails(mapper).getInvocations().size());
            storedMvc.perform(get(path).param(idField, "missing"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.totalOperations").value(0))
                    .andExpect(jsonPath("$.data.recentOperations").value(0))
                    .andExpect(jsonPath("$.data.lastOperation").hasJsonPath())
                    .andExpect(jsonPath("$.data.lastOperation").isEmpty());
            assertEquals(2, mockingDetails(mapper).getInvocations().size());
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
