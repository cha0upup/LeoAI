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

    private final ScanPlanService planner = new ScanPlanService(new TargetResolver(), new PortPolicyResolver());
    private final ScanPreviewService service = new ScanPreviewService(planner);

    @Test
    void doesNotMultiplyExplicitPortsByThePortPolicy() {
        ScanConfig scan = new ScanConfig(
                "explicit ports",
                new TargetInput(List.of("127.0.0.1:80", "127.0.0.2:443"), List.of()),
                new PortPolicy("custom", List.of(), List.of(80, 443, 8080), List.of()),
                null, null);

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
                null, null));

        assertTrue(response.preview() == null);
        assertTrue(response.errors().stream().anyMatch(error -> error.contains("探活目标数")));
    }

    @Test
    void mixedTargetsReserveExplicitPortsRegardlessOfInputOrder() {
        PortPolicy policy = new PortPolicy("custom", List.of("1-100"), List.of(), List.of());
        var first = planner.plan(new ScanConfig("mixed", new TargetInput(
                List.of("127.0.0.1", "127.0.0.1:45678"), List.of()), policy, null, null));
        ScanConfig reversed = new ScanConfig("mixed", new TargetInput(
                List.of("127.0.0.1:45678", "127.0.0.1"), List.of()), policy, null, null);
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
                new PortPolicy("custom", List.of(), List.of(8080), List.of()), null, null));

        assertEquals(1, plan.targets().size());
        assertEquals("http://127.0.0.1:8080/app", plan.targets().get(0).get("baseUrl"));
        assertThrows(UnsupportedOperationException.class, () -> plan.targets().get(0).put("port", 80));
    }

    @Test
    void previewAndExecutionRejectTheSameInvalidConfiguration() {
        ScanConfig invalid = new ScanConfig("invalid", new TargetInput(List.of("127.0.0.1"), List.of()),
                null, new ExecutionConfig(257, 1000), null);
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> planner.plan(invalid));
        assertEquals(List.of(failure.getMessage()), service.preview(invalid).errors());
        assertNull(service.preview(null).preview());
    }
}
