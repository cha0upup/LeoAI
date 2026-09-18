package org.leo.web.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.stream.Stream;
import org.leo.core.puppet.capability.NetworkProbeCapable;
import org.leo.service.fingerprint.FingerprintManageService;
import org.leo.web.exception.ApiException;
import org.leo.web.dto.puppetnode.scan.NetworkDiscoveryDtos.*;
import org.leo.web.service.discovery.PortPolicyResolver;
import org.leo.web.service.discovery.ScanPlanService;
import org.leo.web.service.discovery.TargetResolver;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class NetworkProbeWorkflowServiceTest {

    private final ExecutorService workflowExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService orchestrationExecutor = Executors.newSingleThreadExecutor();
    private final NetworkProbeOrchestrationService orchestration =
            new NetworkProbeOrchestrationService(orchestrationExecutor, 1L);
    private final NetworkProbeAnalysisService analysis =
            new NetworkProbeAnalysisService(new StubFingerprintManageService());
    private final NetworkProbeWorkflowService service =
            new NetworkProbeWorkflowService(analysis, orchestration, workflowExecutor, 1L);

    @AfterEach
    void closeService() {
        service.close();
        orchestration.close();
    }

    @Test
    void runsReachabilityPortAndServiceIdentificationAsOneTask() throws Exception {
        WorkflowNode node = new WorkflowNode();
        String taskId = String.valueOf(service.start("session-1", node, plan(List.of(80, 22), null)).get("taskId"));

        Map<String, Object> snapshot = awaitTerminal(taskId);
        List<?> stages = (List<?>) snapshot.get("stages");

        assertEquals("COMPLETED", snapshot.get("outcome"), snapshot.toString());
        assertEquals(NetworkProbeWorkflowService.KIND, snapshot.get("scanKind"));
        assertEquals(List.of("REACHABILITY", "PORT_SCAN", "SERVICE_PROBE"),
                stages.stream().map(stage -> ((Map<?, ?>) stage).get("name")).toList());
        assertTrue(stages.stream().allMatch(stage -> "COMPLETED".equals(((Map<?, ?>) stage).get("status"))));
        assertEquals(List.of("REACHABILITY", "PORT_SCAN", "SERVICE_PROBE", "SERVICE_PROBE"),
                node.startedStages);
        assertEquals(List.of("host-a"), snapshot.get("reachableHostList"));
        assertEquals(1, ((List<?>) snapshot.get("openPortResults")).size());
        assertEquals("http", ((Map<?, ?>) ((List<?>) snapshot.get("openPortResults")).get(0)).get("service"));
    }

    @Test
    void runsFingerprintStageAndSkipsItWhenThereAreNoHttpApplications() throws Exception {
        WorkflowNode node = new WorkflowNode();
        var stages = List.of("PORT_SCAN", "SERVICE_PROBE", "FINGERPRINT");
        var request = plan(List.of(80), stages);
        String id = String.valueOf(service.start("session-1", node, request).get("taskId"));
        var snapshot = awaitTerminal(id);
        assertEquals("COMPLETED", snapshot.get("outcome"), snapshot.toString());
        assertEquals(1, service.querySummaryIfPresent("session-1", id).get("fingerprintCount"));
        assertTrue(node.startedStages.contains("RECON"));
        WorkflowNode tcpOnly = new WorkflowNode();
        tcpOnly.openPorts = Set.of(5432);
        request = plan(List.of(5432), stages);
        id = String.valueOf(service.start("session-1", tcpOnly, request).get("taskId"));
        snapshot = awaitTerminal(id);
        assertEquals("COMPLETED", snapshot.get("outcome"));
        assertEquals("NO_HTTP_APPLICATIONS", ((Map<?, ?>) ((List<?>) snapshot.get("stages")).get(2)).get("reason"));
        assertTrue(!tcpOnly.startedStages.contains("RECON"));
    }

    @Test
    void probesUnknownNonStandardPortsForHttpAndSkipsKnownTcpServices() throws Exception {
        WorkflowNode node = new WorkflowNode();
        node.openPorts = Set.of(80, 18080, 5432);
        String taskId = String.valueOf(service.start("session-1", node, plan(List.of(80, 18080, 5432), null)).get("taskId"));

        Map<String, Object> snapshot = awaitTerminal(taskId);
        List<Map<String, Object>> openPorts = (List<Map<String, Object>>) snapshot.get("openPortResults");

        Map<String, Object> web = openPorts.stream()
                .filter(endpoint -> Integer.valueOf(18080).equals(endpoint.get("port")))
                .findFirst().orElseThrow();
        Map<String, Object> postgres = openPorts.stream()
                .filter(endpoint -> Integer.valueOf(5432).equals(endpoint.get("port")))
                .findFirst().orElseThrow();
        assertEquals("https", web.get("service"));
        assertEquals(200, web.get("statusCode"));
        assertEquals("postgresql", postgres.get("service"));
        assertEquals(List.of("REACHABILITY", "PORT_SCAN", "SERVICE_PROBE", "SERVICE_PROBE",
                        "SERVICE_PROBE"),
                node.startedStages);
    }

    @Test
    void skipsDependentStagesWhenNoHostIsReachable() throws Exception {
        WorkflowNode node = new WorkflowNode();
        node.reachable = false;
        String taskId = String.valueOf(service.start("session-1", node, plan(List.of(80), null)).get("taskId"));

        Map<String, Object> snapshot = awaitTerminal(taskId);
        List<?> stages = (List<?>) snapshot.get("stages");

        assertEquals("COMPLETED", snapshot.get("outcome"), snapshot.toString());
        assertEquals("COMPLETED", ((Map<?, ?>) stages.get(0)).get("status"));
        assertEquals("SKIPPED", ((Map<?, ?>) stages.get(1)).get("status"));
        assertEquals("SKIPPED", ((Map<?, ?>) stages.get(2)).get("status"));
        assertTrue(node.startedStages.size() == 1);
    }

    @Test
    void executesReachabilityAndPortsWithoutServiceIdentification() throws Exception {
        WorkflowNode node = new WorkflowNode();
        String taskId = String.valueOf(service.start("session-1", node, plan(List.of(80), List.of("REACHABILITY", "PORT_SCAN"))).get("taskId"));

        Map<String, Object> snapshot = awaitTerminal(taskId);
        List<?> stages = (List<?>) snapshot.get("stages");

        assertEquals("COMPLETED", snapshot.get("outcome"), snapshot.toString());
        assertEquals(List.of("REACHABILITY", "PORT_SCAN"), node.startedStages);
        assertEquals(2, stages.size());
        assertEquals(2, snapshot.get("stageCount"));
    }

    static Stream<List<String>> selectedStages() {
        return Stream.of(List.of("REACHABILITY"), List.of("PORT_SCAN"),
                List.of("REACHABILITY", "PORT_SCAN"), List.of("PORT_SCAN", "SERVICE_PROBE"),
                List.of("REACHABILITY", "PORT_SCAN", "SERVICE_PROBE"));
    }

    @ParameterizedTest
    @MethodSource("selectedStages")
    void executesOnlySelectedStagesAndReportsConfirmedHosts(List<String> selected) throws Exception {
        WorkflowNode node = new WorkflowNode();
        node.reachable = selected.contains("REACHABILITY"); // discovery would fail when bypassed
        var request = plan(selected.contains("PORT_SCAN") ? List.of(80) : List.of(), selected);
        String taskId = String.valueOf(service.start("session-1", node, request).get("taskId"));
        Map<String, Object> snapshot = awaitTerminal(taskId);

        assertEquals("COMPLETED", snapshot.get("outcome"), snapshot.toString());
        assertEquals(selected, node.startedStages.stream().distinct().toList());
        assertEquals(selected.size(), snapshot.get("stageCount"));
        assertEquals(selected.size(), snapshot.get("completedStageCount"));
        assertEquals(100, snapshot.get("progress"));
        assertEquals(List.of("host-a"), snapshot.get("reachableHostList"));
        assertEquals(selected.contains("PORT_SCAN") ? 1 : 0, ((List<?>) snapshot.get("openPortResults")).size());
        assertEquals(selected.contains("SERVICE_PROBE") ? 1 : 0, ((List<?>) snapshot.get("serviceResults")).size());
    }

    @Test
    void bypassedDiscoveryDoesNotMarkClosedHostsAsAlive() throws Exception {
        WorkflowNode node = new WorkflowNode();
        node.openPorts = Set.of();
        String taskId = String.valueOf(service.start("session-1", node, plan(List.of(80), List.of("PORT_SCAN", "SERVICE_PROBE"))).get("taskId"));
        Map<String, Object> snapshot = awaitTerminal(taskId);
        assertEquals("COMPLETED", snapshot.get("outcome"));
        assertEquals(List.of(), snapshot.get("reachableHostList"));
        assertEquals(List.of("PORT_SCAN"), node.startedStages);
        assertEquals("NO_OPEN_PORTS", ((Map<?, ?>) ((List<?>) snapshot.get("stages")).get(1)).get("reason"));
    }

    @Test
    void acceptsDerivedOpenPortResultsFromCompletedPortStage() throws Exception {
        Method method = NetworkProbeWorkflowService.class.getDeclaredMethod("openEndpoints", Map.class);
        method.setAccessible(true);
        Map<String, Object> result = Map.of("openPortResults", List.of(Map.of(
                "host", "host-a", "port", 8080, "state", "open")));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> endpoints = (List<Map<String, Object>>) method.invoke(service, result);
        assertEquals(1, endpoints.size());
        assertEquals("host-a", endpoints.get(0).get("host"));
        assertEquals(8080, endpoints.get(0).get("port"));
    }

    @Test
    void identifiesBinaryMysqlHandshakeWithoutMysqlWord() throws Exception {
        Method method = NetworkProbeWorkflowService.class
                .getDeclaredMethod("detectService", String.class, int.class);
        method.setAccessible(true);
        String handshake = "J\n8.0.43\u0000\u0010\u0000caching_sha2_password";
        assertEquals("mysql", method.invoke(null, handshake, 3306));
    }

    @Test
    void deletesCompletedWorkflowFromLiveRegistry() throws Exception {
        WorkflowNode node = new WorkflowNode();
        String taskId = String.valueOf(service.start("session-1", node, plan(List.of(80), List.of("REACHABILITY", "PORT_SCAN"))).get("taskId"));

        awaitTerminal(taskId);

        assertEquals("DELETED", service.delete("session-1", taskId).get("status"));
        ApiException error = assertThrows(ApiException.class,
                () -> service.query("session-1", taskId));
        assertEquals(404, error.getCode());
    }

    @Test
    void preservesCancelledStageOutcomeAndDoesNotRunDependentStages() throws Exception {
        CancelledWorkflowNode node = new CancelledWorkflowNode();
        String taskId = String.valueOf(service.start("session-1", node, plan(List.of(80), null)).get("taskId"));

        Map<String, Object> snapshot = awaitTerminal(taskId);

        assertEquals("CANCELLED", snapshot.get("outcome"), snapshot.toString());
        assertEquals(List.of("REACHABILITY"), node.startedStages);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void controlsTheActiveStageAndChildTask(boolean skipReachability) throws Exception {
        ControlledWorkflowNode node = new ControlledWorkflowNode();
        String taskId = String.valueOf(service.start("session-1", node, plan(List.of(80), skipReachability ? List.of("PORT_SCAN", "SERVICE_PROBE")
                        : List.of("REACHABILITY", "PORT_SCAN", "SERVICE_PROBE"))).get("taskId"));

        assertTrue(node.started.await(1L, TimeUnit.SECONDS));
        assertEquals("PAUSED", service.pause("session-1", taskId).get("status"));
        awaitStatus(taskId, "PAUSED");
        awaitCount(node.pauseCalls);

        assertEquals("RUNNING", service.resume("session-1", taskId).get("status"));
        awaitStatus(taskId, "RUNNING");
        awaitCount(node.resumeCalls);

        assertEquals("STOPPED", service.stop("session-1", taskId).get("status"));
        Map<String, Object> snapshot = awaitTerminal(taskId);
        assertEquals("CANCELLED", snapshot.get("outcome"));
        awaitCount(node.stopCalls);
    }

    @Test
    void controlsFingerprintChildAfterServiceIdentificationHasFinished() throws Exception {
        var node = new FingerprintControlledNode();
        String id = String.valueOf(service.start("session-1", node, plan(List.of(80), List.of("PORT_SCAN", "SERVICE_PROBE", "FINGERPRINT"))).get("taskId"));
        assertTrue(node.control.started.await(2, TimeUnit.SECONDS));
        service.pause("session-1", id);
        awaitCount(node.control.pauseCalls);
        assertEquals("PAUSED", service.querySummaryIfPresent("session-1", id).get("status"));
        assertEquals("FINGERPRINT", service.querySummaryIfPresent("session-1", id).get("currentStage"));
        service.resume("session-1", id);
        awaitCount(node.control.resumeCalls);
        service.stop("session-1", id);
        assertEquals("CANCELLED", awaitTerminal(id).get("outcome"));
        awaitCount(node.control.stopCalls);
    }

    private ScanPlanService.ScanPlan plan(List<Integer> ports, List<String> stages) {
        TargetResolver resolver = mock(TargetResolver.class);
        when(resolver.resolve(any())).thenReturn(List.of(
                new ResolvedTarget("host-a", "host-a", "host-a", null, "tcp", "host", "host-a")));
        ScanPlanService planner = new ScanPlanService(resolver, new PortPolicyResolver(), analysis);
        return planner.plan(new ScanConfig("test workflow", new TargetInput(List.of("host-a"), List.of()),
                new PortPolicy("custom", List.of(), ports, List.of()),
                new ExecutionConfig(4, 1000), null, stages));
    }

    private static final class FingerprintControlledNode implements NetworkProbeCapable {
        private final WorkflowNode discovery = new WorkflowNode();
        private final ControlledWorkflowNode control = new ControlledWorkflowNode();
        private NetworkProbeCapable node(String id) { return "controlled-child".equals(id) ? control : discovery; }
        public Map<String, Object> startNetworkProbe(Map<String, Object> plan) {
            return ((List<?>) plan.get("stages")).contains("http-request")
                    ? control.startNetworkProbe(plan) : discovery.startNetworkProbe(plan);
        }
        public Map<String, Object> queryNetworkProbe(String id, long cursor, int items, int bytes, boolean evidence) throws Exception {
            return node(id).queryNetworkProbe(id, cursor, items, bytes, evidence);
        }
        public Map<String, Object> pauseNetworkProbe(String id) throws Exception { return node(id).pauseNetworkProbe(id); }
        public Map<String, Object> resumeNetworkProbe(String id) throws Exception { return node(id).resumeNetworkProbe(id); }
        public Map<String, Object> stopNetworkProbe(String id) throws Exception { return node(id).stopNetworkProbe(id); }
        public Map<String, Object> releaseNetworkProbe(String id) throws Exception { return node(id).releaseNetworkProbe(id); }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> awaitTerminal(String taskId) throws Exception {
        Map<String, Object> snapshot = Map.of();
        for (int attempt = 0; attempt < 200; attempt++) {
            snapshot = (Map<String, Object>) service.query("session-1", taskId).get("result");
            if ("STOPPED".equals(snapshot.get("status"))) return snapshot;
            Thread.sleep(5L);
        }
        throw new AssertionError("workflow did not stop: " + snapshot);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> awaitStatus(String taskId, String expectedStatus) throws Exception {
        Map<String, Object> snapshot = Map.of();
        for (int attempt = 0; attempt < 200; attempt++) {
            snapshot = (Map<String, Object>) service.query("session-1", taskId).get("result");
            if (expectedStatus.equals(snapshot.get("status"))) return snapshot;
            Thread.sleep(5L);
        }
        throw new AssertionError("workflow did not reach " + expectedStatus + ": " + snapshot);
    }

    private static void awaitCount(AtomicInteger counter) throws Exception {
        for (int attempt = 0; attempt < 200; attempt++) {
            if (counter.get() > 0) return;
            Thread.sleep(5L);
        }
        throw new AssertionError("expected control call");
    }

    private static final class WorkflowNode implements NetworkProbeCapable {
        private final Map<String, Map<String, Object>> tasks = new LinkedHashMap<>();
        private final List<String> startedStages = new ArrayList<>();
        private Set<Integer> openPorts = Set.of(80);
        private boolean reachable = true;

        @Override
        @SuppressWarnings("unchecked")
        public synchronized Map<String, Object> startNetworkProbe(Map<String, Object> plan) {
            List<Map<String, Object>> targets = (List<Map<String, Object>>) plan.get("targets");
            List<String> stages = (List<String>) plan.get("stages");
            String stage = stages.contains("http-request") ? "RECON"
                    : stages.contains("http-head") ? "SERVICE_PROBE"
                    : stages.contains("tcp-exchange") ? "SERVICE_PROBE"
                    : targets.get(0).containsKey("targetId") ? "REACHABILITY" : "PORT_SCAN";
            startedStages.add(stage);
            String taskId = "child-" + tasks.size();
            tasks.put(taskId, Map.of("stage", stage, "targets", new ArrayList<>(targets)));
            return Map.of("code", 200, "taskId", taskId);
        }

        @Override
        public synchronized Map<String, Object> queryNetworkProbe(String taskId, long cursor,
                                                                    int maxItems, int maxBytes,
                                                                    boolean includeEvidence) {
            return queryNetworkProbe(taskId);
        }

        @SuppressWarnings("unchecked")
        public synchronized Map<String, Object> queryNetworkProbe(String taskId) {
            Map<String, Object> task = tasks.get(taskId);
            String stage = String.valueOf(task.get("stage"));
            List<Map<String, Object>> targets = (List<Map<String, Object>>) task.get("targets");
            List<Map<String, Object>> observations = new ArrayList<>();
            for (Map<String, Object> target : targets) {
                Map<String, Object> observation = new LinkedHashMap<>(target);
                observation.put("state", "open");
                if ("REACHABILITY".equals(stage)) {
                    observation.put("stage", "tcp-connect");
                    if (!reachable || Integer.valueOf(80).equals(target.get("port"))) {
                        observation.put("state", reachable ? "open" : "closed");
                    } else {
                        observation.put("state", "closed");
                    }
                } else if ("PORT_SCAN".equals(stage)) {
                    observation.put("stage", "tcp-connect");
                    observation.put("state", openPorts.contains(target.get("port")) ? "open" : "closed");
                } else if ("SERVICE_PROBE".equals(stage)) {
                    if ("http-head".equals(target.get("stage"))) {
                        observation.put("stage", "http-head");
                        if (Integer.valueOf(18080).equals(target.get("port"))
                                && "http".equals(target.get("protocol"))) {
                            observation.put("state", "error");
                            observation.put("error", "plaintext request rejected");
                        } else {
                            observation.put("evidence", Map.of(
                                    "statusCode", "https".equals(target.get("protocol")) ? 200 : 405,
                                    "server", "test-server"));
                        }
                    } else {
                        observation.put("stage", "tcp-exchange");
                        if (Integer.valueOf(5432).equals(target.get("port"))) {
                            observation.put("evidence", Map.of("banner", "PostgreSQL 16.0"));
                        } else if (Integer.valueOf(80).equals(target.get("port"))) {
                            observation.put("evidence", Map.of("statusCode", 200, "server", "test-server"));
                        }
                    }
                } else {
                    observation.put("stage", "http-request");
                    observation.put("evidence", Map.of("statusCode", 200, "body", "nginx"));
                }
                observations.add(observation);
            }
            return Map.of("code", 200, "result", Map.of(
                    "status", "STOPPED",
                    "outcome", "COMPLETED",
                    "total", targets.size(),
                    "completed", targets.size(),
                    "progress", 100,
                    "targets", targets,
                    "observations", observations,
                    "errors", List.of()));
        }

        @Override public Map<String, Object> pauseNetworkProbe(String taskId) { return Map.of("code", 200); }
        @Override public Map<String, Object> resumeNetworkProbe(String taskId) { return Map.of("code", 200); }
        @Override public Map<String, Object> stopNetworkProbe(String taskId) { return Map.of("code", 200); }
        @Override public Map<String, Object> releaseNetworkProbe(String taskId) { return Map.of("code", 200); }
    }

    private static final class ControlledWorkflowNode implements NetworkProbeCapable {
        private final CountDownLatch started = new CountDownLatch(1);
        private final AtomicInteger pauseCalls = new AtomicInteger();
        private final AtomicInteger resumeCalls = new AtomicInteger();
        private final AtomicInteger stopCalls = new AtomicInteger();
        private volatile boolean stopped;

        @Override
        public Map<String, Object> startNetworkProbe(Map<String, Object> plan) {
            started.countDown();
            return Map.of("code", 200, "taskId", "controlled-child");
        }

        @Override
        public Map<String, Object> queryNetworkProbe(String taskId, long cursor,
                                                      int maxItems, int maxBytes,
                                                      boolean includeEvidence) {
            return queryNetworkProbe(taskId);
        }

        public Map<String, Object> queryNetworkProbe(String taskId) {
            if (!stopped) {
                return Map.of("code", 200, "result", Map.of(
                        "status", "RUNNING", "outcome", "RUNNING", "total", 1,
                        "completed", 0, "progress", 0, "observations", List.of(), "errors", List.of()));
            }
            return Map.of("code", 200, "result", Map.of(
                    "status", "STOPPED", "outcome", "COMPLETED", "total", 1,
                    "completed", 1, "progress", 100, "observations", List.of(), "errors", List.of()));
        }

        @Override
        public Map<String, Object> pauseNetworkProbe(String taskId) {
            pauseCalls.incrementAndGet();
            return Map.of("code", 200);
        }

        @Override
        public Map<String, Object> resumeNetworkProbe(String taskId) {
            resumeCalls.incrementAndGet();
            return Map.of("code", 200);
        }

        @Override
        public Map<String, Object> stopNetworkProbe(String taskId) {
            stopped = true;
            stopCalls.incrementAndGet();
            return Map.of("code", 200);
        }

        @Override
        public Map<String, Object> releaseNetworkProbe(String taskId) {
            return Map.of("code", 200);
        }
    }

    private static final class CancelledWorkflowNode implements NetworkProbeCapable {
        private final List<String> startedStages = new ArrayList<>();

        @Override
        @SuppressWarnings("unchecked")
        public synchronized Map<String, Object> startNetworkProbe(Map<String, Object> plan) {
            List<String> stages = (List<String>) plan.get("stages");
            startedStages.add(stages.contains("tcp-connect") ? "REACHABILITY" : "OTHER");
            return Map.of("code", 200, "taskId", "cancelled-stage-child");
        }

        @Override
        public Map<String, Object> queryNetworkProbe(String taskId, long cursor,
                                                      int maxItems, int maxBytes,
                                                      boolean includeEvidence) {
            return queryNetworkProbe(taskId);
        }

        public Map<String, Object> queryNetworkProbe(String taskId) {
            return Map.of("code", 200, "result", Map.of(
                    "status", "STOPPED", "outcome", "CANCELLED", "total", 1,
                    "completed", 0, "progress", 0, "observations", List.of(), "errors", List.of()));
        }

        @Override public Map<String, Object> pauseNetworkProbe(String taskId) { return Map.of("code", 200); }
        @Override public Map<String, Object> resumeNetworkProbe(String taskId) { return Map.of("code", 200); }
        @Override public Map<String, Object> stopNetworkProbe(String taskId) { return Map.of("code", 200); }
        @Override public Map<String, Object> releaseNetworkProbe(String taskId) { return Map.of("code", 200); }
    }

    private static final class StubFingerprintManageService extends FingerprintManageService {
        private final Map<String, HashMap<String, Object>> fingerprints = Map.of(
                "web_any", fingerprint("web_any", "nginx", "http", List.of("web", "server")));

        @Override
        public List<Map<String, Object>> listFingerprints() {
            return fingerprints.values().stream().map(value -> Map.<String, Object>of(
                    "fingerprintId", value.get("fingerprintId"), "protocol", value.get("protocol"),
                    "name", value.get("name"), "tags", value.get("tags"))).toList();
        }

        @Override
        public HashMap<String, Object> getFingerprintById(String fingerprintId) {
            HashMap<String, Object> fingerprint = fingerprints.get(fingerprintId);
            if (fingerprint == null) throw new FingerprintNotFoundException("指纹不存在: " + fingerprintId);
            return new HashMap<>(fingerprint);
        }

        private static HashMap<String, Object> fingerprint(String id, String name, String protocol,
                                                            List<String> tags) {
            HashMap<String, Object> fingerprint = new HashMap<>();
            fingerprint.put("fingerprintId", id);
            fingerprint.put("name", name);
            fingerprint.put("protocol", protocol);
            fingerprint.put("tags", tags);
            fingerprint.put("rule", Map.of(
                    "requests", List.of(Map.of("body", "")),
                    "match", Map.of("field", "body", "operator", "contains", "value", name)));
            return fingerprint;
        }
    }
}
