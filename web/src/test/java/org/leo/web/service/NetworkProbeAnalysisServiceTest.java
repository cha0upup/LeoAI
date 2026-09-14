package org.leo.web.service;

import org.junit.jupiter.api.Test;
import org.leo.service.fingerprint.FingerprintManageService;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NetworkProbeAnalysisServiceTest {

    private final NetworkProbeAnalysisService service =
            new NetworkProbeAnalysisService(new FingerprintManageService());

    @Test
    void preparesReachabilityAsOneBoundedLogicalPlan() throws Exception {
        NetworkProbeAnalysisService.PreparedScan prepared = service.prepare(Map.of(
                "kind", "reachability",
                "hosts", List.of("host-a", "host-b", "host-a"),
                "timeout", 1500,
                "threads", 64));

        assertEquals(List.of("tcp-connect"), prepared.plan().get("stages"));
        assertEquals(10, ((List<?>) prepared.plan().get("targets")).size());
        assertEquals(10, ((Map<?, ?>) prepared.plan().get("limits")).get("threads"));
        assertEquals(1500, ((Map<?, ?>) prepared.plan().get("limits")).get("timeout"));
    }

    @Test
    void reachabilityUsesExactTargetsForNonStandardPorts() throws Exception {
        NetworkProbeAnalysisService.PreparedScan prepared = service.prepare(Map.of(
                "kind", "reachability",
                "hosts", List.of("host-a"),
                "targets", List.of(Map.of("host", "host-a", "port", 8080)),
                "ports", List.of(80, 443, 22, 8080)));

        List<?> targets = (List<?>) prepared.plan().get("targets");
        assertEquals(1, targets.size());
        assertEquals(8080, ((Map<?, ?>) targets.get(0)).get("port"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void enrichesReachabilityWithoutMarkingPendingHostsUnreachable() throws Exception {
        NetworkProbeAnalysisService.PreparedScan prepared = service.prepare(Map.of(
                "kind", "reachability",
                "hosts", List.of("host-a", "host-b", "host-c")));
        service.register("task-1", prepared);

        List<Map<String, Object>> targets = (List<Map<String, Object>>) prepared.plan().get("targets");
        List<Map<String, Object>> observations = new ArrayList<>();
        observations.add(observation("host-a", 80, "open"));
        observations.add(observation("host-b", 80, "closed"));
        observations.add(observation("host-b", 443, "closed"));
        observations.add(observation("host-b", 22, "closed"));
        observations.add(observation("host-b", 8080, "closed"));
        observations.add(observation("host-b", 8443, "closed"));
        observations.add(observation("host-c", 80, "closed"));

        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("status", "RUNNING");
        snapshot.put("targets", targets);
        snapshot.put("observations", observations);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("result", snapshot);
        service.enrich("task-1", response);

        Map<String, Object> result = (Map<String, Object>) response.get("result");
        Map<String, Object> analysis = (Map<String, Object>) result.get("analysis");
        assertEquals("host-reachability", result.get("scanKind"));
        assertEquals(List.of("host-a"), analysis.get("reachableHostList"));
        assertEquals(List.of("host-b"), analysis.get("unreachableHostList"));
        assertEquals(List.of("host-c"), analysis.get("pendingHostList"));
        assertEquals(2, analysis.get("completed"));

        service.enrich("task-1", response);
        result = (Map<String, Object>) response.get("result");
        analysis = (Map<String, Object>) result.get("analysis");
        assertEquals(List.of("host-a"), analysis.get("reachableHostList"));
        assertEquals(2, analysis.get("completed"));
    }

    @Test
    void acceptsReachabilityPlansBeyondTheFormerLogicalTargetLimit() throws Exception {
        List<String> hosts = new ArrayList<>();
        for (int index = 0; index < 2731; index++) hosts.add("host-" + index);

        NetworkProbeAnalysisService.PreparedScan prepared =
                service.prepare(Map.of("kind", "reachability", "hosts", hosts));

        assertEquals(2731 * 5, ((List<?>) prepared.plan().get("targets")).size());
    }

    @Test
    void rejectsReachabilityPlansThatExceedTheGlobalProbeLimit() {
        List<String> hosts = new ArrayList<>();
        for (int index = 0; index < 10000; index++) hosts.add("host-" + index);

        assertThrows(IllegalArgumentException.class,
                () -> service.prepare(Map.of("kind", "reachability", "hosts", hosts,
                        "ports", List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11))));
    }

    @Test
    void reconSelectsRulesByDiscoveredProtocolAndService() throws Exception {
        NetworkProbeAnalysisService analysisService =
                new NetworkProbeAnalysisService(new StubFingerprintManageService());

        NetworkProbeAnalysisService.PreparedScan prepared = analysisService.prepare(Map.of(
                "kind", "recon",
                "targets", List.of(
                        Map.of("host", "host-a", "port", 22, "protocol", "tcp", "service", "ssh"),
                        Map.of("host", "host-b", "port", 6379, "protocol", "tcp", "service", "redis"),
                        Map.of("baseUrl", "http://host-c:8080", "protocol", "http", "service", "http")),
                "ruleSelector", Map.of()));

        List<?> probes = (List<?>) prepared.plan().get("targets");
        assertEquals(1, probes.size());
        assertEquals(List.of("http-request"), prepared.plan().get("stages"));
        assertEquals(Set.of("web_any"), prepared.context().ruleIds());
    }

    @Test
    void httpReconRulesAreRejectedForTcpTargets() {
        NetworkProbeAnalysisService analysisService =
                new NetworkProbeAnalysisService(new StubFingerprintManageService());

        assertThrows(IllegalArgumentException.class, () -> analysisService.prepare(Map.of(
                "kind", "recon",
                "targets", List.of(
                        Map.of("host", "host-a", "port", 22, "protocol", "tcp", "service", "ssh")),
                "ruleSelector", Map.of("fingerprintIds", List.of("web_any")))));
    }

    @Test
    void reconRejectsKnownServicesWithoutApplicableRules() {
        NetworkProbeAnalysisService analysisService =
                new NetworkProbeAnalysisService(new StubFingerprintManageService());

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> analysisService.prepare(Map.of(
                        "kind", "recon",
                        "targets", List.of(Map.of(
                                "host", "host-a", "port", 445,
                                "protocol", "tcp", "service", "smb")),
                        "ruleSelector", Map.of())));

        assertEquals("没有适用于已识别服务的指纹规则", error.getMessage());
    }

    private static Map<String, Object> observation(String host, int port, String state) {
        return Map.of(
                "host", host,
                "port", port,
                "stage", "tcp-connect",
                "state", state);
    }

    private static final class StubFingerprintManageService extends FingerprintManageService {
        private final Map<String, HashMap<String, Object>> fingerprints = Map.of(
                "web_any", fingerprint("web_any", "web-server", "http", List.of("web", "server")));

        @Override
        public List<Map<String, Object>> listFingerprints() {
            return fingerprints.values().stream().map(value -> Map.<String, Object>of(
                    "fingerprintId", value.get("fingerprintId"),
                    "protocol", value.get("protocol"),
                    "name", value.get("name"),
                    "tags", value.get("tags"))).toList();
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
                    "match", Map.of("field", "raw", "operator", "contains", "value", name)));
            return fingerprint;
        }
    }
}
