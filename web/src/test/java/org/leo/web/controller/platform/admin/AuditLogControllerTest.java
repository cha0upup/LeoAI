package org.leo.web.controller.platform.admin;

import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.mapping.Environment;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
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
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.management.ManagementFactory;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
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
    void summaryPageProjectsListFieldsAndAggregatesTheSameFilteredRows(@TempDir Path temp) throws Exception {
        var source = SqliteTestDatabase.create(temp.resolve("audit-summary.db"));
        new JdbcTemplate(source).update("""
                INSERT INTO audit_logs (log_id, user_id, user_name, agent_id, agent_name, operation_type,
                                        operation_name, operation_path, status, client_ip, session_id,
                                        request_params, response_message, create_time)
                VALUES ('a', 'user-a', 'Alice', 'node', 'Node', 'TEST', 'Test', 'path', 'SUCCESS',
                        '192.0.2.1', 'session-a', 'fixture-marker', 'fixture-message', '2026-10-01 00:00:00'),
                       ('b', 'user-a', NULL, NULL, NULL, 'TEST', 'Test', NULL, 'FAILED',
                        NULL, NULL, NULL, 'fixture-marker', '2026-10-01 00:00:00'),
                       ('c', 'user-a', NULL, NULL, NULL, 'TEST', 'Test', NULL, 'ERROR',
                        NULL, NULL, 'fixture-marker', NULL, '2026-10-01 00:00:00'),
                       ('d', 'user-a', NULL, NULL, NULL, 'TEST', 'Test', NULL, 'PENDING',
                        NULL, NULL, 'fixture-marker', NULL, '2026-10-01 00:00:00'),
                       ('e', 'user-b', NULL, NULL, NULL, 'TEST', 'Test', NULL, 'SUCCESS',
                        NULL, NULL, 'fixture-marker', NULL, '2026-10-01 00:00:00')
                """);
        var configuration = SqliteTestDatabase.myBatisConfiguration(source);
        configuration.addMapper(AuditLogMapper.class);
        try (var session = new SqlSessionFactoryBuilder().build(configuration).openSession(true)) {
            var mapper = mock(AuditLogMapper.class, delegatesTo(session.getMapper(AuditLogMapper.class)));
            var storedMvc = MockMvcBuilders.standaloneSetup(new AuditLogController(
                    new AuditLogService(mapper), mock(AuditPolicyService.class), permissions)).build();
            String path = "/platform/admin/audit-logs";

            storedMvc.perform(get(path).param("view", "summary").param("limit", "1").param("offset", "3")
                            .param("userId", " user-a ").param("keyword", "fixture-marker"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.total").value(4))
                    .andExpect(jsonPath("$.data.successCount").value(1))
                    .andExpect(jsonPath("$.data.failedCount").value(2))
                    .andExpect(jsonPath("$.data.limit").value(1))
                    .andExpect(jsonPath("$.data.offset").value(3))
                    .andExpect(jsonPath("$.data.logs.length()").value(1))
                    .andExpect(jsonPath("$.data.logs[0].logId").value("a"))
                    .andExpect(jsonPath("$.data.logs[0].userName").value("Alice"))
                    .andExpect(jsonPath("$.data.logs[0].agentName").value("Node"))
                    .andExpect(jsonPath("$.data.logs[0].clientIp").value("192.0.2.1"))
                    .andExpect(jsonPath("$.data.logs[0].sessionId").doesNotHaveJsonPath())
                    .andExpect(jsonPath("$.data.logs[0].requestParams").doesNotHaveJsonPath())
                    .andExpect(jsonPath("$.data.logs[0].responseMessage").doesNotHaveJsonPath());
            verify(mapper).findAuditLogListItems(any());
            verify(mapper).countAuditLogsByStatus(any());
            verifyNoMoreInteractions(mapper);
            verify(permissions).requireAdmin(any(User.class));

            storedMvc.perform(get(path).param("view", "summary").param("status", "ERROR").param("offset", "20"))
                    .andExpect(jsonPath("$.data.logs").isEmpty())
                    .andExpect(jsonPath("$.data.total").value(1))
                    .andExpect(jsonPath("$.data.successCount").value(0))
                    .andExpect(jsonPath("$.data.failedCount").value(1));
            storedMvc.perform(get(path).param("view", "summary").param("userId", "missing"))
                    .andExpect(jsonPath("$.data.logs").isEmpty())
                    .andExpect(jsonPath("$.data.total").value(0))
                    .andExpect(jsonPath("$.data.successCount").value(0))
                    .andExpect(jsonPath("$.data.failedCount").value(0));

            storedMvc.perform(get(path).param("sessionId", "session-a"))
                    .andExpect(jsonPath("$.data.logs[0].requestParams").value("fixture-marker"))
                    .andExpect(jsonPath("$.data.successCount").doesNotHaveJsonPath());
            storedMvc.perform(get(path + "/detail").param("logId", "a"))
                    .andExpect(jsonPath("$.data.responseMessage").value("fixture-message"));
            storedMvc.perform(get(path + "/count").param("userId", "user-a"))
                    .andExpect(jsonPath("$.data.count").value(4));
        }
    }

    @Test
    void cleanupDeletesOnlyRowsBeforeTheSuppliedLocalTimestamp(@TempDir Path temp) throws Exception {
        var source = SqliteTestDatabase.create(temp.resolve("audit-retention.db"));
        var jdbc = new JdbcTemplate(source);
        jdbc.update("""
                INSERT INTO audit_logs (log_id, operation_type, operation_name, create_time)
                VALUES ('old', 'TEST', 'Test', '2026-10-01 11:59:59'),
                       ('boundary', 'TEST', 'Test', '2026-10-01 12:00:00'),
                       ('new', 'TEST', 'Test', '2026-10-01 12:00:01')
                """);
        var configuration = SqliteTestDatabase.myBatisConfiguration(source);
        configuration.addMapper(AuditLogMapper.class);
        try (var session = new SqlSessionFactoryBuilder().build(configuration).openSession(true)) {
            assertEquals(1, session.getMapper(AuditLogMapper.class).deleteOldAuditLogs("2026-10-01 12:00:00"));
        }
        assertEquals(List.of("boundary", "new"),
                jdbc.queryForList("SELECT log_id FROM audit_logs ORDER BY create_time", String.class));
    }

    @Test
    void auditReadFailuresDoNotExposeStorageDetails() throws Exception {
        when(logs.getSummaryPage(any())).thenThrow(new IllegalStateException("internal storage details"));
        MockMvc errorMvc = MockMvcBuilders.standaloneSetup(
                        new AuditLogController(logs, mock(AuditPolicyService.class), permissions))
                .setControllerAdvice(new ApiResponseStatusAdvice()).build();
        errorMvc.perform(get("/platform/admin/audit-logs").param("view", "summary"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.msg").value("获取审计日志列表失败"));
    }

    @Test
    void summaryRowsAndCountsShareOneSnapshotDuringConcurrentInsert(@TempDir Path temp) {
        var source = SqliteTestDatabase.create(temp.resolve("audit-snapshot.db"));
        var jdbc = new JdbcTemplate(source);
        assertEquals("wal", jdbc.queryForObject("PRAGMA journal_mode=WAL", String.class));
        String insert = """
                INSERT INTO audit_logs (log_id, operation_type, operation_name, create_time)
                VALUES (?, 'TEST', 'Test', '2026-10-01 00:00:00')
                """;
        jdbc.update(insert, "first");
        var configuration = new org.apache.ibatis.session.Configuration(
                new Environment("audit-snapshot", new SpringManagedTransactionFactory(), source));
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(AuditLogMapper.class);
        var template = new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(configuration));
        var delegate = template.getMapper(AuditLogMapper.class);
        var mapper = mock(AuditLogMapper.class, delegatesTo(delegate));
        doAnswer(call -> {
            var rows = delegate.findAuditLogListItems(call.getArgument(0));
            // 绕开当前 Spring 事务，以另一个连接在两次读取之间提交。
            try (var connection = source.getConnection(); var statement = connection.prepareStatement(insert)) {
                statement.setString(1, "second");
                assertEquals(1, statement.executeUpdate());
            }
            return rows;
        }).when(mapper).findAuditLogListItems(any());

        try (var context = new AnnotationConfigApplicationContext()) {
            context.register(AuditReadTransactions.class);
            context.registerBean(PlatformTransactionManager.class, () -> new DataSourceTransactionManager(source));
            context.registerBean(AuditLogMapper.class, () -> mapper);
            context.registerBean(AuditLogService.class);
            context.refresh();

            var page = context.getBean(AuditLogService.class).getSummaryPage(AuditLogQuery.page(20, 0));
            assertEquals(List.of("first"), page.logs().stream().map(row -> row.logId()).toList());
            assertEquals(1, page.total());
            assertEquals(1, page.successCount());
        }
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM audit_logs", Integer.class));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    static class AuditReadTransactions {
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
    void concurrentAuditCacheMissesShareOneConfigurationRead() throws Exception {
        var config = mock(SystemConfigService.class);
        var policy = new AuditPolicyService(config);
        var readStarted = new CountDownLatch(1);
        var releaseRead = new CountDownLatch(1);
        when(config.getString(AuditPolicyService.CONFIG_KEY_AUDIT_MODE, AuditPolicyService.MODE_ON)).thenAnswer(call -> {
            if (readStarted.getCount() > 0) {
                readStarted.countDown();
                assertTrue(releaseRead.await(5, TimeUnit.SECONDS));
            }
            return AuditPolicyService.MODE_ON;
        });

        var firstRead = new FutureTask<>(policy::getMode);
        var secondRead = new FutureTask<>(policy::getMode);
        var loader = new Thread(firstRead, "audit-cache-loader-test");
        var follower = new Thread(secondRead, "audit-cache-follower-test");
        loader.start();
        try {
            assertTrue(readStarted.await(5, TimeUnit.SECONDS));
            follower.start();
            var threads = ManagementFactory.getThreadMXBean();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            // Release the load only after the follower has either waited for it or read the store independently.
            while (!secondRead.isDone() && System.nanoTime() - deadline < 0) {
                var info = threads.getThreadInfo(follower.getId());
                if (info != null && info.getLockOwnerId() == loader.getId()) break;
                Thread.sleep(1);
            }
            releaseRead.countDown();

            assertEquals(AuditPolicyService.MODE_ON, firstRead.get(5, TimeUnit.SECONDS));
            assertEquals(AuditPolicyService.MODE_ON, secondRead.get(5, TimeUnit.SECONDS));
            verify(config).getString(AuditPolicyService.CONFIG_KEY_AUDIT_MODE, AuditPolicyService.MODE_ON);
        } finally {
            releaseRead.countDown();
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
