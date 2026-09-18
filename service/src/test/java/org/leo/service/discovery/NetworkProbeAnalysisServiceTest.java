package org.leo.service.discovery;

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

    @Test
    void distinguishesRequestErrorsAndIncompleteNegationsFromMatches() throws Exception {
        Map<String, Object> contains = Map.of("field", "body", "operator", "contains", "value", "marker");
        assertEquals("ERROR", evaluateFingerprint(Map.of("field", "body", "operator", "notcontains", "value", "marker"),
                Map.of("error", "timeout")).get("status"));
        assertEquals("INCONCLUSIVE", evaluateFingerprint(Map.of("not", contains),
                Map.of("evidence", Map.of("statusCode", 200, "body", "prefix", "truncated", true))).get("status"));
        assertEquals("MATCHED", evaluateFingerprint(contains,
                Map.of("evidence", Map.of("statusCode", 200, "body", "prefix marker", "truncated", true))).get("status"));
        assertEquals("NOT_MATCHED", evaluateFingerprint(contains,
                Map.of("evidence", Map.of("statusCode", 200, "body", "prefix"))).get("status"));
        assertEquals("INCONCLUSIVE", evaluateFingerprint(Map.of("not", Map.of("field", "headers", "operator", "contains", "value", "absent")),
                Map.of("evidence", Map.of("statusCode", 200, "headers", "X: present", "headersTruncated", true))).get("status"));
    }

    @Test
    void nacosFingerprintDoesNotTreatErrorPageEchoAsAHit() throws Exception {
        Map<String, Object> nacosMatch = Map.of("all", List.of(
                Map.of("field", "status", "operator", "equals", "value", 200),
                Map.of("field", "body", "operator", "contains", "value", "nacos")));

        assertEquals("NOT_MATCHED", evaluateFingerprint(nacosMatch,
                Map.of("evidence", Map.of("statusCode", 404,
                        "body", "请求的资源[/nacos/]不可用"))).get("status"));
        assertEquals("MATCHED", evaluateFingerprint(nacosMatch,
                Map.of("evidence", Map.of("statusCode", 200,
                        "body", "<title>Nacos</title>"))).get("status"));
    }

    @Test
    void validatesRequestsBeforeCreatingAnyProbes() {
        FingerprintManageService library = new FingerprintManageService();
        assertThrows(IllegalArgumentException.class, () -> library.validateRule(Map.of(
                "requests", List.of(Map.of("uri", "https://another-host/")),
                "match", Map.of("field", "body", "value", "marker"))));
        assertThrows(IllegalArgumentException.class, () -> library.validateRule(Map.of(
                "requests", List.of(Map.of()), "match", Map.of("field", "body", "value", ""))));
        assertThrows(IllegalArgumentException.class, () -> library.validateRule(Map.of(
                "requests", List.of(Map.of()), "match", Map.of("field", "body", "value", "marker", "request", 1))));
    }

    @Test
    void accumulatesMultiRequestEvidenceAcrossPagesAndDeduplicatesReplay() throws Exception {
        Map<String, Object> rule = Map.of("fingerprintId", "multi", "name", "Multi", "protocol", "http",
                "rule", Map.of("requests", List.of(Map.of("uri", "/first"), Map.of("uri", "/second")),
                "match", Map.of("all", List.of(Map.of("request", 0, "field", "body", "value", "one"),
                        Map.of("request", 1, "field", "body", "value", "two")))));
        var prepared = service.prepare(Map.of("kind", "fingerprint", "rules", List.of(rule), "targets", List.of(
                Map.of("host", "127.0.0.1", "port", 80, "baseUrl", "http://example.test/app/", "protocol", "http"))));
        service.register("multi", prepared);
        List<Map<String, Object>> probes = (List<Map<String, Object>>) prepared.plan().get("targets");
        assertEquals("/app/first", ((Map<?, ?>) probes.get(0).get("httpRequest")).get("path"));
        assertEquals("http://example.test/", probes.get(0).get("baseUrl"));
        Map<String, Object> first = new LinkedHashMap<>(probes.get(0));
        first.put("evidence", Map.of("statusCode", 200, "body", "one"));
        Map<String, Object> second = new LinkedHashMap<>(probes.get(1));
        second.put("evidence", Map.of("statusCode", 200, "body", "two"));
        Map<String, Object> page = new LinkedHashMap<>(Map.of("result", Map.of("status", "RUNNING", "observations", List.of(second))));
        service.enrich("multi", page);
        assertEquals("PENDING", firstMatch(page).get("status"));
        page = new LinkedHashMap<>(Map.of("result", Map.of("status", "STOPPED", "observations", List.of(first, second))));
        service.enrich("multi", page);
        assertEquals("MATCHED", firstMatch(page).get("status"));
        assertEquals(2, firstMatch(page).get("evidenceCount"));
        service.enrich("multi", page);
        assertEquals(2, firstMatch(page).get("evidenceCount"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void sharesReadRequestsAcrossRulesAndKeepsIndependentDecisionsAndEvidence() throws Exception {
        Map<String, Object> a = phaseTwoRule("a", List.of(Map.of("path", "/")), "marker", Map.of());
        Map<String, Object> b = phaseTwoRule("b", List.of(Map.of("path", "/"), Map.of("path", "/extra")), "absent", Map.of());
        var prepared = service.prepare(Map.of("kind", "fingerprint", "targets", List.of(phaseTwoTarget()), "rules", List.of(a, b), "debug", true));
        var probes = (List<Map<String, Object>>) prepared.plan().get("targets");
        assertEquals(2, probes.size());
        service.register("shared", prepared);
        var first = new LinkedHashMap<>(probes.get(0));
        first.put("evidence", Map.of("statusCode", 200, "body", "marker"));
        var page = new LinkedHashMap<String, Object>(Map.of("result", Map.of("status", "RUNNING", "observations", List.of(first))));
        service.enrich("shared", page);
        var firstAnalysis = (Map<String, Object>) ((Map<?, ?>) page.get("result")).get("analysis");
        var initial = (List<Map<String, Object>>) firstAnalysis.get("matches");
        assertEquals(List.of("MATCHED", "PENDING"), initial.stream().map(item -> item.get("status")).toList());
        assertEquals(3, firstAnalysis.get("logicalRequestCount"));
        assertEquals(2, firstAnalysis.get("networkRequestCount"));
        var second = new LinkedHashMap<>(probes.get(1));
        second.put("evidence", Map.of("statusCode", 200, "body", "marker"));
        page = new LinkedHashMap<>(Map.of("result", Map.of("status", "STOPPED", "observations", List.of(first, second))));
        service.enrich("shared", page);
        var matches = (List<Map<String, Object>>) ((Map<?, ?>) ((Map<?, ?>) page.get("result")).get("analysis")).get("matches");
        assertEquals(List.of("MATCHED", "NOT_MATCHED"), matches.stream().map(item -> item.get("status")).toList());
        assertEquals(initial.get(0).get("probeIds"), List.of(((List<?>) matches.get(1).get("probeIds")).get(0)));
        assertEquals(2, matches.get(1).get("evidenceCount"));
        assertEquals("NOT_MATCHED", ((Map<?, ?>) ((List<?>) matches.get(1).get("conditions")).get(0)).get("status"));
        service.enrich("shared", page);
        assertEquals(2, ((Map<?, ?>) ((Map<?, ?>) page.get("result")).get("analysis")).get("completed"));
    }

    @Test
    void doesNotShareRequestsWithDifferentSemanticsOrWrites() throws Exception {
        List<Map<String, Object>> requests = List.of(Map.of(), Map.of("headers", Map.of("X-Tenant", "a")),
                Map.of("headers", Map.of("X-Tenant", "b")), Map.of("body", "a"), Map.of("body", "b"),
                Map.of("timeout", 2000), Map.of("maxBodyBytes", 256), Map.of("charset", "ISO-8859-1"),
                Map.of("method", "POST"), Map.of("method", "POST"));
        var prepared = service.prepare(Map.of("kind", "fingerprint", "targets", List.of(phaseTwoTarget()),
                "rules", List.of(phaseTwoRule("distinct", requests, "marker", Map.of()))));
        assertEquals(10, ((List<?>) prepared.plan().get("targets")).size());
    }

    @Test
    @SuppressWarnings("unchecked")
    void extractsOnlyObservedVersionsAndHandlesTruncation() throws Exception {
        Map<String, Object> extractor = Map.of("request", 0, "field", "headers", "prefix", "nginx/");
        for (var sample : List.of(
                Map.of("headers", "Server: nginx/1.26.2\r\n", "version", "1.26.2", "status", "EXTRACTED"),
                Map.of("headers", "Server: nginx/1.26", "truncated", true, "status", "INCONCLUSIVE"),
                Map.of("headers", "Server: nginx/1.26 ", "truncated", true, "version", "1.26", "status", "EXTRACTED"),
                Map.of("headers", "Server: nginx", "status", "NOT_FOUND"))) {
            var prepared = service.prepare(Map.of("kind", "fingerprint", "targets", List.of(phaseTwoTarget()),
                    "rules", List.of(phaseTwoRule("version", List.of(Map.of()), "marker", extractor))));
            service.register("versions", prepared);
            var observation = new LinkedHashMap<>((Map<String, Object>) ((List<?>) prepared.plan().get("targets")).get(0));
            observation.put("evidence", Map.of("statusCode", 200, "body", "marker", "headers", sample.get("headers"),
                    "headersTruncated", Boolean.TRUE.equals(sample.get("truncated"))));
            var page = new LinkedHashMap<String, Object>(Map.of("result", Map.of("observations", List.of(observation))));
            service.enrich("versions", page);
            var match = firstMatch(page);
            assertEquals(sample.get("status"), match.get("versionStatus"));
            assertEquals(sample.get("version"), match.get("detectedVersion"));
            if (sample.containsKey("version")) assertEquals(0, ((Map<?, ?>) match.get("versionEvidence")).get("request"));
        }
        assertThrows(IllegalArgumentException.class, () -> service.prepare(Map.of("kind", "fingerprint", "targets", List.of(phaseTwoTarget()),
                "rules", List.of(phaseTwoRule("invalid", List.of(Map.of()), "marker", Map.of("field", "headers", "prefix", "nginx/", "request", 1))))));
    }

    @Test
    void rejectsInvalidVersionDeclarationsBeforeExecuting() {
        for (Map<String, Object> extractor : List.<Map<String, Object>>of(Map.of(), Map.of("prefix", "nginx/"),
                Map.of("field", "body", "prefix", ""), Map.of("field", "body", "prefix", "v", "regex", ".*"),
                Map.of("field", "body", "prefix", "v", "ignoreCase", "false"))) {
            assertThrows(IllegalArgumentException.class, () -> service.prepare(Map.of("kind", "fingerprint",
                    "targets", List.of(phaseTwoTarget()), "rules", List.of(Map.of("fingerprintId", "invalid", "protocol", "http",
                            "rule", Map.of("requests", List.of(Map.of()), "match", Map.of("field", "body", "value", "marker"), "version", extractor))))));
        }
    }

    private Map<String, Object> phaseTwoRule(String id, List<Map<String, Object>> requests, String marker, Map<String, Object> version) {
        var definition = new LinkedHashMap<String, Object>(Map.of("requests", requests, "match", Map.of("field", "body", "value", marker)));
        if (!version.isEmpty()) definition.put("version", version);
        return Map.of("fingerprintId", id, "name", id, "protocol", "http", "info", Map.of("version", "any"), "rule", definition);
    }

    private Map<String, Object> phaseTwoTarget() {
        return Map.of("host", "127.0.0.1", "port", 80, "protocol", "http", "baseUrl", "http://example.test/app/");
    }

    private Map<?, ?> evaluateFingerprint(Map<String, Object> expression, Map<String, Object> response) throws Exception {
        Map<String, Object> rule = Map.of("fingerprintId", "demo", "name", "Demo", "protocol", "http",
                "rule", Map.of("requests", List.of(Map.of()), "match", expression));
        var prepared = service.prepare(Map.of("kind", "fingerprint", "rules", List.of(rule), "targets", List.of(
                Map.of("host", "127.0.0.1", "port", 80, "protocol", "http"))));
        String id = java.util.UUID.randomUUID().toString();
        service.register(id, prepared);
        Map<String, Object> observation = new LinkedHashMap<>((Map<String, Object>) ((List<?>) prepared.plan().get("targets")).get(0));
        observation.putAll(response);
        Map<String, Object> result = new LinkedHashMap<>(Map.of("result", Map.of("status", "STOPPED", "observations", List.of(observation))));
        service.enrich(id, result);
        return firstMatch(result);
    }

    private Map<?, ?> firstMatch(Map<String, Object> result) {
        return (Map<?, ?>) ((List<?>) ((Map<?, ?>) ((Map<?, ?>) result.get("result")).get("analysis")).get("matches")).get(0);
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
