package org.leo.web.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.leo.core.agent.capability.NetworkProbeCapable;
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
import static org.junit.jupiter.api.Assertions.assertNotSame;
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
            targets.add(Map.of("host", "127.0.0.1", "port", 1000 + index, "protocol", "tcp",
                    "metadata", Map.of("labels", List.of("fixture"))));
        }
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("targets", targets);
        plan.put("stages", List.of("tcp-connect"));
        plan.put("limits", Map.of("threads", 64, "timeout", 1000,
                "metadata", Map.of("labels", List.of("fixture"))));

        Map<String, Object> started = service.start("session-1", node, plan);
        String taskId = String.valueOf(started.get("taskId"));
        Map<String, Object> snapshot = awaitTerminal(taskId);

        assertEquals("COMPLETED", snapshot.get("outcome"));
        assertEquals(260, snapshot.get("total"));
        assertEquals(260, snapshot.get("completed"));
        assertEquals(100, snapshot.get("progress"));
        assertEquals(1, snapshot.get("batchCount"));
        assertEquals(List.of(260), node.batchSizes);
        assertEquals(1, node.released.size());
        assertEquals(260, ((List<?>) snapshot.get("observations")).size());
        assertEquals(64, ((Map<?, ?>) node.startedPlans.get(0).get("limits")).get("threads"));
        var logicalTarget = (Map<?, ?>) ((List<?>) snapshot.get("targets")).get(0);
        var childTarget = (Map<?, ?>) ((List<?>) node.startedPlans.get(0).get("targets")).get(0);
        assertNotSame(targets.get(0), logicalTarget);
        assertNotSame(targets.get(0).get("metadata"), logicalTarget.get("metadata"));
        assertNotSame(logicalTarget, childTarget);
        assertNotSame(logicalTarget.get("metadata"), childTarget.get("metadata"));
        var publicPlan = (Map<?, ?>) snapshot.get("plan");
        var childLimits = (Map<?, ?>) node.startedPlans.get(0).get("limits");
        assertNotSame(((Map<?, ?>) plan.get("limits")).get("metadata"), childLimits.get("metadata"));
        assertNotSame(childLimits.get("metadata"), publicPlan.get("metadata"));
        ((List<?>) ((Map<?, ?>) publicPlan.get("metadata")).get("labels")).clear();
        var refreshed = (Map<?, ?>) result(service.query("session-1", taskId)).get("plan");
        assertEquals(Map.of("labels", List.of("fixture")), refreshed.get("metadata"));
    }

    @Test
    void usesTheNodesActualWorkerCapacityForAdmission() throws Exception {
        ImmediateNode node = new ImmediateNode() {
            @Override public int networkProbeWorkerLimit() { return 16; }
        };
        List<Map<String, Object>> targets = java.util.stream.IntStream.range(0, 32)
                .mapToObj(index -> Map.<String, Object>of("host", "127.0.0.1", "port", 1000 + index)).toList();
        String id = String.valueOf(service.start("session-1", node,
                Map.of("targets", targets, "limits", Map.of("threads", 256))).get("taskId"));
        assertEquals("COMPLETED", awaitTerminal(id).get("outcome"));
        assertEquals(16, ((Map<?, ?>)node.startedPlans.get(0).get("limits")).get("threads"));
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
        IncrementalNode node = new IncrementalNode();
        String taskId = startSingleTarget(node);
        awaitTerminal(taskId);
        assertEquals(List.of(0L, 1L), node.queryAcks);
        assertEquals(List.of(2L), node.finalAcks);

        Map<String, Object> firstPage = result(service.querySince("session-1", taskId, 0L, 0L));
        assertEquals(2, ((List<?>) firstPage.get("observations")).size());
        assertEquals(2L, firstPage.get("nextCursor"));
        var full = result(service.query("session-1", taskId));
        for (String key : List.of("taskId", "scanKind", "status", "outcome", "total", "completed", "progress",
                "batchCount", "batchIndex", "createdAt", "finishedAt", "backpressured", "nodeBackpressured", "pendingItems", "pendingBytes"))
            assertEquals(full.get(key), firstPage.get(key), key);

        service.acknowledge("session-1", taskId, 2L, 0L);
        Map<String, Object> afterAck = result(service.querySince("session-1", taskId, 2L, 0L));
        assertTrue(((List<?>) afterAck.get("observations")).isEmpty());
        assertEquals(2L, afterAck.get("nextCursor"));
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void backpressureBoundsResultsAndResumesOnlyAfterConsumerAck(boolean large) throws Exception {
        BurstNode node = new BurstNode(large);
        String taskId = startSingleTarget(node);
        Map<String, Object> snapshot = awaitPressure(taskId);
        int calls = node.queries.get();
        Thread.sleep(50);
        assertEquals(calls, node.queries.get(), "backlog must stop polling/ACKing the node");
        assertEquals("RUNNING", snapshot.get("status"));
        assertTrue(((Number) snapshot.get("pendingItems")).intValue() <= 4096);
        assertTrue(((Number) snapshot.get("pendingBytes")).longValue() <= 8L * 1024 * 1024);
        long cursor = 0, errors = 0;
        int received = 0, errorCount = 0;
        for (int attempt = 0; attempt < 300; attempt++) {
            Map<String, Object> page = result(service.querySince("session-1", taskId, cursor, errors));
            received += ((List<?>) page.get("observations")).size();
            errorCount += ((List<?>) page.get("errors")).size();
            cursor = ((Number) page.get("nextCursor")).longValue();
            errors = ((Number) page.get("nextErrorCursor")).longValue();
            service.acknowledge("session-1", taskId, cursor, errors);
            if ("STOPPED".equals(page.get("status")) && !Boolean.TRUE.equals(page.get("hasMore"))) break;
            Thread.sleep(5);
        }
        assertEquals(node.total, received);
        assertEquals(1, errorCount, "ACKed diagnostic summaries must not reappear on every node poll");
        Map<String, Object> finalPage = result(service.querySince("session-1", taskId, cursor, errors));
        assertEquals("COMPLETED", finalPage.get("outcome"));
        assertEquals(false, finalPage.get("backpressured"));
        assertEquals(0L, finalPage.get("pendingBytes"));
    }

    @Test
    void stopReleasesChildWhileTheResultBufferIsBackpressured() throws Exception {
        BurstNode node = new BurstNode(true);
        String taskId = startSingleTarget(node);
        awaitPressure(taskId);
        service.stop("session-1", taskId);
        assertEquals("CANCELLED", result(service.query("session-1", taskId)).get("outcome"));
        assertEquals(1, node.releases.get());
    }

    private Map<String, Object> awaitPressure(String taskId) throws Exception {
        for (int attempt = 0; attempt < 200; attempt++) {
            Map<String, Object> page = result(service.querySince("session-1", taskId, 0L, 0L));
            if (Boolean.TRUE.equals(page.get("backpressured"))) return page;
            Thread.sleep(5);
        }
        throw new AssertionError("producer did not reach high water");
    }

    private static final class BurstNode implements NetworkProbeCapable {
        private final boolean large;
        private final int total;
        private final AtomicInteger queries = new AtomicInteger();
        private final AtomicInteger releases = new AtomicInteger();
        private volatile boolean stopped;
        private BurstNode(boolean large) { this.large = large; this.total = large ? 320 : 4000; }
        public Map<String, Object> startNetworkProbe(Map<String, Object> plan) { return Map.of("code", 200, "taskId", "burst"); }
        public Map<String, Object> queryNetworkProbe(String id, long cursor, long ack, int maxItems, int maxBytes, boolean evidence) {
            queries.incrementAndGet();
            int end = Math.min(total, (int)cursor + (large ? 32 : 128));
            List<Map<String, Object>> rows = new ArrayList<>();
            for (int index = (int)cursor; index < end; index++) rows.add(Map.of("state", "open", "index", index,
                    "evidence", large ? "x".repeat(8192) : ""));
            return Map.of("code", 200, "result", Map.of("status", stopped || end == total ? "STOPPED" : "RUNNING",
                    "outcome", stopped ? "CANCELLED" : "COMPLETED", "completed", end,
                    "cursor", cursor, "nextCursor", (long)end, "hasMore", end < total,
                    "observations", rows, "errors", List.of(Map.of("errorCode", "fixture", "error", "one diagnostic"))));
        }
        public Map<String, Object> pauseNetworkProbe(String id) { return Map.of("code", 200); }
        public Map<String, Object> resumeNetworkProbe(String id) { return Map.of("code", 200); }
        public Map<String, Object> stopNetworkProbe(String id) { stopped = true; return Map.of("code", 200); }
        public Map<String, Object> ackNetworkProbe(String id, long cursor) { return Map.of("code", 200); }
        public Map<String, Object> releaseNetworkProbe(String id) { releases.incrementAndGet(); return Map.of("code", 200); }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void sameNodeRotatesBatchesAndPausedTasksReleaseTheOnlyCoordinator(boolean paused) throws Exception {
        FairNode node = new FairNode();
        List<Map<String, Object>> targets = java.util.stream.IntStream.range(0, 2048)
                .mapToObj(index -> Map.<String, Object>of("host", "127.0.0.1", "port", 1000 + index)).toList();
        String large = String.valueOf(service.start("session-1", node,
                Map.of("targets", targets, "limits", Map.of("threads", 256))).get("taskId"));
        assertTrue(node.firstStarted.await(1, TimeUnit.SECONDS));
        if (paused) service.pause("session-1", large);
        String small = startSingleTarget(node);
        if (!paused) node.allowFirst = true;
        assertEquals("COMPLETED", awaitTerminal(small).get("outcome"));
        assertEquals(List.of(1024, 1), ((ImmediateNode)node).batchSizes.subList(0, 2));
        if (paused) {
            assertEquals("PAUSED", result(service.query("session-1", large)).get("status"));
            node.allowFirst = true; service.resume("session-1", large);
        }
        assertEquals("COMPLETED", awaitTerminal(large).get("outcome"));
        assertEquals(List.of(1024, 1, 1024), ((ImmediateNode)node).batchSizes);
        assertEquals(2048, ((List<?>) result(service.query("session-1", large)).get("observations")).size());
    }

    private static final class FairNode extends ImmediateNode {
        private final CountDownLatch firstStarted = new CountDownLatch(1);
        private volatile boolean allowFirst;
        @Override public synchronized Map<String, Object> startNetworkProbe(Map<String, Object> plan) {
            Map<String, Object> result = super.startNetworkProbe(plan); firstStarted.countDown(); return result;
        }
        @Override public synchronized Map<String, Object> queryNetworkProbe(String id, long cursor, long ack,
                int maxItems, int maxBytes, boolean evidence) {
            if ("child-1".equals(id) && !allowFirst) return Map.of("code", 200, "result", Map.of(
                    "status", "RUNNING", "cursor", cursor, "nextCursor", cursor, "hasMore", false,
                    "observations", List.of(), "errors", List.of()));
            return super.queryNetworkProbe(id, cursor, ack, maxItems, maxBytes, evidence);
        }
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

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cursorPage(Map<String, Object> response, long cursor) {
        if (!Integer.valueOf(200).equals(response.get("code"))) return response;
        Map<String, Object> page = new LinkedHashMap<>((Map<String, Object>) response.get("result"));
        page.put("cursor", cursor);
        page.put("nextCursor", cursor + ((List<?>) page.get("observations")).size());
        page.put("hasMore", false);
        return Map.of("code", 200, "result", page);
    }

    private static class ImmediateNode implements NetworkProbeCapable {
        @Override
        public Map<String, Object> ackNetworkProbe(String taskId, long cursor) {
            return Map.of("code", 200, "cursor", cursor);
        }

        private final List<Integer> batchSizes = new ArrayList<>();
        private final List<Map<String, Object>> startedPlans = new ArrayList<>();
        private final List<String> released = new ArrayList<>();
        private final Map<String, List<Map<String, Object>>> tasks = new LinkedHashMap<>();

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
        public synchronized Map<String, Object> queryNetworkProbe(String taskId, long cursor, long ackCursor,
                                                                    int maxItems, int maxBytes,
                                                                    boolean includeEvidence) {
            return cursorPage(queryNetworkProbe(taskId), cursor);
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
        @Override
        public Map<String, Object> ackNetworkProbe(String taskId, long cursor) {
            return Map.of("code", 200, "cursor", cursor);
        }

        private final boolean failQueryAfterStop;
        private final CountDownLatch started = new CountDownLatch(1);
        private final AtomicInteger releaseCount = new AtomicInteger();
        private volatile boolean stopped;

        private StoppableNode(boolean failQueryAfterStop) {
            this.failQueryAfterStop = failQueryAfterStop;
        }

        @Override
        public Map<String, Object> startNetworkProbe(Map<String, Object> plan) {
            started.countDown();
            return Map.of("code", 200, "taskId", "child-1");
        }

        @Override
        public Map<String, Object> queryNetworkProbe(String taskId, long cursor, long ackCursor,
                                                      int maxItems, int maxBytes,
                                                      boolean includeEvidence) {
            return cursorPage(queryNetworkProbe(taskId), cursor);
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
        public Map<String, Object> ackNetworkProbe(String taskId, long cursor) {
            return Map.of("code", 200, "cursor", cursor);
        }

        @Override
        public Map<String, Object> startNetworkProbe(Map<String, Object> plan) {
            return Map.of("code", 200, "taskId", "failed-child");
        }

        @Override
        public Map<String, Object> queryNetworkProbe(String taskId, long cursor, long ackCursor,
                                                      int maxItems, int maxBytes,
                                                      boolean includeEvidence) {
            return cursorPage(queryNetworkProbe(taskId), cursor);
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
        public Map<String, Object> ackNetworkProbe(String taskId, long cursor) {
            return Map.of("code", 200, "cursor", cursor);
        }

        @Override
        public Map<String, Object> startNetworkProbe(Map<String, Object> plan) {
            return Map.of("code", 200, "taskId", "cancelled-child");
        }

        @Override
        public Map<String, Object> queryNetworkProbe(String taskId, long cursor, long ackCursor,
                                                      int maxItems, int maxBytes,
                                                      boolean includeEvidence) {
            return cursorPage(queryNetworkProbe(taskId), cursor);
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
        private final List<Long> queryAcks = new ArrayList<>();
        private final List<Long> finalAcks = new ArrayList<>();

        @Override
        public Map<String, Object> ackNetworkProbe(String taskId, long cursor) {
            finalAcks.add(cursor);
            return Map.of("code", 200, "cursor", cursor);
        }

        @Override
        public Map<String, Object> startNetworkProbe(Map<String, Object> plan) {
            return Map.of("code", 200, "taskId", "incremental-child");
        }

        @Override
        public Map<String, Object> queryNetworkProbe(String taskId, long cursor, long ackCursor,
                                                      int maxItems, int maxBytes,
                                                      boolean includeEvidence) {
            queryAcks.add(ackCursor);
            List<Map<String, Object>> observations = List.of(
                    Map.of("host", "127.0.0.1", "port", 80, "stage", "tcp-connect", "state", "open"),
                    Map.of("host", "127.0.0.1", "port", 443, "stage", "tcp-connect", "state", "open"));
            return Map.of("code", 200, "result", Map.of(
                    "status", "STOPPED", "outcome", "COMPLETED", "completed", 2,
                    "cursor", cursor, "nextCursor", cursor + 1L, "hasMore", cursor == 0L,
                    "incremental", true, "observations", observations.subList((int) cursor, (int) cursor + 1),
                    "errors", List.of()));
        }

        @Override public Map<String, Object> pauseNetworkProbe(String taskId) { return Map.of("code", 200); }
        @Override public Map<String, Object> resumeNetworkProbe(String taskId) { return Map.of("code", 200); }
        @Override public Map<String, Object> stopNetworkProbe(String taskId) { return Map.of("code", 200); }
        @Override public Map<String, Object> releaseNetworkProbe(String taskId) { return Map.of("code", 200); }
    }
}
