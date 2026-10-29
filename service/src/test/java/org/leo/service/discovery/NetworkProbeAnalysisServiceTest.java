package org.leo.service.discovery;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.leo.service.fingerprint.FingerprintManageService;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NetworkProbeAnalysisServiceTest {

    private final NetworkProbeAnalysisService service =
            new NetworkProbeAnalysisService(new FingerprintManageService());

    @ParameterizedTest
    @CsvSource({"REFUSED,reachableHostList", "TIMEOUT,unknownHostList", "IO,unknownHostList",
            "DNS,unreachableHostList", "NO_ROUTE,unreachableHostList"})
    void distinguishesAnsweredHostsFromUnconfirmedProbes(String code, String group) throws Exception {
        var prepared = service.prepare(Map.of("kind", "reachability", "hosts", List.of("host"), "ports", List.of(80)));
        service.register("fixture", prepared);
        Map<String, Object> sample = new LinkedHashMap<>(observation("host", 80, "closed"));
        sample.put("errorCode", code);
        Map<String, Object> response = new LinkedHashMap<>(Map.of("result", Map.of("observations", List.of(sample))));
        service.enrich("fixture", response);
        Map<?, ?> analysis = (Map<?, ?>) ((Map<?, ?>) response.get("result")).get("analysis");
        assertEquals(List.of("host"), analysis.get(group));
        assertEquals(1, analysis.get("completed"));
    }

    @Test
    void keepsTagSelectionAndSnapshotMetadataWithoutCopyingTagsIntoExecutionRules() throws Exception {
        var analysis = new NetworkProbeAnalysisService(new StubFingerprintManageService());
        var selected = analysis.snapshotRules(Map.of("tags", List.of("server")));
        assertEquals(1, selected.size());
        assertEquals("web_any", selected.get(0).get("fingerprintId"));
        assertEquals(List.of("web", "server"), selected.get(0).get("tags"));
        var prepared = analysis.prepare(Map.of("kind", "fingerprint", "rules", selected,
                "targets", List.of(phaseTwoTarget())));
        assertEquals(Set.of("web_any"), prepared.context().rulesById().keySet());
        assertThrows(IllegalArgumentException.class,
                () -> analysis.snapshotRules(Map.of("tags", List.of("missing-tag"))));
    }

    @Test
    void preparesReachabilityAsOneBoundedLogicalPlan() throws Exception {
        NetworkProbeAnalysisService.PreparedScan prepared = service.prepare(Map.of(
                "kind", "reachability",
                "hosts", List.of("host-a", "host-b", "host-a"),
                "timeout", 1500,
                "threads", 64));

        assertEquals(List.of("tcp-connect"), prepared.plan().get("stages"));
        assertEquals(16, ((List<?>) prepared.plan().get("targets")).size());
        assertEquals(16, ((Map<?, ?>) prepared.plan().get("limits")).get("threads"));
        assertEquals(1500, ((Map<?, ?>) prepared.plan().get("limits")).get("timeout"));
    }

    @Test
    void reachabilityUsesExactTargetsForNonStandardPorts() throws Exception {
        NetworkProbeAnalysisService.PreparedScan prepared = service.prepare(Map.of(
                "kind", "reachability",
                "hosts", List.of("host-a", "host-b", "host-c"),
                "targets", List.of(Map.of("host", "host-a", "port", 8080), Map.of("host", "host-a", "port", 8080),
                        Map.of("host", "host-b", "port", 9090), Map.of("host", "host-b", "port", 9091)),
                "ports", List.of(80, 443, 22, 8080)));

        List<?> targets = (List<?>) prepared.plan().get("targets");
        assertEquals(3, targets.size());
        assertEquals(8080, ((Map<?, ?>) targets.get(0)).get("port"));
        service.register("exact-targets", prepared);
        var page = new LinkedHashMap<String, Object>(Map.of("result", Map.of("observations", List.of(
                Map.of("host", "host-a", "port", 8080, "stage", "tcp-connect", "errorCode", "DNS"),
                Map.of("host", "host-b", "port", 9090, "stage", "tcp-connect", "errorCode", "DNS")))));
        service.enrich("exact-targets", page);
        var analysis = (Map<?, ?>) ((Map<?, ?>) page.get("result")).get("analysis");
        assertEquals(List.of("host-a"), analysis.get("unreachableHostList"));
        assertEquals(List.of("host-b", "host-c"), analysis.get("pendingHostList"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void enrichesReachabilityWithoutMarkingPendingHostsUnreachable() throws Exception {
        NetworkProbeAnalysisService.PreparedScan prepared = service.prepare(Map.of(
                "kind", "reachability",
                "hosts", List.of("host-a", "host-b", "host-c"),
                "ports", List.of(80, 443, 22, 8080, 8443, 80)));
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
        assertEquals(List.of(), analysis.get("unreachableHostList"));
        assertEquals(List.of("host-b"), analysis.get("unknownHostList"));
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

        assertEquals(2731 * NetworkProbeLimits.DEFAULT_REACHABILITY_PORTS.size(), ((List<?>) prepared.plan().get("targets")).size());
    }

    @Test
    @SuppressWarnings("unchecked")
    void preservesFlatProbeOrderWhenDiscoveryGroupingIsDisabled() throws Exception {
        var prepared = service.prepare(Map.of("kind", "reachability", "hosts", List.of("host-a", "host-b"),
                "hostDiscovery", false, "javaReachability", true,
                "targets", List.of(Map.of("host", "host-a", "port", 443),
                        Map.of("host", "host-b", "port", 18080),
                        Map.of("host", "host-a", "port", 80),
                        Map.of("host", "host-b", "port", 18080, "explicitPort", true))));
        var targets = (List<Map<String, Object>>) prepared.plan().get("targets");
        assertEquals(List.of("tcp-connect"), prepared.plan().get("stages"));
        assertEquals(List.of("host-a", "host-b", "host-a"), targets.stream().map(target -> target.get("host")).toList());
        assertEquals(List.of(443, 18080, 80), targets.stream().map(target -> target.get("port")).toList());
        assertTrue(targets.stream().noneMatch(target -> target.containsKey("javaReachability") || target.containsKey("ports")));
    }

    @Test
    void groupsDiscoveryByHostAndUsesJavaDetectionOnlyWithoutExplicitPorts() throws Exception {
        var prepared = service.prepare(Map.of("kind", "reachability", "hosts", List.of("host-a", "host-b"),
                "hostDiscovery", true, "javaReachability", true,
                "targets", List.of(Map.of("host", "host-a", "port", 443, "explicitPort", false),
                        Map.of("host", "host-b", "port", 18080, "explicitPort", false),
                        Map.of("host", "host-a", "port", 80, "explicitPort", false),
                        Map.of("host", "host-b", "port", 18080, "explicitPort", true))));
        var targets = (List<Map<String, Object>>) prepared.plan().get("targets");
        assertEquals(List.of("host-reachability"), prepared.plan().get("stages"));
        assertEquals(2, targets.size());
        assertEquals(List.of(443, 80), targets.get(0).get("ports"));
        assertEquals(List.of(18080), targets.get(1).get("ports"));
        assertEquals(true, targets.get(0).get("javaReachability"));
        assertEquals(false, targets.get(1).get("javaReachability"));
        service.register("grouped", prepared);
        var page = new LinkedHashMap<String, Object>(Map.of("result", Map.of("observations", List.of(
                Map.of("host", "host-a", "stage", "java-reachability", "port", 0, "state", "open", "hostComplete", true),
                Map.of("host", "host-b", "stage", "tcp-connect", "port", 18080, "state", "closed", "errorCode", "TIMEOUT", "hostComplete", true)))));
        service.enrich("grouped", page);
        var stats = (Map<?, ?>) ((Map<?, ?>) page.get("result")).get("analysis");
        assertEquals(2, stats.get("completed"));
        assertEquals(1, stats.get("unknownHostCount"));
        assertEquals(List.of("host-a"), stats.get("reachableHostList"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void groupsDefaultDiscoveryPortsAndBoundsWorkersByHostCount(boolean javaReachability) throws Exception {
        var prepared = service.prepare(Map.of("kind", "reachability", "hosts", List.of("host-a", "host-b"),
                "hostDiscovery", true, "javaReachability", javaReachability, "threads", 64, "timeout", 1500));
        var targets = (List<Map<String, Object>>) prepared.plan().get("targets");
        assertEquals(List.of("host-reachability"), prepared.plan().get("stages"));
        assertEquals(2, targets.size());
        assertEquals(Map.of("threads", 2, "timeout", 1500), prepared.plan().get("limits"));
        for (var target : targets) {
            assertEquals(NetworkProbeLimits.DEFAULT_REACHABILITY_PORTS, target.get("ports"));
            assertEquals(javaReachability, target.get("javaReachability"));
        }
        assertNotSame(targets.get(0).get("ports"), targets.get(1).get("ports"));
        ((List<?>) targets.get(0).get("ports")).clear();
        assertEquals(NetworkProbeLimits.DEFAULT_REACHABILITY_PORTS, targets.get(1).get("ports"));
        assertEquals(8, NetworkProbeLimits.DEFAULT_REACHABILITY_PORTS.size());
    }

    @Test
    void acceptsReachabilityPlansBeyondTheFormerGlobalProbeLimit() throws Exception {
        List<String> hosts = new ArrayList<>();
        for (int index = 0; index < 10000; index++) hosts.add("host-" + index);

        var prepared = service.prepare(Map.of("kind", "reachability", "hosts", hosts,
                "ports", List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11)));
        assertEquals(110000, ((List<?>) prepared.plan().get("targets")).size());
    }

    @Test
    void acceptsGroupedDiscoveryWithMoreThanTenThousandHosts() throws Exception {
        var hosts = java.util.stream.IntStream.range(0, 10001).mapToObj(index -> "host-" + index).toList();
        var prepared = service.prepare(Map.of("kind", "reachability", "hosts", hosts,
                "hostDiscovery", true, "javaReachability", true));
        assertEquals(10001, ((List<?>) prepared.plan().get("targets")).size());
        assertEquals(256, ((Map<?, ?>) prepared.plan().get("limits")).get("threads"));
    }

    @Test
    void acceptsMoreThanOneHundredThousandExplicitDiscoveryTargets() throws Exception {
        var hosts = java.util.stream.IntStream.range(0, 12501).mapToObj(index -> "host-" + index).toList();
        var targets = hosts.stream().flatMap(host -> java.util.stream.IntStream.rangeClosed(1, 8)
                .mapToObj(port -> Map.<String, Object>of("host", host, "port", port, "explicitPort", true))).toList();
        var prepared = service.prepare(Map.of("kind", "reachability", "hosts", hosts, "targets", targets));
        assertEquals(100008, ((List<?>) prepared.plan().get("targets")).size());
    }

    @Test
    void largeFingerprintTasksStillDeduplicateIdenticalReadRequests() throws Exception {
        var rules = java.util.stream.IntStream.range(0, 50).mapToObj(index ->
                phaseTwoRule("rule-" + index, List.of(Map.of("path", "/")), "marker", Map.of())).toList();
        var targets = java.util.stream.IntStream.range(0, 1001).mapToObj(index -> Map.<String, Object>of(
                "host", "host-" + index, "port", 80, "protocol", "http")).toList();
        var prepared = service.prepare(Map.of("kind", "fingerprint", "targets", targets, "rules", rules));
        assertEquals(50050, prepared.context().groups().size());
        assertEquals(1001, ((List<?>) prepared.plan().get("targets")).size());
    }

    @ParameterizedTest
    @CsvSource({"http,http", "https,https", "https,unknown", "http,''"})
    void reconSelectsRulesByDiscoveredProtocolAndService(String protocol, String service) throws Exception {
        NetworkProbeAnalysisService analysisService =
                new NetworkProbeAnalysisService(new StubFingerprintManageService());

        NetworkProbeAnalysisService.PreparedScan prepared = analysisService.prepare(Map.of(
                "kind", "recon",
                "targets", List.of(
                        Map.of("host", "host-a", "port", 22, "protocol", "tcp", "service", "ssh"),
                        Map.of("host", "host-b", "port", 6379, "protocol", "tcp", "service", "redis"),
                        Map.of("baseUrl", "http://non-http.test", "protocol", "http", "service", "redis"),
                        Map.of("baseUrl", "http://host-c:8080", "protocol", protocol, "service", service)),
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
        assertEquals("探测证据不完整", assertThrows(IllegalArgumentException.class, () -> evaluateFingerprint(contains,
                Map.of("probeId", "unexpected", "requestIndex", 1))).getMessage());
    }

    @ParameterizedTest
    @CsvSource({"all,404,NOT_MATCHED", "all,200,INCONCLUSIVE", "any,200,MATCHED", "any,404,INCONCLUSIVE"})
    void combinesIncompleteAndKnownConditionsInEitherOrder(String mode, int status, String expected) throws Exception {
        var incomplete = Map.of("field", "body", "value", "absent");
        var known = Map.of("field", "status", "operator", "equals", "value", status);
        for (var children : List.of(List.of(incomplete, known), List.of(known, incomplete))) {
            var expression = Map.<String, Object>of(mode, children);
            var response = Map.<String, Object>of("evidence", Map.of("statusCode", 200, "body", "prefix", "truncated", true));
            assertEquals(expected, evaluateFingerprint(expression, response).get("status"));
            assertEquals(expected, evaluateFingerprint(Map.of("not", Map.of("not", expression)), response).get("status"));
        }
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
        var firstCondition = new LinkedHashMap<String, Object>(Map.of("request", 0, "field", "body", "value", "one"));
        Map<String, Object> rule = Map.of("fingerprintId", "multi", "name", "Multi", "protocol", "http",
                "rule", Map.of("requests", List.of(Map.of("uri", "/first"), Map.of("uri", "/second")),
                "match", Map.of("all", List.of(firstCondition,
                        Map.of("request", 1, "field", "body", "value", "two")))));
        var prepared = service.prepare(Map.of("kind", "fingerprint", "rules", List.of(rule), "targets", List.of(
                Map.of("host", "127.0.0.1", "port", 80, "baseUrl", "http://example.test/app/", "protocol", "http"))));
        firstCondition.put("value", "changed after preparation");
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
        assertEquals(List.of(0, 1), firstMatch(page).get("requestIndices"));
        assertEquals(probes.stream().map(probe -> probe.get("probeId")).toList(), firstMatch(page).get("probeIds"));
        service.enrich("multi", page);
        assertEquals(2, firstMatch(page).get("evidenceCount"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void sharesReadRequestsAcrossRulesAndKeepsIndependentDecisionsAndEvidence() throws Exception {
        Map<String, Object> a = phaseTwoRule("a", List.of(Map.of("path", "/")), "marker", Map.of());
        Map<String, Object> b = phaseTwoRule("b", List.of(Map.of("path", "/"), Map.of("path", "/extra")), "absent", Map.of());
        var target = new LinkedHashMap<>(phaseTwoTarget());
        var prepared = service.prepare(Map.of("kind", "fingerprint", "targets", List.of(target), "rules", List.of(a, b), "debug", true));
        var snapshot = prepared.context().groups().get(0).target();
        assertSame(snapshot, prepared.context().groups().get(1).target());
        target.put("host", "changed");
        assertEquals("127.0.0.1", snapshot.get("host"));
        assertThrows(UnsupportedOperationException.class, () -> snapshot.put("host", "changed"));
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

    @ParameterizedTest
    @CsvSource({"2000,3000", "5000,5000"})
    void doesNotShareRequestsWithDifferentSemanticsOrWrites(int timeout, int timeoutLimit) throws Exception {
        List<Map<String, Object>> requests = List.of(Map.of(), Map.of("headers", Map.of("X-Tenant", "a")),
                Map.of("headers", Map.of("X-Tenant", "b")), Map.of("body", "a"), Map.of("body", "b"),
                Map.of("timeout", timeout), Map.of("maxBodyBytes", 256), Map.of("charset", "ISO-8859-1"),
                Map.of("method", "POST"), Map.of("method", "POST"));
        var prepared = service.prepare(Map.of("kind", "fingerprint", "targets", List.of(phaseTwoTarget()),
                "rules", List.of(phaseTwoRule("distinct", requests, "marker", Map.of()))));
        assertEquals(10, ((List<?>) prepared.plan().get("targets")).size());
        assertEquals(timeoutLimit, ((Map<?, ?>) prepared.plan().get("limits")).get("timeout"));
        assertEquals(NetworkProbeLimits.NODE_MAX_READ_BYTES, ((Map<?, ?>) prepared.plan().get("limits")).get("maxReadBytes"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void extractsOnlyObservedVersionsAndHandlesTruncation() throws Exception {
        Map<String, Object> extractor = Map.of("request", 0, "field", "headers", "prefix", "nginx/");
        for (var sample : List.of(
                Map.of("headers", "Server: nginx/1.26.2\r\n", "version", "1.26.2", "status", "EXTRACTED"),
                Map.of("headers", "Server: nginx/1.26", "truncated", true, "status", "INCONCLUSIVE"),
                Map.of("headers", "Server: nginx/1.26 ", "truncated", true, "version", "1.26", "status", "EXTRACTED"),
                Map.of("headers", "Server: nginx/" + "1".repeat(64) + "\r\n", "version", "1".repeat(64), "status", "EXTRACTED"),
                Map.of("headers", "Server: nginx/" + "1".repeat(65) + "\r\n", "status", "NOT_FOUND"),
                Map.of("headers", "Server: nginx/invalid; nginx/1.26.2\r\n", "version", "1.26.2", "status", "EXTRACTED"),
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
