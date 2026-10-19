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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NetworkProbeResultStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void restoresStrictDiscoveryStatisticsFromTheDurableStageSnapshot() throws Exception {
        var dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + tempDir.resolve("discovery-stats.db"));
        try (var connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("sql/schema.sql"));
        }
        var store = new NetworkProbeResultStore(dataSource, new ObjectMapper());
        assertTrue(store.createTask("stats", "session", "strict", Map.of("targetCount", 4)));
        assertTrue(store.append("stats", List.of(Map.of("workflowStage", "REACHABILITY", "stage", "java-reachability",
                "host", "127.0.0.1", "port", 0, "state", "open")), List.of()));
        store.updateTask("stats", "STOPPED", "COMPLETED", null, 100, 4, null, List.of(
                Map.of("name", "REACHABILITY", "status", "COMPLETED", "reachableHostCount", 1,
                        "unknownHostCount", 2, "unreachableHostCount", 1, "pendingHostCount", 0),
                Map.of("name", "PORT_SCAN", "status", "COMPLETED")));
        var restored = store.summary("session", "stats");
        assertEquals(2, restored.get("unknownHostCount")); assertEquals(1, restored.get("unreachableHostCount"));
        assertEquals(0, restored.get("pendingHostCount")); assertEquals(3, restored.get("skippedHostCount"));
        assertEquals(1, restored.get("portScanHostCount")); assertEquals(1, restored.get("reachableHostCount"));
    }

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
        Map<String, Object> taskSummary = store.summary("session-a", "task-delete");
        assertEquals(1, taskSummary.get("reachableHostCount"));
        assertEquals(List.of("10.0.0.10"), taskSummary.get("reachableHostList"));
        assertEquals(1L, store.queryResults("session-a", "task-delete", Map.of()).get("total"));
        Map<String, Object> endpoint = ((List<Map<String, Object>>) store.queryResults("session-a", "task-delete", Map.of()).get("endpoints")).get(0);
        assertEquals(200, ((Number) endpoint.get("statusCode")).intValue());
        assertEquals(1240, ((Number) endpoint.get("responseSize")).intValue());
        assertEquals(store.queryResults("session-a", "task-delete", Map.of()),
                store.queryResults("session-a", "task-delete", null));

        assertFalse(store.deleteTask("session-b", "task-delete"));
        assertTrue(store.deleteTask("session-a", "task-delete"));
        assertTrue(((List<?>) store.list("session-a", Map.of()).get("tasks")).isEmpty());
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
        assertTrue(((List<?>) store.list("session-a", Map.of()).get("tasks")).isEmpty());
        assertEquals(1, ((List<?>) store.list("session-b", Map.of()).get("tasks")).size());
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
                Map.of("workflowStage", "PORT_SCAN", "stage", "tcp-connect", "host", "10.0.0.2", "port", 80, "state", "closed", "errorCode", "REFUSED")), List.of()));
        store.updateTask("ports-only", "STOPPED", "COMPLETED", null, 100, 2, null, stages);
        Map<String, Object> summary = store.summary("session-a", "ports-only");
        assertEquals(stages, summary.get("stages"));
        assertEquals(1, summary.get("stageCount"));
        assertEquals(2, summary.get("reachableHostCount"));
        assertEquals(List.of("10.0.0.1", "10.0.0.2"), summary.get("reachableHostList"));

        assertTrue(store.createTask("alive-only", "session-a", "alive", Map.of("targetCount", 1)));
        assertTrue(store.append("alive-only", List.of(Map.of("workflowStage", "REACHABILITY", "stage", "tcp-connect",
                "host", "10.0.0.3", "port", 80, "state", "open")), List.of()));
        assertEquals(1, store.summary("session-a", "alive-only").get("reachableHostCount"));
        assertEquals(0L, store.queryResults("session-a", "alive-only", Map.of()).get("total"));
    }

    @Test
    void deduplicatesReportedErrorsAgainstObservationsAndEarlierErrors() throws Exception {
        SQLiteDataSource source = new SQLiteDataSource();
        source.setUrl("jdbc:sqlite:" + tempDir.resolve("errors.db"));
        try (var connection = source.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("sql/schema.sql"));
        }
        var store = new NetworkProbeResultStore(source, new ObjectMapper());
        assertTrue(store.createTask("task", "session", "errors", Map.of()));
        Map<String, Object> reported = Map.of("host", "127.0.0.1", "port", 80, "errorCode", "RESET");
        var observed = new LinkedHashMap<>(reported);
        observed.putAll(Map.of("stage", "error", "state", "error", "latencyMs", 10));
        Map<String, Object> distinct = Map.of("host", "127.0.0.1", "port", 81, "errorCode", "RESET");
        var repeated = new LinkedHashMap<>(distinct);
        repeated.put("latencyMs", 20);
        assertTrue(store.append("task", List.of(observed), List.of(reported, distinct, repeated)));
        store.updateTask("task", "STOPPED", "FAILED", null, 100, 0);
        assertEquals(2, store.summary("session", "task").get("errorCount"));
        try (var connection = source.getConnection(); var statement = connection.createStatement()) {
            assertEquals(2, scalar(statement, "SELECT COUNT(*) FROM scan_observations"));
            assertEquals(1, scalar(statement, "SELECT COUNT(*) FROM scan_observations WHERE json_extract(observation_json, '$.latencyMs')=10"));
        }
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
        assertEquals(0, store.summaryCounts("session", "task").get("fingerprintCount"));
        var matches = (List<Map<String, Object>>) store.queryFingerprintMatches("session", "task", Map.of("endpointId", "tcp|127.0.0.1|80")).get("matches");
        assertEquals("INCONCLUSIVE", matches.get(0).get("status"));
        var rows = (List<Map<String, Object>>) store.queryResults("session", "task", Map.of()).get("endpoints");
        assertEquals("INTERRUPTED", ((Map<?, ?>) rows.get(0).get("fingerprint")).get("status"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void pagesStableHistoryAndFindsOldActiveTasksIndependently() throws Exception {
        SQLiteDataSource source = new SQLiteDataSource();
        source.setUrl("jdbc:sqlite:" + tempDir.resolve("history.db"));
        try (var connection = source.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("sql/schema.sql"));
        }
        var store = new NetworkProbeResultStore(source, new ObjectMapper());
        for (int index = 0; index < 25; index++) {
            String id = "task-%02d".formatted(index);
            assertTrue(store.createTask(id, "session", id, Map.of()));
            store.updateTask(id, "STOPPED", "COMPLETED", null, 100, 0);
        }
        assertTrue(store.createTask("active-old", "session", "old", Map.of()));
        assertTrue(store.createTask("foreign", "other", "foreign", Map.of()));
        try (var connection = source.getConnection(); var statement = connection.createStatement()) {
            statement.executeUpdate("UPDATE scan_tasks SET created_at='2026-10-15T01:00:00Z'");
            statement.executeUpdate("UPDATE scan_tasks SET created_at='2026-10-14T01:00:00Z' WHERE task_id='active-old'");
        }
        Map<String, Object> first = store.list("session", Map.of());
        var tasks = (List<Map<String, Object>>) first.get("tasks");
        assertEquals(20, tasks.size());
        assertEquals("task-24", tasks.get(0).get("taskId"));
        assertEquals("task-05", tasks.get(19).get("taskId"));
        assertEquals(true, first.get("hasMore"));
        // A deleted anchor still works, and a new task above it cannot shift the next page.
        assertTrue(store.deleteTask("session", "task-05"));
        assertTrue(store.createTask("new", "session", "new", Map.of()));
        var next = store.list("session", Map.of("cursor", first.get("nextCursor")));
        assertEquals(List.of("task-04", "task-03", "task-02", "task-01", "task-00", "active-old"),
                ((List<Map<String, Object>>) next.get("tasks")).stream().map(row -> row.get("taskId")).toList());
        assertEquals(false, next.get("hasMore"));
        assertEquals("", next.get("nextCursor"));
        var live = (List<Map<String, Object>>) store.list("session", Map.of("scope", "active")).get("tasks");
        assertEquals(List.of("new", "active-old"), live.stream().map(row -> row.get("taskId")).toList());
        store.updateTask("active-old", "PAUSED", "RUNNING", null, 0, 0);
        var livePage = store.list("session", Map.of("scope", "active", "pageSize", 1));
        var pausedPage = store.list("session", Map.of("scope", "active", "cursor", livePage.get("nextCursor")));
        assertEquals("PAUSED", ((List<Map<String, Object>>) pausedPage.get("tasks")).get(0).get("status"));
        assertEquals(100, store.list("session", Map.of("pageSize", 1000)).get("pageSize"));
        assertThrows(IllegalArgumentException.class, () -> store.list("session", Map.of("cursor", "invalid!")));
        assertThrows(IllegalArgumentException.class, () -> store.list("session", Map.of("scope", "unknown")));
        assertEquals(List.of("foreign"), ((List<Map<String, Object>>) store.list("other",
                Map.of("cursor", first.get("nextCursor"))).get("tasks")).stream().map(row -> row.get("taskId")).toList());
    }

    @Test
    @SuppressWarnings("unchecked")
    void batchesListCountsWithoutLoadingStageDetailsOrHostSamples() throws Exception {
        SQLiteDataSource source = new SQLiteDataSource();
        source.setUrl("jdbc:sqlite:" + tempDir.resolve("list-counts.db"));
        try (var connection = source.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("sql/schema.sql"));
        }
        var store = new NetworkProbeResultStore(source, new ObjectMapper());
        assertTrue(store.createTask("task", "session", "scan", Map.of()));
        assertTrue(store.createTask("empty", "session", "empty", Map.of()));
        assertTrue(store.append("task", List.of(
                Map.of("stage", "tcp-connect", "host", "127.0.0.1", "port", 80, "state", "open"),
                Map.of("stage", "tcp-connect", "host", "127.0.0.1", "port", 81, "state", "closed", "errorCode", "REFUSED"),
                Map.of("stage", "tcp-connect", "host", "127.0.0.2", "port", 80, "state", "closed", "errorCode", "REFUSED")),
                List.of(), List.of(Map.of("host", "127.0.0.1", "port", 80, "targetId", "app", "ruleId", "rule",
                        "ruleHash", "hash", "status", "MATCHED")), List.of()));
        store.updateTask("task", "STOPPED", "COMPLETED", null, 100, 2, "detail error",
                List.of(Map.of("name", "PORT_SCAN", "completed", 3)));
        var counted = mock(javax.sql.DataSource.class);
        var connection = spy(source.getConnection());
        when(counted.getConnection()).thenReturn(connection);
        var page = new NetworkProbeResultStore(counted, new ObjectMapper()).list("session", Map.of());
        verify(connection, times(3)).prepareStatement(anyString());
        var rows = (List<Map<String, Object>>) page.get("tasks");
        var task = rows.stream().filter(row -> "task".equals(row.get("taskId"))).findFirst().orElseThrow();
        assertEquals(2, task.get("reachableHostCount"));
        assertEquals(1, task.get("fingerprintCount"));
        assertEquals(1, task.get("identifiedApplicationCount"));
        assertFalse(task.containsKey("reachableHostList"));
        assertFalse(task.containsKey("stages"));
        assertFalse(task.containsKey("error"));
        var empty = rows.stream().filter(row -> "empty".equals(row.get("taskId"))).findFirst().orElseThrow();
        assertEquals(0, empty.get("reachableHostCount"));
        assertEquals(0, empty.get("fingerprintCount"));
        var countsConnection = spy(source.getConnection());
        when(counted.getConnection()).thenReturn(countsConnection);
        assertEquals(Map.of("openCount", 1, "serviceCount", 0, "errorCount", 0, "fingerprintCount", 1,
                "identifiedApplicationCount", 1), new NetworkProbeResultStore(counted, new ObjectMapper()).summaryCounts("session", "task"));
        verify(countsConnection).prepareStatement(anyString());
        assertEquals(Map.of("openCount", 0, "serviceCount", 0, "errorCount", 0, "fingerprintCount", 0,
                "identifiedApplicationCount", 0), store.summaryCounts("session", "empty"));
        assertTrue(store.summaryCounts("another-session", "task").isEmpty());
        assertTrue(store.summaryCounts("session", "missing").isEmpty());
        assertTrue(store.summary("session", "task").containsKey("stages"));
    }

    private int scalar(Statement statement, String sql) throws Exception {
        try (ResultSet rows = statement.executeQuery(sql)) {
            assertTrue(rows.next());
            return rows.getInt(1);
        }
    }
}
