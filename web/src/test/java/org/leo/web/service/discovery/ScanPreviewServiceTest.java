package org.leo.web.service.discovery;

import org.junit.jupiter.api.Test;
import org.leo.web.dto.puppetnode.scan.NetworkDiscoveryDtos.PortPolicy;
import org.leo.web.dto.puppetnode.scan.NetworkDiscoveryDtos.PreviewResponse;
import org.leo.web.dto.puppetnode.scan.NetworkDiscoveryDtos.ScanConfig;
import org.leo.web.dto.puppetnode.scan.NetworkDiscoveryDtos.TargetInput;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScanPreviewServiceTest {

    private final ScanPreviewService service =
            new ScanPreviewService(new TargetResolver(), new PortPolicyResolver());

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
}
