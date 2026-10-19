package org.leo.web.service;

import org.leo.service.discovery.NetworkProbeAnalysisService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.stream.Stream;
import org.leo.core.agent.capability.NetworkProbeCapable;
import org.leo.service.fingerprint.FingerprintManageService;
import org.leo.web.exception.ApiException;
import org.leo.service.discovery.NetworkDiscoveryDtos.*;
import org.leo.service.discovery.PortPolicyResolver;
import org.leo.service.discovery.ScanPlanService;
import org.leo.service.discovery.TargetResolver;

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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
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
    void retainsFailureDetailsAndDoesNotCountTheFailedStageAsCompleted() throws Exception {
        var backend = mock(NetworkProbeOrchestrationService.class);
        when(backend.start(anyString(), any(), anyMap())).thenReturn(Map.of("taskId", "failed-reachability"));
        when(backend.querySince(anyString(), anyString(), anyLong(), anyLong())).thenReturn(new LinkedHashMap<>(Map.of("result", Map.of(
                "status", "STOPPED", "outcome", "FAILED", "total", 32, "completed", 0,
                "observations", List.of(), "errors", List.of(Map.of("error", "worker fixture failure"))))));
        try (var worker = new NetworkProbeWorkflowService(analysis, backend, Executors.newSingleThreadExecutor(), 10)) {
            String id = String.valueOf(worker.start("session-1", new WorkflowNode(), plan(List.of(80), null)).get("taskId"));
            Map<?, ?> result = Map.of();
            for (int attempt = 0; attempt < 200; attempt++) {
                result = (Map<?, ?>) worker.query("session-1", id).get("result");
                if ("STOPPED".equals(result.get("status"))) break;
                Thread.sleep(5);
            }
            assertEquals("FAILED", result.get("outcome"));
            assertEquals("工作流阶段失败: REACHABILITY · worker fixture failure", result.get("error"));
            assertEquals(0, result.get("progress"));
            assertEquals(0, result.get("completedStageCount"));
            assertEquals("FAILED", ((Map<?, ?>) ((List<?>) result.get("stages")).get(0)).get("status"));
        }
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
        var summary = service.querySummaryIfPresent("session-1", taskId);
        assertEquals(((List<?>) snapshot.get("openPortResults")).size(), summary.get("openCount"));
        assertEquals(((List<?>) snapshot.get("serviceResults")).size(), summary.get("serviceCount"));
        assertEquals(((List<?>) snapshot.get("reachableHostList")).size(), summary.get("reachableHostCount"));
        assertEquals(snapshot.get("reachableHostList"), summary.get("reachableHostList"));
        ((List<?>) snapshot.get("reachableHostList")).clear();
        assertEquals(List.of("host-a"), service.querySummaryIfPresent("session-1", taskId).get("reachableHostList"));
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

    @ParameterizedTest
    @ValueSource(ints = {443, 8443})
    void probesUnknownNonStandardPortsForHttpAndSkipsKnownTcpServices(int tlsPort) throws Exception {
        WorkflowNode node = new WorkflowNode();
        node.openPorts = Set.of(80, 18080, 5432, tlsPort);
        node.pageSize = 1;
        String taskId = String.valueOf(service.start("session-1", node, plan(List.of(80, 18080, 5432, tlsPort), null)).get("taskId"));

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
        assertEquals("http-head", web.get("probe"));
        assertEquals("test-server", ((Map<?, ?>) web.get("evidence")).get("server"));
        assertEquals("plaintext request rejected", web.get("probeError"));
        assertEquals("postgresql", postgres.get("service"));
        assertEquals(List.of("REACHABILITY", "PORT_SCAN", "SERVICE_PROBE", "SERVICE_PROBE",
                        "SERVICE_PROBE"),
                node.startedStages);
        List<Map<String, Object>> httpTargets = node.tasks.values().stream()
                .flatMap(child -> ((List<Map<String, Object>>) child.get("targets")).stream())
                .filter(target -> "http-head".equals(target.get("stage"))).toList();
        assertEquals(List.of("http", "https"), httpTargets.stream()
                .filter(target -> Integer.valueOf(18080).equals(target.get("port"))).map(target -> target.get("protocol")).toList());
        assertEquals(List.of("https"), httpTargets.stream()
                .filter(target -> Integer.valueOf(tlsPort).equals(target.get("port"))).map(target -> target.get("protocol")).toList());
        assertTrue(httpTargets.stream().noneMatch(target -> Integer.valueOf(5432).equals(target.get("port"))));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void skipsUnconfirmedHostsForPortScanning(boolean fingerprint) throws Exception {
        WorkflowNode node = new WorkflowNode();
        node.reachable = false;
        String taskId = String.valueOf(service.start("session-1", node, plan(List.of(80), fingerprint
                ? List.of("REACHABILITY", "PORT_SCAN", "SERVICE_PROBE", "FINGERPRINT") : null)).get("taskId"));

        Map<String, Object> snapshot = awaitTerminal(taskId);
        List<?> stages = (List<?>) snapshot.get("stages");

        assertEquals("COMPLETED", snapshot.get("outcome"), snapshot.toString());
        assertEquals("COMPLETED", ((Map<?, ?>) stages.get(0)).get("status"));
        assertEquals("SKIPPED", ((Map<?, ?>) stages.get(1)).get("status"));
        assertEquals("NO_REACHABLE_HOSTS", ((Map<?, ?>) stages.get(1)).get("reason"));
        assertEquals("SKIPPED", ((Map<?, ?>) stages.get(2)).get("status"));
        assertEquals(List.of("REACHABILITY"), node.startedStages);
        assertEquals(1, snapshot.get("unknownHostCount"));
        assertEquals(1, snapshot.get("skippedHostCount"));
        assertEquals(0, snapshot.get("portScanHostCount"));
        assertEquals(List.of(), snapshot.get("openPortResults"));
        if (fingerprint) assertEquals("SKIPPED", ((Map<?, ?>) stages.get(3)).get("status"));
    }

    @Test
    void scansOnlyAnsweredHostsInAMixedDiscoveryAndCountsSkippedHosts() throws Exception {
        WorkflowNode node = new WorkflowNode();
        node.reachabilityErrors = Map.of("host-b", "REFUSED", "host-c", "TIMEOUT", "host-d", "NO_ROUTE");
        String id = String.valueOf(service.start("session-1", node,
                plan(List.of(80, 22), null, List.of("host-a", "host-b", "host-c", "host-d"))).get("taskId"));
        var snapshot = awaitTerminal(id);
        assertEquals("COMPLETED", snapshot.get("outcome"));
        assertEquals(List.of("host-a", "host-b"), snapshot.get("reachableHostList"));
        assertEquals(1, snapshot.get("unknownHostCount"));
        assertEquals(1, snapshot.get("unreachableHostCount"));
        assertEquals(2, snapshot.get("skippedHostCount"));
        List<String> scanned = node.tasks.values().stream().filter(task -> "PORT_SCAN".equals(task.get("stage")))
                .flatMap(task -> ((List<Map<String, Object>>) task.get("targets")).stream())
                .map(target -> String.valueOf(target.get("host"))).distinct().toList();
        assertEquals(List.of("host-a", "host-b"), scanned);
        var reachability = (Map<?, ?>) ((List<?>) snapshot.get("stages")).get(0);
        assertEquals(4, reachability.get("total"));
        assertEquals(4, reachability.get("completed"));
    }

    @Test
    void reusesSuccessfulAndRefusedTcpProbesWithinTheSelectedPortRange() throws Exception {
        WorkflowNode node = new WorkflowNode(); node.javaReachable = false;
        String id = String.valueOf(service.start("session-1", node, plan(List.of(80), null)).get("taskId"));
        var snapshot = awaitTerminal(id);
        assertEquals("COMPLETED", snapshot.get("outcome"));
        assertTrue(!node.startedStages.contains("PORT_SCAN"));
        assertEquals(1, ((List<?>) snapshot.get("openPortResults")).size());
        node = new WorkflowNode(); node.reachabilityErrors = Map.of("host-a", "REFUSED");
        id = String.valueOf(service.start("session-1", node, plan(List.of(80), null)).get("taskId"));
        snapshot = awaitTerminal(id);
        assertEquals(List.of("REACHABILITY"), node.startedStages);
        assertEquals(List.of(), snapshot.get("openPortResults"));
    }

    @Test
    void cancellationDuringCachedResultPersistenceDoesNotCompleteThePortStage() throws Exception {
        var store = mock(NetworkProbeResultStore.class);
        when(store.createTask(anyString(), anyString(), anyString(), anyMap())).thenReturn(true);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        when(store.append(anyString(), anyList(), anyList())).thenAnswer(call -> {
            List<Map<String, Object>> observations = call.getArgument(1);
            if (observations.stream().anyMatch(row -> "reachability-cache".equals(row.get("probeSource")))) {
                entered.countDown(); assertTrue(release.await(2, TimeUnit.SECONDS));
            }
            return true;
        });
        when(store.append(anyString(), anyList(), anyList(), anyList(), anyList())).thenReturn(true);
        service.setResultStore(store);
        var node = new WorkflowNode(); node.javaReachable = false;
        String id = String.valueOf(service.start("session-1", node, plan(List.of(80), null)).get("taskId"));
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        var stopped = java.util.concurrent.CompletableFuture.runAsync(() -> service.stop("session-1", id));
        try { assertEquals("CANCELLED", awaitStatus(id, "STOPPED").get("outcome")); }
        finally { release.countDown(); }
        stopped.get(2, TimeUnit.SECONDS);
        var result = awaitTerminal(id);
        assertEquals("CANCELLED", ((Map<?, ?>) ((List<?>) result.get("stages")).get(1)).get("status"));
        verify(store, timeout(1000)).updateTask(eq(id), eq("STOPPED"), eq("CANCELLED"), eq("PORT_SCAN"),
                org.mockito.ArgumentMatchers.anyInt(), eq(1), isNull(), anyList());
    }

    @Test
    void retainsEarlierServicePagesWhenPersistenceRetriesTheCurrentPage() throws Exception {
        var store = mock(NetworkProbeResultStore.class);
        when(store.createTask(anyString(), anyString(), anyString(), anyMap())).thenReturn(true);
        AtomicInteger serviceWrites = new AtomicInteger();
        when(store.append(anyString(), anyList(), anyList(), anyList(), anyList())).thenAnswer(call -> {
            List<Map<String, Object>> rows = call.getArgument(1);
            boolean servicePage = rows.stream().anyMatch(row -> "SERVICE_PROBE".equals(row.get("workflowStage")));
            return !servicePage || serviceWrites.incrementAndGet() != 2;
        });
        service.setResultStore(store);
        WorkflowNode node = new WorkflowNode();
        node.pageSize = 1;
        node.openPorts = Set.of(80, 5432);
        String id = String.valueOf(service.start("session-1", node,
                plan(List.of(80, 5432), List.of("PORT_SCAN", "SERVICE_PROBE"))).get("taskId"));

        var snapshot = awaitTerminal(id);
        assertEquals("COMPLETED", snapshot.get("outcome"), snapshot.toString());
        List<Map<String, Object>> endpoints = (List<Map<String, Object>>) snapshot.get("serviceResults");
        assertEquals(2, endpoints.size());
        assertEquals(Set.of("http", "postgresql"), endpoints.stream().map(row -> row.get("service")).collect(java.util.stream.Collectors.toSet()));
        assertEquals("test-server", endpoints.stream().filter(row -> Integer.valueOf(80).equals(row.get("port")))
                .findFirst().orElseThrow().get("server"));
        assertTrue(serviceWrites.get() >= 3, "banner and HTTP evidence must survive the persistence retry");
    }

    @Test
    void persistenceFailureStopsAndReleasesTheActiveChild() throws Exception {
        var store = mock(NetworkProbeResultStore.class);
        when(store.createTask(anyString(), anyString(), anyString(), anyMap())).thenReturn(true);
        when(store.append(anyString(), anyList(), anyList(), anyList(), anyList())).thenReturn(false);
        service.setResultStore(store);
        var node = new ControlledWorkflowNode();
        node.emitObservation = true;
        String id = String.valueOf(service.start("session-1", node, plan(List.of(80), List.of("PORT_SCAN"))).get("taskId"));
        assertEquals("FAILED", awaitTerminal(id).get("outcome"));
        verify(store, timeout(1000)).updateTask(eq(id), eq("STOPPED"), eq("FAILED"), isNull(),
                eq(0), eq(1), eq("扫描结果连续持久化失败"), anyList());
        assertTrue(node.stopCalls.get() > 0);
        awaitCount(node.releaseCalls);
    }

    @Test
    void drainsQueuedPagesWithoutWaitingForTheIdlePollInterval() throws Exception {
        var backend = mock(NetworkProbeOrchestrationService.class);
        when(backend.start(anyString(), any(), anyMap())).thenReturn(Map.of("taskId", "pages"));
        CountDownLatch secondPage = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        when(backend.querySince(anyString(), anyString(), anyLong(), anyLong())).thenAnswer(invocation -> {
            int page = calls.incrementAndGet();
            if (page == 2) secondPage.countDown();
            return Map.of("result", Map.of("status", "STOPPED", "outcome", "COMPLETED",
                    "total", 2, "completed", 2, "observations", List.of(), "errors", List.of(), "hasMore", page == 1));
        });
        try (var worker = new NetworkProbeWorkflowService(analysis, backend, Executors.newSingleThreadExecutor(), 2000)) {
            worker.start("session-1", new WorkflowNode(), plan(List.of(80), List.of("PORT_SCAN")));
            assertTrue(secondPage.await(1, TimeUnit.SECONDS), "queued pages should not wait two seconds");
        }
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
    void retainsOpenPortResultsAfterIncrementalStageCompletion() throws Exception {
        WorkflowNode node = new WorkflowNode();
        node.openPorts = Set.of(8080, 9090);
        node.pageSize = 1;
        String taskId = String.valueOf(service.start("session-1", node,
                plan(List.of(8080, 9090, 10000), List.of("PORT_SCAN"))).get("taskId"));
        Map<String, Object> snapshot = awaitTerminal(taskId);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> endpoints = (List<Map<String, Object>>) snapshot.get("openPortResults");
        assertEquals(2, endpoints.size());
        assertEquals("host-a", endpoints.get(0).get("host"));
        assertEquals(List.of(8080, 9090), endpoints.stream().map(endpoint -> endpoint.get("port")).toList());
        var stage = (Map<?, ?>) ((List<?>) snapshot.get("stages")).get(0);
        assertEquals(List.of(8080, 9090), ((Map<?, ?>) stage.get("result")).get("openPortList"));
        endpoints.get(0).clear();
        var refreshed = (Map<?, ?>) service.query("session-1", taskId).get("result");
        assertEquals(8080, ((Map<?, ?>) ((List<?>) refreshed.get("openPortResults")).get(0)).get("port"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"J\n8.0.43\u0000\u0010\u0000caching_sha2_password",
            "10.11.2\r\nsha256_password", "caching_sha2_password"})
    void identifiesBinaryMysqlHandshakeWithoutMysqlWord(String handshake) throws Exception {
        Method method = NetworkProbeWorkflowService.class
                .getDeclaredMethod("detectService", String.class);
        method.setAccessible(true);
        assertEquals(handshake.equals("caching_sha2_password") ? "" : "mysql", method.invoke(null, handshake));
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

    @Test
    void pausedWorkflowsDoNotOccupyTheSingleStageExecutor() throws Exception {
        ControlledWorkflowNode first = new ControlledWorkflowNode(), second = new ControlledWorkflowNode();
        String a = String.valueOf(service.start("session-1", first, plan(List.of(80), List.of("PORT_SCAN"))).get("taskId"));
        assertTrue(first.started.await(1, TimeUnit.SECONDS)); service.pause("session-1", a);
        String b = String.valueOf(service.start("session-1", second, plan(List.of(80), List.of("PORT_SCAN"))).get("taskId"));
        assertTrue(second.started.await(1, TimeUnit.SECONDS)); service.pause("session-1", b);
        String small = String.valueOf(service.start("session-1", new WorkflowNode(), plan(List.of(80), List.of("PORT_SCAN"))).get("taskId"));
        assertEquals("COMPLETED", awaitTerminal(small).get("outcome"));
        assertEquals("PAUSED", service.querySummaryIfPresent("session-1", a).get("status"));
        assertEquals("PAUSED", service.querySummaryIfPresent("session-1", b).get("status"));
        service.resume("session-1", a); awaitCount(first.resumeCalls);
        service.stop("session-1", a); service.stop("session-1", b);
    }

    @Test
    void arrivingResultsWakeTheWorkflowInsteadOfWaitingForTheTwoSecondFallback() throws Exception {
        NetworkProbeCapable node = mock(NetworkProbeCapable.class);
        java.util.concurrent.atomic.AtomicLong started = new java.util.concurrent.atomic.AtomicLong();
        when(node.startNetworkProbe(anyMap())).thenAnswer(call -> {
            started.set(System.nanoTime()); return Map.of("code", 200, "taskId", "event-child");
        });
        when(node.queryNetworkProbe(anyString(), anyLong(), anyLong(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyBoolean())).thenAnswer(call -> {
            long cursor = call.getArgument(1);
            boolean ready = System.nanoTime() - started.get() >= TimeUnit.MILLISECONDS.toNanos(100);
            List<?> rows = ready && cursor == 0 ? List.of(Map.of("host", "host-a", "port", 80, "state", "open", "stage", "tcp-connect")) : List.of();
            return Map.of("code", 200, "result", Map.of("status", ready ? "STOPPED" : "RUNNING",
                    "outcome", ready ? "COMPLETED" : "RUNNING", "completed", ready ? 1 : 0,
                    "cursor", cursor, "nextCursor", cursor + rows.size(), "hasMore", false, "observations", rows, "errors", List.of()));
        });
        when(node.ackNetworkProbe(anyString(), anyLong())).thenReturn(Map.of("code", 200));
        when(node.releaseNetworkProbe(anyString())).thenReturn(Map.of("code", 200));
        try (var workflows = new NetworkProbeWorkflowService(analysis, orchestration, Executors.newSingleThreadExecutor(), 2000)) {
            String id = String.valueOf(workflows.start("session-1", node, plan(List.of(80), List.of("PORT_SCAN"))).get("taskId"));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
            Map<String, Object> snapshot;
            do {
                snapshot = workflows.querySummaryIfPresent("session-1", id);
                if ("COMPLETED".equals(snapshot.get("outcome"))) break;
                Thread.sleep(5);
            } while (System.nanoTime() < deadline);
            assertEquals("COMPLETED", snapshot.get("outcome"));
            assertEquals(1, snapshot.get("openCount"));
        }
    }

    private ScanPlanService.ScanPlan plan(List<Integer> ports, List<String> stages) {
        return plan(ports, stages, List.of("host-a"));
    }

    private ScanPlanService.ScanPlan plan(List<Integer> ports, List<String> stages, List<String> hosts) {
        TargetResolver resolver = mock(TargetResolver.class);
        when(resolver.resolve(any())).thenReturn(hosts.stream().map(host ->
                new ResolvedTarget(host, host, host, null, "tcp", "host", host)).toList());
        ScanPlanService planner = new ScanPlanService(resolver, new PortPolicyResolver(), analysis);
        return planner.plan(new ScanConfig("test workflow", new TargetInput(hosts, List.of()),
                new PortPolicy("custom", List.of(), ports, List.of()),
                new ExecutionConfig(4, 1000), null, stages == null ? List.of("REACHABILITY", "PORT_SCAN", "SERVICE_PROBE") : stages));
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

    private static final class FingerprintControlledNode implements NetworkProbeCapable {
        @Override
        public Map<String, Object> ackNetworkProbe(String taskId, long cursor) {
            return Map.of("code", 200, "cursor", cursor);
        }

        private final WorkflowNode discovery = new WorkflowNode();
        private final ControlledWorkflowNode control = new ControlledWorkflowNode();
        private NetworkProbeCapable node(String id) { return "controlled-child".equals(id) ? control : discovery; }
        public Map<String, Object> startNetworkProbe(Map<String, Object> plan) {
            return ((List<?>) plan.get("stages")).contains("http-request")
                    ? control.startNetworkProbe(plan) : discovery.startNetworkProbe(plan);
        }
        public Map<String, Object> queryNetworkProbe(String id, long cursor, long ackCursor, int items, int bytes, boolean evidence) throws Exception {
            return node(id).queryNetworkProbe(id, cursor, ackCursor, items, bytes, evidence);
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
        @Override
        public Map<String, Object> ackNetworkProbe(String taskId, long cursor) {
            return Map.of("code", 200, "cursor", cursor);
        }

        private final Map<String, Map<String, Object>> tasks = new LinkedHashMap<>();
        private final List<String> startedStages = new ArrayList<>();
        private Set<Integer> openPorts = Set.of(80);
        private int pageSize = Integer.MAX_VALUE;
        private boolean reachable = true;
        private boolean javaReachable = true;
        private Map<String, String> reachabilityErrors = Map.of();

        @Override public boolean supportsJavaReachability() { return true; }

        @Override
        @SuppressWarnings("unchecked")
        public synchronized Map<String, Object> startNetworkProbe(Map<String, Object> plan) {
            List<Map<String, Object>> targets = (List<Map<String, Object>>) plan.get("targets");
            List<String> stages = (List<String>) plan.get("stages");
            String stage = stages.contains("host-reachability") ? "REACHABILITY" : stages.contains("http-request") ? "RECON"
                    : stages.contains("http-head") ? "SERVICE_PROBE"
                    : stages.contains("tcp-exchange") ? "SERVICE_PROBE"
                    : targets.get(0).containsKey("targetId") ? "REACHABILITY" : "PORT_SCAN";
            startedStages.add(stage);
            String taskId = "child-" + tasks.size();
            tasks.put(taskId, Map.of("stage", stage, "targets", new ArrayList<>(targets)));
            return Map.of("code", 200, "taskId", taskId);
        }

        @Override
        public synchronized Map<String, Object> queryNetworkProbe(String taskId, long cursor, long ackCursor,
                                                                    int maxItems, int maxBytes,
                                                                    boolean includeEvidence) {
            Map<String, Object> response = cursorPage(queryNetworkProbe(taskId), cursor);
            Map<String, Object> result = (Map<String, Object>) response.get("result");
            List<?> observations = (List<?>) result.get("observations");
            int end = (int) Math.min(observations.size(), cursor + pageSize);
            result.put("observations", observations.subList((int) cursor, end));
            result.put("nextCursor", (long) end);
            result.put("hasMore", end < observations.size());
            return response;
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
                    String error = reachabilityErrors.get(String.valueOf(target.get("host")));
                    boolean javaAnswer = reachable && javaReachable && error == null;
                    observation.put("stage", javaAnswer ? "java-reachability" : "tcp-connect");
                    List<Integer> ports = (List<Integer>) target.get("ports");
                    observation.put("port", javaAnswer ? 0 : ports.contains(80) ? 80 : ports.get(0));
                    observation.put("state", reachable && error == null ? "open" : "closed");
                    if (error != null || !reachable) observation.put("errorCode", error == null ? "TIMEOUT" : error);
                    observation.put("hostComplete", true);
                    if ("NO_ROUTE".equals(error) || "DNS".equals(error)) {
                        for (Integer port : ports) {
                            Map<String, Object> failed = new LinkedHashMap<>(observation);
                            failed.put("port", port); failed.put("hostComplete", port.equals(ports.get(ports.size() - 1)));
                            observations.add(failed);
                        }
                        continue;
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
        @Override
        public Map<String, Object> ackNetworkProbe(String taskId, long cursor) {
            return Map.of("code", 200, "cursor", cursor);
        }

        private final CountDownLatch started = new CountDownLatch(1);
        private final AtomicInteger pauseCalls = new AtomicInteger();
        private final AtomicInteger resumeCalls = new AtomicInteger();
        private final AtomicInteger stopCalls = new AtomicInteger();
        private final AtomicInteger releaseCalls = new AtomicInteger();
        private boolean emitObservation;
        private volatile boolean stopped;

        @Override
        public Map<String, Object> startNetworkProbe(Map<String, Object> plan) {
            started.countDown();
            return Map.of("code", 200, "taskId", "controlled-child");
        }

        @Override
        public Map<String, Object> queryNetworkProbe(String taskId, long cursor, long ackCursor,
                                                      int maxItems, int maxBytes,
                                                      boolean includeEvidence) {
            Map<String, Object> response = queryNetworkProbe(taskId);
            if (emitObservation && cursor == 0) {
                Map<String, Object> page = new LinkedHashMap<>((Map<String, Object>) response.get("result"));
                page.put("observations", List.of(Map.of("host", "host-a", "port", 80, "stage", "tcp-connect", "state", "open")));
                response = Map.of("code", 200, "result", page);
            }
            return cursorPage(response, cursor);
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
            releaseCalls.incrementAndGet();
            return Map.of("code", 200);
        }
    }

    private static final class CancelledWorkflowNode implements NetworkProbeCapable {
        @Override
        public Map<String, Object> ackNetworkProbe(String taskId, long cursor) {
            return Map.of("code", 200, "cursor", cursor);
        }

        private final List<String> startedStages = new ArrayList<>();

        @Override
        @SuppressWarnings("unchecked")
        public synchronized Map<String, Object> startNetworkProbe(Map<String, Object> plan) {
            List<String> stages = (List<String>) plan.get("stages");
            startedStages.add(stages.contains("host-reachability") || stages.contains("tcp-connect") ? "REACHABILITY" : "OTHER");
            return Map.of("code", 200, "taskId", "cancelled-stage-child");
        }

        @Override
        public Map<String, Object> queryNetworkProbe(String taskId, long cursor, long ackCursor,
                                                      int maxItems, int maxBytes,
                                                      boolean includeEvidence) {
            return cursorPage(queryNetworkProbe(taskId), cursor);
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
