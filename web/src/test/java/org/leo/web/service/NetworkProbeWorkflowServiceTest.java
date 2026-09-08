package org.leo.web.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.leo.core.puppet.capability.NetworkProbeCapable;
import org.leo.service.fingerprint.FingerprintManageService;

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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    void runsReachabilityPortServiceAndReconAsOneTask() throws Exception {
        WorkflowNode node = new WorkflowNode();
        String taskId = String.valueOf(service.start("session-1", node, Map.of(
                "hosts", List.of("host-a"),
                "ports", List.of(80, 22),
                "timeout", 1000,
                "threads", 4,
                "probeServices", true,
                "ruleSelector", Map.of())).get("taskId"));

        Map<String, Object> snapshot = awaitTerminal(taskId);
        List<?> stages = (List<?>) snapshot.get("stages");

        assertEquals("COMPLETED", snapshot.get("outcome"), snapshot.toString());
        assertEquals(List.of("REACHABILITY", "PORT_SCAN", "SERVICE_PROBE", "RECON"),
                stages.stream().map(stage -> ((Map<?, ?>) stage).get("name")).toList());
        assertTrue(stages.stream().allMatch(stage -> "COMPLETED".equals(((Map<?, ?>) stage).get("status"))));
        assertEquals(List.of("REACHABILITY", "PORT_SCAN", "SERVICE_PROBE", "RECON"), node.startedStages);
        assertEquals(List.of("host-a"), snapshot.get("reachableHostList"));
        assertEquals(1, ((List<?>) snapshot.get("openPortResults")).size());
        assertEquals("http", ((Map<?, ?>) ((List<?>) snapshot.get("openPortResults")).get(0)).get("service"));
        assertEquals(1, ((Map<?, ?>) snapshot.get("reconAnalysis")).get("hitCount"));
    }

    @Test
    void skipsDependentStagesWhenNoHostIsReachable() throws Exception {
        WorkflowNode node = new WorkflowNode();
        node.reachable = false;
        String taskId = String.valueOf(service.start("session-1", node, Map.of(
                "hosts", List.of("host-a"),
                "ports", List.of(80),
                "probeServices", true,
                "ruleSelector", Map.of())).get("taskId"));

        Map<String, Object> snapshot = awaitTerminal(taskId);
        List<?> stages = (List<?>) snapshot.get("stages");

        assertEquals("COMPLETED", snapshot.get("outcome"), snapshot.toString());
        assertEquals("COMPLETED", ((Map<?, ?>) stages.get(0)).get("status"));
        assertEquals("SKIPPED", ((Map<?, ?>) stages.get(1)).get("status"));
        assertEquals("SKIPPED", ((Map<?, ?>) stages.get(2)).get("status"));
        assertEquals("SKIPPED", ((Map<?, ?>) stages.get(3)).get("status"));
        assertTrue(node.startedStages.size() == 1);
    }

    @Test
    void quickDepthSkipsServiceAndReconStages() throws Exception {
        WorkflowNode node = new WorkflowNode();
        String taskId = String.valueOf(service.start("session-1", node, Map.of(
                "hosts", List.of("host-a"),
                "ports", List.of(80),
                "probeServices", false,
                "ruleSelector", Map.of())).get("taskId"));

        Map<String, Object> snapshot = awaitTerminal(taskId);
        List<?> stages = (List<?>) snapshot.get("stages");

        assertEquals("COMPLETED", snapshot.get("outcome"), snapshot.toString());
        assertEquals(List.of("REACHABILITY", "PORT_SCAN"), node.startedStages);
        assertEquals("SKIPPED", ((Map<?, ?>) stages.get(2)).get("status"));
        assertEquals("SKIPPED", ((Map<?, ?>) stages.get(3)).get("status"));
    }

    @Test
    void preservesCancelledStageOutcomeAndDoesNotRunDependentStages() throws Exception {
        CancelledWorkflowNode node = new CancelledWorkflowNode();
        String taskId = String.valueOf(service.start("session-1", node, Map.of(
                "hosts", List.of("host-a"),
                "ports", List.of(80),
                "probeServices", true,
                "ruleSelector", Map.of())).get("taskId"));

        Map<String, Object> snapshot = awaitTerminal(taskId);

        assertEquals("CANCELLED", snapshot.get("outcome"), snapshot.toString());
        assertEquals(List.of("REACHABILITY"), node.startedStages);
    }

    @Test
    void controlsTheActiveStageAndChildTask() throws Exception {
        ControlledWorkflowNode node = new ControlledWorkflowNode();
        String taskId = String.valueOf(service.start("session-1", node, Map.of(
                "hosts", List.of("host-a"),
                "ports", List.of(80),
                "probeServices", true,
                "ruleSelector", Map.of())).get("taskId"));

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
        private boolean reachable = true;

        @Override
        public Map<String, Object> networkProbeCapabilities() {
            return Map.of("code", 200);
        }

        @Override
        @SuppressWarnings("unchecked")
        public synchronized Map<String, Object> startNetworkProbe(Map<String, Object> plan) {
            List<Map<String, Object>> targets = (List<Map<String, Object>>) plan.get("targets");
            List<String> stages = (List<String>) plan.get("stages");
            String stage = stages.contains("http-request") ? "RECON"
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
                    observation.put("state", Integer.valueOf(80).equals(target.get("port")) ? "open" : "closed");
                } else if ("SERVICE_PROBE".equals(stage)) {
                    observation.put("stage", "tcp-exchange");
                    observation.put("evidence", Map.of("statusCode", 200, "server", "nginx"));
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
        public Map<String, Object> networkProbeCapabilities() {
            return Map.of("code", 200);
        }

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
        public Map<String, Object> networkProbeCapabilities() {
            return Map.of("code", 200);
        }

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
                "nginx_any", fingerprint("nginx_any", "nginx", "http", List.of("web", "server")));

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
