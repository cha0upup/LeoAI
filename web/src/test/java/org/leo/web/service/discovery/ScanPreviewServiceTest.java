package org.leo.web.service.discovery;

import org.junit.jupiter.api.Test;
import org.leo.web.dto.puppetnode.scan.NetworkDiscoveryDtos.PortPolicy;
import org.leo.web.dto.puppetnode.scan.NetworkDiscoveryDtos.PreviewResponse;
import org.leo.web.dto.puppetnode.scan.NetworkDiscoveryDtos.ScanConfig;
import org.leo.web.dto.puppetnode.scan.NetworkDiscoveryDtos.TargetInput;
import org.leo.web.dto.puppetnode.scan.NetworkDiscoveryDtos.ExecutionConfig;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScanPreviewServiceTest {

    private final ScanPlanService planner = new ScanPlanService(new TargetResolver(), new PortPolicyResolver(),
            new org.leo.web.service.NetworkProbeAnalysisService(new org.leo.service.fingerprint.FingerprintManageService()));
    private final ScanPreviewService service = new ScanPreviewService(planner);

    @Test
    void doesNotMultiplyExplicitPortsByThePortPolicy() {
        ScanConfig scan = new ScanConfig(
                "explicit ports",
                new TargetInput(List.of("127.0.0.1:80", "127.0.0.2:443"), List.of()),
                new PortPolicy("custom", List.of(), List.of(80, 443, 8080), List.of()),
                null, null, null);

        PreviewResponse response = service.preview(scan);

        assertTrue(response.errors().isEmpty(), response.errors().toString());
        assertEquals(2, response.preview().hostCount());
        assertEquals(2, response.preview().portCount());
        assertEquals(2, response.preview().combinationCount());
        assertEquals(2, response.preview().reachabilityProbeCount());
    }

    @Test
    void rejectsAReachabilityPlanThatWouldOverloadTheWorkflow() {
        List<Integer> ports = new java.util.ArrayList<>();
        for (int port = 1; port <= 26; port++) ports.add(port);

        PreviewResponse response = service.preview(new ScanConfig(
                "large reachability",
                new TargetInput(List.of("192.0.0.0/20"), List.of()),
                new PortPolicy("custom", List.of(), ports, List.of()),
                null, null, null));

        assertTrue(response.preview() == null);
        assertTrue(response.errors().stream().anyMatch(error -> error.contains("探活目标数")));
    }

    @Test
    void mixedTargetsReserveExplicitPortsRegardlessOfInputOrder() {
        PortPolicy policy = new PortPolicy("custom", List.of("1-100"), List.of(), List.of());
        var first = planner.plan(new ScanConfig("mixed", new TargetInput(
                List.of("127.0.0.1", "127.0.0.1:45678"), List.of()), policy, null, null, null));
        ScanConfig reversed = new ScanConfig("mixed", new TargetInput(
                List.of("127.0.0.1:45678", "127.0.0.1"), List.of()), policy, null, null, null);
        var second = planner.plan(reversed);

        assertEquals(Set.copyOf(first.reachabilityTargets()), Set.copyOf(second.reachabilityTargets()));
        assertEquals(32, first.reachabilityTargets().size());
        assertTrue(first.reachabilityTargets().contains(Map.of("host", "127.0.0.1", "port", 45678, "protocol", "tcp")));
        assertEquals(101, first.targets().size());
        assertEquals(first.targets().size(), service.preview(reversed).preview().combinationCount());
        assertEquals(first.reachabilityTargets().size(), service.preview(reversed).preview().reachabilityProbeCount());
    }

    @Test
    void deduplicatesEndpointsWithoutLosingTheExplicitUrl() {
        var plan = planner.plan(new ScanConfig("url", new TargetInput(
                List.of("127.0.0.1", "http://127.0.0.1:8080/app"), List.of()),
                new PortPolicy("custom", List.of(), List.of(8080), List.of()), null, null, null));

        assertEquals(1, plan.targets().size());
        assertEquals("http://127.0.0.1:8080/app", plan.targets().get(0).get("baseUrl"));
        assertThrows(UnsupportedOperationException.class, () -> plan.targets().get(0).put("port", 80));
        assertThrows(UnsupportedOperationException.class, () -> plan.hosts().clear());
        assertThrows(UnsupportedOperationException.class, () -> plan.ports().clear());
        List<?> applications = (List<?>) plan.targets().get(0).get("applications");
        assertThrows(UnsupportedOperationException.class, applications::clear);
        assertThrows(UnsupportedOperationException.class, () -> ((Map<?, ?>) applications.get(0)).clear());
    }

    @Test
    void snapshotsRulesBeforeTheyCanBeChangedWhileWaitingForExecution() {
        var analysis = org.mockito.Mockito.mock(org.leo.web.service.NetworkProbeAnalysisService.class);
        Map<String, Object> request = new java.util.LinkedHashMap<>(Map.of("method", "GET"));
        List<Map<String, Object>> requests = new java.util.ArrayList<>(List.of(request));
        Map<String, Object> rule = new java.util.LinkedHashMap<>(Map.of("id", "test-rule", "requests", requests));
        org.mockito.Mockito.when(analysis.snapshotRules(org.mockito.ArgumentMatchers.any())).thenReturn(List.of(rule));
        var snapshotPlanner = new ScanPlanService(new TargetResolver(), new PortPolicyResolver(), analysis);
        var plan = snapshotPlanner.plan(new ScanConfig("rules", new TargetInput(List.of("127.0.0.1:80"), List.of()),
                null, null, null, List.of("PORT_SCAN", "SERVICE_PROBE", "FINGERPRINT")));

        request.put("method", "POST");
        requests.clear();
        rule.put("id", "changed");

        assertEquals("test-rule", plan.fingerprintRules().get(0).get("id"));
        List<?> snapshot = (List<?>) plan.fingerprintRules().get(0).get("requests");
        assertEquals("GET", ((Map<?, ?>) snapshot.get(0)).get("method"));
        assertThrows(UnsupportedOperationException.class, () -> ((Map<?, ?>) snapshot.get(0)).clear());
    }

    @Test
    void previewAndExecutionRejectTheSameInvalidConfiguration() {
        ScanConfig invalid = new ScanConfig("invalid", new TargetInput(List.of("127.0.0.1"), List.of()),
                null, new ExecutionConfig(257, 1000), null, null);
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> planner.plan(invalid));
        assertEquals(List.of(failure.getMessage()), service.preview(invalid).errors());
        assertNull(service.preview(null).preview());
    }
    @Test
    void reachabilityOnlyIgnoresPortPolicyAndDoesNotExpandPortScanTargets() {
        ScanConfig scan = new ScanConfig("alive", new TargetInput(List.of("192.0.2.0/24"), List.of()),
                new PortPolicy("custom", List.of("1-65535"), List.of(), List.of()),
                null, null, List.of("REACHABILITY"));
        var plan = planner.plan(scan);
        var preview = service.preview(scan).preview();
        assertTrue(plan.targets().isEmpty());
        assertTrue(plan.ports().isEmpty());
        assertEquals(plan.hosts().size() * NetworkProbeLimits.DEFAULT_REACHABILITY_PORTS.size(), plan.reachabilityTargets().size());
        assertEquals(0, preview.combinationCount());
        assertEquals(0, preview.serviceProbeCount());
        assertEquals(List.of("REACHABILITY"), plan.stages().stream().map(Enum::name).toList());
    }

    @Test
    void skippingDiscoveryAvoidsReachabilityLimitsButStillBoundsPortWork() {
        List<String> endpoints = java.util.stream.IntStream.rangeClosed(1, 40)
                .mapToObj(port -> "127.0.0.1:" + port).toList();
        ScanConfig scan = new ScanConfig("ports", new TargetInput(endpoints, List.of()),
                null, null, null, List.of("PORT_SCAN"));
        var plan = planner.plan(scan);
        var preview = service.preview(scan).preview();
        assertEquals(40, plan.targets().size());
        assertTrue(plan.reachabilityTargets().isEmpty());
        assertEquals(0, preview.reachabilityProbeCount());
        assertEquals(0, preview.serviceProbeCount());
        ScanConfig oversized = new ScanConfig("too many", new TargetInput(List.of("192.0.2.0/24"), List.of()),
                new PortPolicy("custom", List.of("1-65535"), List.of(), List.of()),
                null, null, List.of("PORT_SCAN"));
        assertTrue(service.preview(oversized).errors().get(0).contains("扫描组合数"));
    }

    @Test
    void validatesStageSelectionsAndKeepsLegacyJsonDefault() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        ScanConfig legacy = mapper.readValue("{\"targets\":{\"items\":[\"127.0.0.1:80\"]}}", ScanConfig.class);
        assertEquals(List.of("REACHABILITY", "PORT_SCAN", "SERVICE_PROBE"), planner.plan(legacy).stages().stream().map(Enum::name).toList());
        for (List<String> stages : List.of(List.<String>of(), List.of("SERVICE_PROBE"), List.of("PORT_SCAN", "FINGERPRINT"), List.of("UNKNOWN"))) {
            ScanConfig invalid = new ScanConfig("invalid", legacy.targets(), null, null, null, stages);
            var failure = assertThrows(IllegalArgumentException.class, () -> planner.plan(invalid));
            assertEquals(List.of(failure.getMessage()), service.preview(invalid).errors());
        }
        ScanConfig onlyAlive = mapper.readValue("{\"targets\":{\"items\":[\"127.0.0.1:80\"]},\"stages\":[\"REACHABILITY\"]}", ScanConfig.class);
        assertEquals(List.of("REACHABILITY"), planner.plan(onlyAlive).stages().stream().map(Enum::name).toList());
    }

}
