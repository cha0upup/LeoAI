package org.leo.web.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.leo.core.session.AgentNodeSession;
import org.leo.core.session.AgentNodeSessionContainer;
import org.sqlite.SQLiteDataSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NetworkProbeOwnershipTest {
    @TempDir Path temp;

    @AfterEach
    void clearSessions() {
        AgentNodeSessionContainer.clearAllSessions();
    }

    @Test
    void historyAndEvidenceSurviveSessionDestructionAndServerRestart() {
        SQLiteDataSource dataSource = database();
        NetworkProbeResultStore store = new NetworkProbeResultStore(dataSource, new ObjectMapper());
        session("first", "user", "agent", "host");
        assertTrue(store.createTask("task", "first", "scan", Map.of("targets", List.of(
                Map.of("host", "127.0.0.1", "port", 8080, "protocol", "tcp")))));
        assertTrue(store.append("task", List.of(Map.of("stage", "http-head", "workflowStage", "SERVICE_PROBE",
                "host", "127.0.0.1", "port", 8080, "protocol", "tcp", "state", "open", "service", "http",
                "evidence", Map.of("statusCode", 200))), List.of()));
        NetworkProbeWorkflowService workflow = mock(NetworkProbeWorkflowService.class);
        SessionLifecycleManager lifecycle = new SessionLifecycleManager(workflow);
        lifecycle.registerDestroyHook();
        try {
            assertTrue(lifecycle.destroy("first", false));
            verify(workflow).cleanupSession("first");
            assertEquals(1, new JdbcTemplate(dataSource).queryForObject("SELECT COUNT(*) FROM scan_tasks", Integer.class));
            assertTrue(store.summary("first", "task").isEmpty());

            session("reconnected", "user", "agent", "host");
            NetworkProbeResultStore restarted = new NetworkProbeResultStore(dataSource, new ObjectMapper());
            restarted.markInterruptedTasks();
            assertEquals("FAILED", restarted.summary("reconnected", "task").get("status"));
            assertEquals(1, ((List<?>) restarted.list("reconnected", Map.of()).get("tasks")).size());
            assertEquals(1L, restarted.queryResults("reconnected", "task", Map.of()).get("total"));
            assertFalse(restarted.summaryCounts("reconnected", "task").isEmpty());
            assertEquals(1, restarted.fingerprintSource("reconnected", "task", List.of("tcp|127.0.0.1|8080")).endpoints().size());
            assertTrue(restarted.deleteTask("reconnected", "task"));
            assertEquals(0, new JdbcTemplate(dataSource).queryForObject("SELECT COUNT(*) FROM scan_endpoint_results", Integer.class));
        } finally {
            lifecycle.unregisterDestroyHook();
        }
    }

    @ParameterizedTest
    @CsvSource({"other,agent,host", "user,other,host", "user,agent,other", "user,agent,"})
    void everyReadAndDeleteUsesDurableOwnership(String user, String agent, String host) {
        NetworkProbeResultStore store = new NetworkProbeResultStore(database(), new ObjectMapper());
        session("first", "user", "agent", "host");
        assertTrue(store.createTask("task", "first", "scan", Map.of()));
        // Even reusing the originating ID must not bypass non-null persisted ownership.
        AgentNodeSessionContainer.removeSession("first");
        session("first", user, agent, host);
        assertFalse(store.ownsTask("first", "task"));
        assertTrue(store.summary("first", "task").isEmpty());
        assertTrue(store.summaryCounts("first", "task").isEmpty());
        assertTrue(store.queryResults("first", "task", Map.of()).isEmpty());
        assertTrue(store.queryFingerprintMatches("first", "task", Map.of()).isEmpty());
        assertTrue(store.fingerprintEvidence("first", "task", "match").isEmpty());
        assertTrue(((List<?>) store.list("first", Map.of()).get("tasks")).isEmpty());
        assertThrows(IllegalArgumentException.class,
                () -> store.fingerprintSource("first", "task", List.of("endpoint")));
        assertFalse(store.deleteTask("first", "task"));
    }

    @Test
    void unknownLegacyOwnershipIsPreservedAndNeverClaimedByAReconnect() {
        SQLiteDataSource dataSource = database();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.update("INSERT INTO scan_tasks(task_id,session_id,name,created_at,updated_at) "
                + "VALUES ('old','original','legacy',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        NetworkProbeResultStore store = new NetworkProbeResultStore(dataSource, new ObjectMapper());
        session("original", "user", "agent", "host");
        assertFalse(store.ownsTask("original", "old"));
        AgentNodeSessionContainer.removeSession("original");
        session("new", "user", "agent", "host");
        assertFalse(store.ownsTask("new", "old"));
        assertFalse(store.deleteTask("new", "old"));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM scan_tasks WHERE host_id IS NULL", Integer.class));
    }

    @Test
    void creatingTasksRequiresACompleteScopeAndNeverOverwritesAnExistingId() {
        NetworkProbeResultStore store = new NetworkProbeResultStore(database(), new ObjectMapper());
        assertFalse(store.createTask("task", "missing", "scan", Map.of()));
        session("unbound", "user", "agent", null);
        assertFalse(store.createTask("task", "unbound", "scan", Map.of()));
        session("first", "user", "agent", "host");
        assertTrue(store.createTask("task", "first", "original", Map.of()));
        assertFalse(store.createTask("task", "first", "replacement", Map.of()));
        assertEquals("original", store.summary("first", "task").get("name"));
    }

    @Test
    void historyAndActivePagesUseOwnerScopedOrderedIndexes() {
        JdbcTemplate jdbc = new JdbcTemplate(database());
        for (boolean active : List.of(false, true)) {
            String sql = "EXPLAIN QUERY PLAN SELECT task_id FROM scan_tasks WHERE user_id=? AND agent_id=? AND host_id=?"
                    + (active ? " AND status IN ('RUNNING','PAUSED')" : "")
                    + " AND (created_at, task_id) < (?, ?) ORDER BY created_at DESC, task_id DESC LIMIT 21";
            String plan = String.join(" ", jdbc.query(sql, (row, index) -> row.getString("detail"),
                    "user", "agent", "host", "2026-10-28", "task"));
            assertTrue(plan.contains(active ? "idx_scan_tasks_owner_active_cursor" : "idx_scan_tasks_owner_cursor"), plan);
            assertFalse(plan.contains("TEMP B-TREE"), plan);
        }
    }

    private SQLiteDataSource database() {
        SQLiteDataSource source = new SQLiteDataSource();
        source.setUrl("jdbc:sqlite:" + temp.resolve("ownership.db"));
        source.setEnforceForeignKeys(true);
        new ResourceDatabasePopulator(new ClassPathResource("sql/schema.sql")).execute(source);
        return source;
    }

    private void session(String id, String user, String agent, String host) {
        AgentNodeSession session = new AgentNodeSession();
        session.setSessionId(id);
        session.setCreateByUser(user);
        session.setAgentId(agent);
        if (host != null) session.bindHostId(host);
        AgentNodeSessionContainer.addSession(id, session);
    }
}
