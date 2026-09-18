package org.leo.web.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.leo.core.component.NetworkProbeComponent;
import org.leo.core.puppet.capability.NetworkProbeCapable;
import org.leo.service.fingerprint.FingerprintManageService;
import org.leo.web.dto.puppetnode.scan.NetworkDiscoveryDtos.*;
import org.leo.web.service.discovery.*;
import org.sqlite.SQLiteDataSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

class NetworkProbeFingerprintIntegrationTest {
    @TempDir Path directory;

    @Test
    @SuppressWarnings("unchecked")
    void scansApplicationPathsWithFrozenRulesAndRestoresEvidenceFromDatabase() throws Exception {
        List<String> requestedPaths = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            requestedPaths.add(path);
            byte[] body = ("x".repeat(5000) + "component-marker").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("X-Component", "demo");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        Map<String, Object> match = new LinkedHashMap<>(Map.of("all", List.of(
                Map.of("request", 0, "field", "body", "operator", "contains", "value", "component-marker"),
                Map.of("request", 1, "field", "headers", "operator", "contains", "value", "demo"))));
        HashMap<String, Object> rule = new HashMap<>(Map.of(
                "fingerprintId", "demo", "name", "Demo", "protocol", "http", "tags", List.of("web"),
                "rule", new LinkedHashMap<>(Map.of("requests", List.of(Map.of("uri", "/one"), Map.of("path", "/two")), "match", match))));
        FingerprintManageService library = new FingerprintManageService() {
            @Override public List<Map<String, Object>> listFingerprints() { return List.of(rule); }
            @Override public HashMap<String, Object> getFingerprintById(String id) { return rule; }
        };
        NetworkProbeAnalysisService analysis = new NetworkProbeAnalysisService(library);
        ScanPlanService planner = new ScanPlanService(new TargetResolver(), new PortPolicyResolver(), analysis);
        int port = server.getAddress().getPort();
        ScanConfig config = new ScanConfig("components", new TargetInput(List.of(
                "http://127.0.0.1:" + port + "/app", "http://127.0.0.1:" + port + "/other"), List.of()),
                null, new ExecutionConfig(2, 1000), new FingerprintConfig(List.of("WEB"), List.of()),
                List.of("PORT_SCAN", "SERVICE_PROBE", "FINGERPRINT"));
        var preview = new ScanPreviewService(planner).preview(config);
        assertTrue(preview.errors().isEmpty(), preview.errors().toString());
        assertEquals(1, preview.preview().fingerprintRuleCount());
        assertEquals(4L, preview.preview().fingerprintProbeUpperBound());
        var plan = planner.plan(config);
        // Mutating the library after submission must not change this task's evaluation.
        match.clear();
        match.putAll(Map.of("field", "body", "operator", "contains", "value", "edited-rule-must-not-run"));

        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + directory.resolve("results.db"));
        dataSource.setEnforceForeignKeys(true);
        try (var connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("sql/schema.sql"));
        }
        NetworkProbeResultStore store = new NetworkProbeResultStore(dataSource, new ObjectMapper());
        try (var orchestration = new NetworkProbeOrchestrationService(Executors.newSingleThreadExecutor(), 10);
             var workflow = new NetworkProbeWorkflowService(analysis, orchestration, Executors.newSingleThreadExecutor(), 10)) {
            workflow.setResultStore(store);
            String taskId = String.valueOf(workflow.start("session", new LocalNode(), plan).get("taskId"));
            Map<String, Object> snapshot = Map.of();
            long deadline = System.currentTimeMillis() + 10000;
            while (System.currentTimeMillis() < deadline) {
                snapshot = (Map<String, Object>) workflow.query("session", taskId).get("result");
                if ("STOPPED".equals(snapshot.get("status"))) break;
                Thread.sleep(20);
            }
            assertEquals("COMPLETED", snapshot.get("outcome"), snapshot.toString());
            assertTrue(requestedPaths.containsAll(List.of("/app/one", "/app/two", "/other/one", "/other/two")), requestedPaths.toString());

            // A new store instance represents a history page opened after the workflow is gone.
            store = new NetworkProbeResultStore(dataSource, new ObjectMapper());
            assertEquals(2, store.summary("session", taskId).get("fingerprintCount"));
            assertEquals(2, store.summary("session", taskId).get("identifiedApplicationCount"));
            var results = store.queryResults("session", taskId, Map.of("filter", Map.of("component", "demo")));
            assertEquals(1L, results.get("total"));
            Map<String, Object> endpoint = ((List<Map<String, Object>>) results.get("endpoints")).get(0);
            assertEquals(2, ((List<?>) ((Map<?, ?>) endpoint.get("fingerprint")).get("components")).size());
            var matches = (List<Map<String, Object>>) store.queryFingerprintMatches("session", taskId,
                    Map.of("endpointId", endpoint.get("endpointId"))).get("matches");
            assertEquals(2, matches.size());
            assertTrue(matches.stream().allMatch(result -> "MATCHED".equals(result.get("status"))));
            var evidence = store.fingerprintEvidence("session", taskId, String.valueOf(matches.get(0).get("matchKey")));
            assertEquals(2, ((List<?>) evidence.get("observations")).size());
            assertFalse(evidence.toString().contains("edited-rule-must-not-run"));
            assertTrue(store.fingerprintEvidence("other-session", taskId, String.valueOf(matches.get(0).get("matchKey"))).isEmpty());
            assertTrue(store.deleteTask("session", taskId));
            try (var connection = dataSource.getConnection(); var statement = connection.createStatement();
                 var rows = statement.executeQuery("SELECT COUNT(*) FROM scan_fingerprint_results")) {
                assertTrue(rows.next()); assertEquals(0, rows.getInt(1));
            }
        } finally { server.stop(0); }
    }

    @Test
    @SuppressWarnings("unchecked")
    void supplementsPersistedAssetsWithoutPortScanAndDebugsUnsavedRules() throws Exception {
        List<String> paths = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            paths.add(exchange.getRequestURI().getPath());
            byte[] body = "demo marker".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("X-Demo", "demo/2.4.1");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body); exchange.close();
        });
        server.start();
        int port = server.getAddress().getPort();
        String url = "http://127.0.0.1:" + port;
        Map<String, Object> definition = Map.of("requests", List.of(Map.of("path", "/info")),
                "match", Map.of("field", "body", "value", "marker"),
                "version", Map.of("field", "headers", "prefix", "demo/"));
        HashMap<String, Object> first = new HashMap<>(Map.of("fingerprintId", "first", "name", "Demo", "protocol", "http", "rule", definition));
        HashMap<String, Object> second = new HashMap<>(first); second.put("fingerprintId", "second");
        FingerprintManageService library = new FingerprintManageService() {
            @Override public List<Map<String, Object>> listFingerprints() { return List.of(first, second); }
            @Override public HashMap<String, Object> getFingerprintById(String id) { return "first".equals(id) ? first : second; }
        };
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + directory.resolve("supplement.db"));
        try (var connection = dataSource.getConnection()) { ScriptUtils.executeSqlScript(connection, new ClassPathResource("sql/schema.sql")); }
        var store = new NetworkProbeResultStore(dataSource, new ObjectMapper());
        Map<String, Object> configured = Map.of("host", "127.0.0.1", "port", port, "applications", List.of(
                Map.of("baseUrl", url + "/app/"), Map.of("baseUrl", url + "/other/")));
        assertTrue(store.createTask("source", "session", "source", Map.of("targets", List.of(configured))));
        assertTrue(store.append("source", List.of(Map.of("host", "127.0.0.1", "port", port, "state", "open",
                "stage", "http-head", "workflowStage", "SERVICE_PROBE", "protocol", "http", "service", "http",
                "evidence", Map.of("statusCode", 201, "title", "Original home"))), List.of()));
        String endpointId = "tcp|127.0.0.1|" + port;
        assertThrows(IllegalArgumentException.class, () -> store.fingerprintSource("other-session", "source", List.of(endpointId)));
        assertThrows(IllegalArgumentException.class, () -> store.fingerprintSource("session", "source", List.of("missing")));
        assertTrue(store.append("source", List.of(Map.of("host", "127.0.0.1", "port", 22, "state", "open",
                "stage", "tcp-connect", "workflowStage", "PORT_SCAN")), List.of()));
        assertThrows(IllegalArgumentException.class, () -> store.fingerprintSource("session", "source", List.of("tcp|127.0.0.1|22")));
        var analysis = new NetworkProbeAnalysisService(library);
        try (var orchestration = new NetworkProbeOrchestrationService(Executors.newSingleThreadExecutor(), 10);
             var workflow = new NetworkProbeWorkflowService(analysis, orchestration, Executors.newSingleThreadExecutor(), 10)) {
            workflow.setResultStore(store);
            LocalNode node = new LocalNode();
            String id = String.valueOf(workflow.startSupplemental("session", node, "source", List.of(endpointId), Map.of()).get("taskId"));
            var summary = awaitWorkflow(workflow, id);
            assertEquals("COMPLETED", summary.get("outcome"), summary.toString());
            assertEquals(List.of("/app/info", "/other/info"), paths.stream().sorted().toList());
            assertEquals(1, ((List<?>) summary.get("stages")).size());
            assertTrue(node.stages.toString().contains("http-request"));
            assertFalse(node.stages.toString().contains("tcp"), node.stages.toString());
            var stage = (Map<?, ?>) ((List<?>) store.summary("session", id).get("stages")).get(0);
            assertEquals(4, stage.get("logicalRequestCount"));
            assertEquals(2, stage.get("networkRequestCount"));
            var matches = (List<Map<String, Object>>) store.queryFingerprintMatches("session", id, Map.of()).get("matches");
            assertEquals(4, matches.size());
            for (var match : matches) {
                assertEquals("2.4.1", match.get("detectedVersion"));
                var evidence = store.fingerprintEvidence("session", id, String.valueOf(match.get("matchKey")));
                var observations = (List<Map<String, Object>>) evidence.get("observations");
                assertEquals(1, observations.size());
                assertEquals(match.get("ruleId"), observations.get(0).get("ruleId"));
                assertEquals(0, observations.get(0).get("requestIndex"));
                assertTrue(observations.get(0).containsKey("request"));
            }
            var endpoint = ((List<Map<String, Object>>) store.queryResults("session", id, Map.of()).get("endpoints")).get(0);
            assertEquals("Original home", endpoint.get("title"));
            assertEquals(201, endpoint.get("statusCode"));
            assertEquals(0, store.summary("session", "source").get("fingerprintCount"));

            HashMap<String, Object> draft = new HashMap<>(first);
            draft.put("fingerprintId", "unsaved");
            draft.put("rule", Map.of("requests", List.of(Map.of("path", "/info")), "match", Map.of("all", List.of(
                    Map.of("field", "status", "operator", "equals", "value", 200),
                    Map.of("field", "body", "value", "missing")))));
            String debugId = String.valueOf(workflow.startFingerprint("session", node,
                    List.of(Map.of("host", "127.0.0.1", "port", port, "service", "http", "protocol", "http")),
                    List.of(Map.of("host", "127.0.0.1", "port", port, "baseUrl", url + "/draft/")),
                    List.of(draft), "debug", "", true).get("taskId"));
            assertEquals("COMPLETED", awaitWorkflow(workflow, debugId).get("outcome"));
            var debug = ((List<Map<String, Object>>) store.queryFingerprintMatches("session", debugId, Map.of()).get("matches")).get(0);
            assertEquals("NOT_MATCHED", debug.get("status"));
            assertEquals(List.of("NOT_MATCHED", "MATCHED", "NOT_MATCHED"), ((List<Map<String, Object>>) debug.get("conditions")).stream().map(item -> item.get("status")).toList());
            assertEquals(3, paths.size());
            assertTrue(paths.contains("/draft/info"));
        } finally { server.stop(0); }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> awaitWorkflow(NetworkProbeWorkflowService workflow, String id) throws Exception {
        long deadline = System.currentTimeMillis() + 10000;
        Map<String, Object> result = Map.of();
        while (System.currentTimeMillis() < deadline) {
            result = (Map<String, Object>) workflow.query("session", id).get("result");
            if ("STOPPED".equals(result.get("status"))) return result;
            Thread.sleep(20);
        }
        fail("工作流未完成: " + result);
        return result;
    }

    /** Exercise the real standalone component, including its incremental result cursors. */
    private static final class LocalNode implements NetworkProbeCapable {
        final List<Object> stages = new CopyOnWriteArrayList<>();
        @SuppressWarnings("unchecked")
        private Map<String, Object> invoke(String action, Map<String, Object> arguments) throws Exception {
            NetworkProbeComponent component = new NetworkProbeComponent();
            HashMap<String, Object> params = new HashMap<>(arguments);
            params.put("methodName", action);
            var parameterField = NetworkProbeComponent.class.getDeclaredField("params");
            parameterField.setAccessible(true); parameterField.set(component, params);
            var resultField = NetworkProbeComponent.class.getDeclaredField("results");
            resultField.setAccessible(true); resultField.set(component, new HashMap<>());
            component.invoke();
            return (Map<String, Object>) resultField.get(component);
        }
        public Map<String, Object> startNetworkProbe(Map<String, Object> plan) throws Exception { stages.add(plan.get("stages")); return invoke("startTask", Map.of("plan", plan)); }
        public Map<String, Object> queryNetworkProbe(String id, long cursor, int items, int bytes, boolean evidence) throws Exception {
            return invoke("queryTask", Map.of("taskId", id, "cursor", cursor, "maxItems", items, "maxBytes", bytes, "includeEvidence", evidence));
        }
        public Map<String, Object> ackNetworkProbe(String id, long cursor) throws Exception { return invoke("ackTask", Map.of("taskId", id, "cursor", cursor)); }
        public Map<String, Object> pauseNetworkProbe(String id) throws Exception { return invoke("pauseTask", Map.of("taskId", id)); }
        public Map<String, Object> resumeNetworkProbe(String id) throws Exception { return invoke("resumeTask", Map.of("taskId", id)); }
        public Map<String, Object> stopNetworkProbe(String id) throws Exception { return invoke("stopTask", Map.of("taskId", id)); }
        public Map<String, Object> releaseNetworkProbe(String id) throws Exception { return invoke("releaseTask", Map.of("taskId", id)); }
    }
}
