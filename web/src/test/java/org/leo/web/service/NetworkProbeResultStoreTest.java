package org.leo.web.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NetworkProbeResultStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void deletesTaskAndCascadedResultsWithoutTouchingAnotherSession() throws Exception {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + tempDir.resolve("network-results.db"));
        dataSource.setEnforceForeignKeys(true);
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("sql/schema.sql"));
        }

        NetworkProbeResultStore store = new NetworkProbeResultStore(dataSource, new ObjectMapper());
        assertTrue(store.createTask("task-delete", "session-a", "test", Map.of(
                "targetCount", 1,
                "hosts", List.of("10.0.0.10"))));
        assertTrue(store.createTask("task-keep", "session-b", "keep", Map.of("targetCount", 1)));

        Map<String, Object> reachability = new LinkedHashMap<>();
        reachability.put("workflowStage", "REACHABILITY");
        reachability.put("stage", "tcp-connect");
        reachability.put("host", "10.0.0.10");
        reachability.put("port", 0);
        reachability.put("state", "open");

        Map<String, Object> serviceProbe = new LinkedHashMap<>();
        serviceProbe.put("workflowStage", "SERVICE_PROBE");
        serviceProbe.put("stage", "tcp-exchange");
        serviceProbe.put("host", "10.0.0.10");
        serviceProbe.put("port", 8080);
        serviceProbe.put("protocol", "tcp");
        serviceProbe.put("state", "open");
        serviceProbe.put("evidence", Map.of("statusCode", 200, "responseSize", 1240, "server", "nginx"));

        assertTrue(store.append("task-delete", List.of(reachability, serviceProbe), List.of()));
        store.updateTask("task-delete", "STOPPED", "COMPLETED", "SERVICE_PROBE", 100, 1);
        Map<String, Object> taskSummary = store.list("session-a").get(0);
        assertEquals(1, taskSummary.get("reachableHostCount"));
        assertEquals(List.of("10.0.0.10"), taskSummary.get("reachableHostList"));
        assertEquals(1L, store.queryResults("session-a", "task-delete", Map.of()).get("total"));
        Map<String, Object> endpoint = ((List<Map<String, Object>>) store.queryResults("session-a", "task-delete", Map.of()).get("endpoints")).get(0);
        assertEquals(200, ((Number) endpoint.get("statusCode")).intValue());
        assertEquals(1240, ((Number) endpoint.get("responseSize")).intValue());

        assertFalse(store.deleteTask("session-b", "task-delete"));
        assertTrue(store.deleteTask("session-a", "task-delete"));
        assertTrue(store.list("session-a").isEmpty());
        assertTrue(store.queryResults("session-a", "task-delete", Map.of()).isEmpty());

        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            assertEquals(0, scalar(statement, "SELECT COUNT(*) FROM scan_tasks WHERE task_id='task-delete'"));
            assertEquals(0, scalar(statement, "SELECT COUNT(*) FROM scan_endpoint_results WHERE task_id='task-delete'"));
            assertEquals(0, scalar(statement, "SELECT COUNT(*) FROM scan_observations WHERE task_id='task-delete'"));
            assertEquals(0, scalar(statement, "SELECT COUNT(*) FROM scan_evidence WHERE task_id='task-delete'"));
            assertEquals(1, scalar(statement, "SELECT COUNT(*) FROM scan_tasks WHERE task_id='task-keep'"));
        }
    }

    @Test
    void deletesAllTasksOwnedBySession() throws Exception {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + tempDir.resolve("network-session-delete.db"));
        dataSource.setEnforceForeignKeys(true);
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("sql/schema.sql"));
        }

        NetworkProbeResultStore store = new NetworkProbeResultStore(dataSource, new ObjectMapper());
        assertTrue(store.createTask("task-a", "session-a", "a", Map.of("targetCount", 1)));
        assertTrue(store.createTask("task-b", "session-a", "b", Map.of("targetCount", 1)));
        assertTrue(store.createTask("task-c", "session-b", "c", Map.of("targetCount", 1)));

        assertEquals(2, store.deleteTasksBySession("session-a"));
        assertTrue(store.list("session-a").isEmpty());
        assertEquals(1, store.list("session-b").size());
    }

    @Test
    void restoresSelectedStagesAndHostsDiscoveredWithoutReachabilityStage() throws Exception {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + tempDir.resolve("selected-stages.db"));
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("sql/schema.sql"));
        }
        NetworkProbeResultStore store = new NetworkProbeResultStore(dataSource, new ObjectMapper());
        List<Map<String, Object>> stages = List.of(Map.of("name", "PORT_SCAN", "status", "COMPLETED", "progress", 100));
        assertTrue(store.createTask("ports-only", "session-a", "ports", Map.of("targetCount", 2, "stages", List.of("PORT_SCAN"))));
        assertTrue(store.append("ports-only", List.of(
                Map.of("workflowStage", "PORT_SCAN", "stage", "tcp-connect", "host", "10.0.0.1", "port", 80, "state", "open"),
                Map.of("workflowStage", "PORT_SCAN", "stage", "tcp-connect", "host", "10.0.0.2", "port", 80, "state", "closed")), List.of()));
        store.updateTask("ports-only", "STOPPED", "COMPLETED", null, 100, 2, null, stages);
        Map<String, Object> summary = store.list("session-a").get(0);
        assertEquals(stages, summary.get("stages"));
        assertEquals(1, summary.get("stageCount"));
        assertEquals(1, summary.get("reachableHostCount"));
        assertEquals(List.of("10.0.0.1"), summary.get("reachableHostList"));

        assertTrue(store.createTask("alive-only", "session-a", "alive", Map.of("targetCount", 1)));
        assertTrue(store.append("alive-only", List.of(Map.of("workflowStage", "REACHABILITY", "stage", "tcp-connect",
                "host", "10.0.0.3", "port", 80, "state", "open")), List.of()));
        assertEquals(1, store.summary("session-a", "alive-only").get("reachableHostCount"));
        assertEquals(0L, store.queryResults("session-a", "alive-only", Map.of()).get("total"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void atomicallyStoresFingerprintEvidenceAndDeduplicatesReplayedMatches() throws Exception {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + tempDir.resolve("fingerprints.db"));
        dataSource.setEnforceForeignKeys(true);
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("sql/schema.sql"));
        }
        var store = new NetworkProbeResultStore(dataSource, new ObjectMapper());
        assertTrue(store.createTask("task", "session", "test", Map.of()));
        Map<String, Object> endpoint = Map.of("host", "127.0.0.1", "port", 80, "stage", "http-head", "state", "open",
                "workflowStage", "SERVICE_PROBE", "evidence", Map.of("statusCode", 200, "title", "Home"));
        assertTrue(store.append("task", List.of(endpoint), List.of()));
        Map<String, Object> observation = Map.of("host", "127.0.0.1", "port", 80, "stage", "http-request", "state", "open",
                "workflowStage", "FINGERPRINT", "targetId", "app", "ruleId", "demo", "probeId", "probe", "requestIndex", 0,
                "evidence", Map.of("statusCode", 404, "body", "marker"));
        Map<String, Object> match = Map.of("host", "127.0.0.1", "port", 80, "targetId", "app", "ruleId", "demo",
                "ruleHash", "hash", "ruleName", "Demo", "status", "MATCHED");
        Map<String, Object> summary = Map.of("host", "127.0.0.1", "port", 80,
                "fingerprint", Map.of("status", "COMPLETED", "components", List.of(match)));
        assertTrue(store.append("task", List.of(observation), List.of(), List.of(match), List.of(summary)));
        assertTrue(store.append("task", List.of(observation), List.of(), List.of(match), List.of(summary)));
        var rows = (List<Map<String, Object>>) store.queryResults("session", "task", Map.of()).get("endpoints");
        assertEquals(200, ((Number) rows.get(0).get("statusCode")).intValue());
        assertEquals("Home", rows.get(0).get("title"));
        assertEquals(1, store.summary("session", "task").get("fingerprintCount"));
        assertEquals(1L, store.queryFingerprintMatches("session", "task", Map.of("endpointId", "tcp|127.0.0.1|80")).get("total"));
        assertTrue(store.queryFingerprintMatches("another-session", "task", Map.of()).isEmpty());
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            assertEquals(2, scalar(statement, "SELECT COUNT(*) FROM scan_observations"));
            statement.executeUpdate("DROP TABLE scan_fingerprint_results");
        }
        var changed = new LinkedHashMap<>(observation);
        changed.put("probeId", "uncommitted");
        assertFalse(store.append("task", List.of(changed), List.of(), List.of(match), List.of(summary)));
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            assertEquals(2, scalar(statement, "SELECT COUNT(*) FROM scan_observations"));
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void restartMarksPendingFingerprintEvidenceAsIncomplete() throws Exception {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + tempDir.resolve("interrupted.db"));
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("sql/schema.sql"));
        }
        var store = new NetworkProbeResultStore(dataSource, new ObjectMapper());
        assertTrue(store.createTask("task", "session", "test", Map.of()));
        Map<String, Object> endpoint = Map.of("host", "127.0.0.1", "port", 80, "stage", "tcp-connect", "state", "open");
        Map<String, Object> match = Map.of("host", "127.0.0.1", "port", 80, "targetId", "app", "ruleId", "demo",
                "ruleHash", "hash", "ruleName", "Demo", "status", "PENDING");
        Map<String, Object> summary = Map.of("host", "127.0.0.1", "port", 80, "fingerprint", Map.of("status", "RUNNING"));
        assertTrue(store.append("task", List.of(endpoint), List.of(), List.of(match), List.of(summary)));
        store.markInterruptedTasks();
        assertEquals("FAILED", store.summary("session", "task").get("outcome"));
        var matches = (List<Map<String, Object>>) store.queryFingerprintMatches("session", "task", Map.of("endpointId", "tcp|127.0.0.1|80")).get("matches");
        assertEquals("INCONCLUSIVE", matches.get(0).get("status"));
        var rows = (List<Map<String, Object>>) store.queryResults("session", "task", Map.of()).get("endpoints");
        assertEquals("INTERRUPTED", ((Map<?, ?>) rows.get(0).get("fingerprint")).get("status"));
    }

    private int scalar(Statement statement, String sql) throws Exception {
        try (ResultSet rows = statement.executeQuery(sql)) {
            assertTrue(rows.next());
            return rows.getInt(1);
        }
    }
}
