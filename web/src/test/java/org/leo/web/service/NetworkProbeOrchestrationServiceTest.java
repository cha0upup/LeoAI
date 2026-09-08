package org.leo.web.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.leo.core.puppet.capability.NetworkProbeCapable;
import org.leo.web.exception.ApiException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NetworkProbeOrchestrationServiceTest {

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final NetworkProbeOrchestrationService service =
            new NetworkProbeOrchestrationService(executor, 10L);

    @AfterEach
    void closeService() {
        service.close();
    }

    @Test
    void partitionsLargePlansAndAggregatesOneLogicalSnapshot() throws Exception {
        ImmediateNode node = new ImmediateNode();
        List<Map<String, Object>> targets = new ArrayList<>();
        for (int index = 0; index < 260; index++) {
            targets.add(Map.of("host", "127.0.0.1", "port", 1000 + index, "protocol", "tcp"));
        }
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("targets", targets);
        plan.put("stages", List.of("tcp-connect"));
        plan.put("limits", Map.of("threads", 64, "timeout", 1000));

        Map<String, Object> started = service.start("session-1", node, plan);
        String taskId = String.valueOf(started.get("taskId"));
        Map<String, Object> snapshot = awaitTerminal(taskId);

        assertEquals("COMPLETED", snapshot.get("outcome"));
        assertEquals(260, snapshot.get("total"));
        assertEquals(260, snapshot.get("completed"));
        assertEquals(100, snapshot.get("progress"));
        assertEquals(3, snapshot.get("batchCount"));
        assertEquals(List.of(128, 128, 4), node.batchSizes);
        assertEquals(3, node.released.size());
        assertEquals(260, ((List<?>) snapshot.get("observations")).size());
        assertEquals(64, ((Map<?, ?>) node.startedPlans.get(0).get("limits")).get("threads"));
        assertEquals(4, ((Map<?, ?>) node.startedPlans.get(2).get("limits")).get("threads"));
    }

    @Test
    void keepsLogicalTasksIsolatedBySession() {
        Map<String, Object> plan = Map.of(
                "targets", List.of(Map.of("host", "127.0.0.1", "port", 80)),
                "stages", List.of("tcp-connect"));
        String taskId = String.valueOf(service.start("session-1", new ImmediateNode(), plan).get("taskId"));

        ApiException error = assertThrows(ApiException.class,
                () -> service.query("session-2", taskId));
        assertTrue(error.getMessage().contains("不属于当前会话"));
        assertEquals(404, error.getCode());
    }

    @Test
    void stopCollectsTheFinalSnapshotAndReleasesTheChild() throws Exception {
        StoppableNode node = new StoppableNode(false);
        String taskId = startSingleTarget(node);
        assertTrue(node.started.await(1L, TimeUnit.SECONDS));

        service.stop("session-1", taskId);
        Map<String, Object> snapshot = result(service.query("session-1", taskId));

        assertEquals("STOPPED", snapshot.get("status"));
        assertEquals("CANCELLED", snapshot.get("outcome"));
        assertEquals(1, snapshot.get("completed"));
        assertEquals(1, ((List<?>) snapshot.get("observations")).size());
        assertEquals(1, node.releaseCount.get());
    }

    @Test
    void stopKeepsCancelledOutcomeWhenTheFinalNodeQueryFails() throws Exception {
        StoppableNode node = new StoppableNode(true);
        String taskId = startSingleTarget(node);
        assertTrue(node.started.await(1L, TimeUnit.SECONDS));

        service.stop("session-1", taskId);
        Map<String, Object> snapshot = result(service.query("session-1", taskId));

        assertEquals("STOPPED", snapshot.get("status"));
        assertEquals("CANCELLED", snapshot.get("outcome"));
        assertEquals(1, node.releaseCount.get());
    }

    @Test
    void propagatesFailedChildOutcomeToTheLogicalTask() throws Exception {
        String taskId = startSingleTarget(new FailedNode());

        Map<String, Object> snapshot = awaitTerminal(taskId);

        assertEquals("FAILED", snapshot.get("outcome"));
        assertEquals(1, ((List<?>) snapshot.get("errors")).size());
    }

    @Test
    void preservesCancelledChildOutcomeOnTheLogicalTask() throws Exception {
        String taskId = startSingleTarget(new CancelledNode());

        Map<String, Object> snapshot = awaitTerminal(taskId);

        assertEquals("CANCELLED", snapshot.get("outcome"));
    }

    @Test
    void acknowledgesIncrementalPagesWithoutRepeatingThemToTheWorkflow() throws Exception {
        String taskId = startSingleTarget(new IncrementalNode());
        awaitTerminal(taskId);

        Map<String, Object> firstPage = result(service.querySince("session-1", taskId, 0L, 0L));
        assertEquals(2, ((List<?>) firstPage.get("observations")).size());
        assertEquals(2L, firstPage.get("nextCursor"));

        service.acknowledge("session-1", taskId, 2L, 0L);
        Map<String, Object> afterAck = result(service.querySince("session-1", taskId, 2L, 0L));
        assertTrue(((List<?>) afterAck.get("observations")).isEmpty());
        assertEquals(2L, afterAck.get("nextCursor"));
    }

    private String startSingleTarget(NetworkProbeCapable node) {
        Map<String, Object> plan = Map.of(
                "targets", List.of(Map.of("host", "127.0.0.1", "port", 80)),
                "stages", List.of("tcp-connect"));
        return String.valueOf(service.start("session-1", node, plan).get("taskId"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> result(Map<String, Object> response) {
        return (Map<String, Object>) response.get("result");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> awaitTerminal(String taskId) throws Exception {
        Map<String, Object> snapshot = Map.of();
        for (int attempt = 0; attempt < 100; attempt++) {
            snapshot = (Map<String, Object>) service.query("session-1", taskId).get("result");
            if ("STOPPED".equals(snapshot.get("status"))) return snapshot;
            Thread.sleep(10L);
        }
        throw new AssertionError("logical task did not stop: " + snapshot);
    }

    private static final class ImmediateNode implements NetworkProbeCapable {
        private final List<Integer> batchSizes = new ArrayList<>();
        private final List<Map<String, Object>> startedPlans = new ArrayList<>();
        private final List<String> released = new ArrayList<>();
        private final Map<String, List<Map<String, Object>>> tasks = new LinkedHashMap<>();

        @Override
        public Map<String, Object> networkProbeCapabilities() {
            return Map.of("code", 200);
        }

        @Override
        @SuppressWarnings("unchecked")
        public synchronized Map<String, Object> startNetworkProbe(Map<String, Object> plan) {
            List<Map<String, Object>> targets = (List<Map<String, Object>>) plan.get("targets");
            String taskId = "child-" + (tasks.size() + 1);
            batchSizes.add(targets.size());
            startedPlans.add(plan);
            tasks.put(taskId, new ArrayList<>(targets));
            return Map.of("code", 200, "taskId", taskId);
        }

        @Override
        public synchronized Map<String, Object> queryNetworkProbe(String taskId, long cursor,
                                                                    int maxItems, int maxBytes,
                                                                    boolean includeEvidence) {
            return queryNetworkProbe(taskId);
        }

        public synchronized Map<String, Object> queryNetworkProbe(String taskId) {
            List<Map<String, Object>> targets = tasks.get(taskId);
            List<Map<String, Object>> observations = targets.stream().map(target -> Map.<String, Object>of(
                    "host", target.get("host"),
                    "port", target.get("port"),
                    "stage", "tcp-connect",
                    "state", "open")).toList();
            return Map.of("code", 200, "result", Map.of(
                    "status", "STOPPED",
                    "completed", targets.size(),
                    "observations", observations,
                    "errors", List.of()));
        }

        @Override
        public Map<String, Object> pauseNetworkProbe(String taskId) {
            return Map.of("code", 200);
        }

        @Override
        public Map<String, Object> resumeNetworkProbe(String taskId) {
            return Map.of("code", 200);
        }

        @Override
        public Map<String, Object> stopNetworkProbe(String taskId) {
            return Map.of("code", 200);
        }

        @Override
        public synchronized Map<String, Object> releaseNetworkProbe(String taskId) {
            released.add(taskId);
            return Map.of("code", 200);
        }
    }

    private static final class StoppableNode implements NetworkProbeCapable {
        private final boolean failQueryAfterStop;
        private final CountDownLatch started = new CountDownLatch(1);
        private final AtomicInteger releaseCount = new AtomicInteger();
        private volatile boolean stopped;

        private StoppableNode(boolean failQueryAfterStop) {
            this.failQueryAfterStop = failQueryAfterStop;
        }

        @Override
        public Map<String, Object> networkProbeCapabilities() {
            return Map.of("code", 200);
        }

        @Override
        public Map<String, Object> startNetworkProbe(Map<String, Object> plan) {
            started.countDown();
            return Map.of("code", 200, "taskId", "child-1");
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
                        "status", "RUNNING", "completed", 0,
                        "observations", List.of(), "errors", List.of()));
            }
            if (failQueryAfterStop) return Map.of("code", 500, "msg", "final query failed");
            return Map.of("code", 200, "result", Map.of(
                    "status", "STOPPED", "completed", 1,
                    "observations", List.of(Map.of("stage", "tcp-connect", "state", "open")),
                    "errors", List.of()));
        }

        @Override
        public Map<String, Object> pauseNetworkProbe(String taskId) {
            return Map.of("code", 200);
        }

        @Override
        public Map<String, Object> resumeNetworkProbe(String taskId) {
            return Map.of("code", 200);
        }

        @Override
        public Map<String, Object> stopNetworkProbe(String taskId) {
            stopped = true;
            return Map.of("code", 200);
        }

        @Override
        public Map<String, Object> releaseNetworkProbe(String taskId) {
            releaseCount.incrementAndGet();
            return Map.of("code", 200);
        }
    }

    private static final class FailedNode implements NetworkProbeCapable {
        @Override
        public Map<String, Object> networkProbeCapabilities() {
            return Map.of("code", 200);
        }

        @Override
        public Map<String, Object> startNetworkProbe(Map<String, Object> plan) {
            return Map.of("code", 200, "taskId", "failed-child");
        }

        @Override
        public Map<String, Object> queryNetworkProbe(String taskId, long cursor,
                                                      int maxItems, int maxBytes,
                                                      boolean includeEvidence) {
            return queryNetworkProbe(taskId);
        }

        public Map<String, Object> queryNetworkProbe(String taskId) {
            return Map.of("code", 200, "result", Map.of(
                    "status", "STOPPED", "outcome", "FAILED",
                    "completed", 0, "observations", List.of(), "errors", List.of()));
        }

        @Override
        public Map<String, Object> pauseNetworkProbe(String taskId) {
            return Map.of("code", 200);
        }

        @Override
        public Map<String, Object> resumeNetworkProbe(String taskId) {
            return Map.of("code", 200);
        }

        @Override
        public Map<String, Object> stopNetworkProbe(String taskId) {
            return Map.of("code", 200);
        }

        @Override
        public Map<String, Object> releaseNetworkProbe(String taskId) {
            return Map.of("code", 200);
        }
    }

    private static final class CancelledNode implements NetworkProbeCapable {
        @Override
        public Map<String, Object> networkProbeCapabilities() {
            return Map.of("code", 200);
        }

        @Override
        public Map<String, Object> startNetworkProbe(Map<String, Object> plan) {
            return Map.of("code", 200, "taskId", "cancelled-child");
        }

        @Override
        public Map<String, Object> queryNetworkProbe(String taskId, long cursor,
                                                      int maxItems, int maxBytes,
                                                      boolean includeEvidence) {
            return queryNetworkProbe(taskId);
        }

        public Map<String, Object> queryNetworkProbe(String taskId) {
            return Map.of("code", 200, "result", Map.of(
                    "status", "STOPPED", "outcome", "CANCELLED",
                    "completed", 0, "observations", List.of(), "errors", List.of()));
        }

        @Override public Map<String, Object> pauseNetworkProbe(String taskId) { return Map.of("code", 200); }
        @Override public Map<String, Object> resumeNetworkProbe(String taskId) { return Map.of("code", 200); }
        @Override public Map<String, Object> stopNetworkProbe(String taskId) { return Map.of("code", 200); }
        @Override public Map<String, Object> releaseNetworkProbe(String taskId) { return Map.of("code", 200); }
    }

    private static final class IncrementalNode implements NetworkProbeCapable {
        @Override
        public Map<String, Object> networkProbeCapabilities() {
            return Map.of("code", 200);
        }

        @Override
        public Map<String, Object> startNetworkProbe(Map<String, Object> plan) {
            return Map.of("code", 200, "taskId", "incremental-child");
        }

        @Override
        public Map<String, Object> queryNetworkProbe(String taskId, long cursor,
                                                      int maxItems, int maxBytes,
                                                      boolean includeEvidence) {
            List<Map<String, Object>> observations = List.of(
                    Map.of("host", "127.0.0.1", "port", 80, "stage", "tcp-connect", "state", "open"),
                    Map.of("host", "127.0.0.1", "port", 443, "stage", "tcp-connect", "state", "open"));
            return Map.of("code", 200, "result", Map.of(
                    "status", "STOPPED", "outcome", "COMPLETED", "completed", 2,
                    "cursor", cursor, "nextCursor", 2L, "hasMore", false,
                    "incremental", true, "observations", cursor == 0L ? observations : List.of(),
                    "errors", List.of()));
        }

        @Override public Map<String, Object> pauseNetworkProbe(String taskId) { return Map.of("code", 200); }
        @Override public Map<String, Object> resumeNetworkProbe(String taskId) { return Map.of("code", 200); }
        @Override public Map<String, Object> stopNetworkProbe(String taskId) { return Map.of("code", 200); }
        @Override public Map<String, Object> releaseNetworkProbe(String taskId) { return Map.of("code", 200); }
    }
}
