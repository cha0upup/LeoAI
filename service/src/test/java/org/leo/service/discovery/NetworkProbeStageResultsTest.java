package org.leo.service.discovery;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class NetworkProbeStageResultsTest {
    @Test
    void replaysPortPagesWithoutReplacingCachedIdentityOrDiscoveryTime() {
        var cached = observation("tcp-connect", 80, Map.of());
        cached.put("latencyMs", 5);
        var results = new NetworkProbeStageResults(ScanStage.PORT_SCAN, null, List.of(cached));
        var first = results.openPortResults().get(0);
        var before = NetworkProbePayloads.copyMap(first);
        var refused = observation("tcp-connect", 81, Map.of());
        refused.put("state", "closed");
        results.accept(Map.of(), List.of(cached, refused, observation("http-head", 82, Map.of()),
                observation("tcp-connect", 0, Map.of()), observation("tcp-connect", 443, Map.of())));
        assertEquals(2, results.openPortResults().size());
        assertSame(first, results.openPortResults().get(0));
        assertEquals(before, first);
        assertEquals("tcp|192.0.2.1|80", first.get("endpointId"));
        var latest = new LinkedHashMap<String, Object>();
        results.finish(latest);
        assertEquals(List.of(80, 443), NetworkProbePayloads.mapRows(latest.get("openPortResults"))
                .stream().map(row -> row.get("port")).toList());
    }

    @Test
    void preservesCanonicalEndpointsAndEarlierEvidenceAcrossServiceSubstages() {
        var endpoint = new LinkedHashMap<String, Object>(Map.of("host", "192.0.2.1", "port", 8080, "protocol", "tcp"));
        var endpoints = List.<Map<String, Object>>of(endpoint);
        var tcp = new NetworkProbeStageResults(ScanStage.SERVICE_PROBE, endpoints, List.of());
        tcp.accept(Map.of(), List.of(observation("tcp-exchange", 8080, Map.of("banner", "fixture banner"))));
        var http = new NetworkProbeStageResults(ScanStage.SERVICE_PROBE, endpoints, List.of());
        http.accept(Map.of(), List.of(observation("http-head", 8080, Map.of("statusCode", 200, "server", "fixture"))));
        var tlsObservation = observation("http-head", 8080, Map.of("statusCode", 204, "title", "application"));
        tlsObservation.put("protocol", "https");
        var tls = new NetworkProbeStageResults(ScanStage.SERVICE_PROBE, endpoints, List.of());
        tls.accept(Map.of(), List.of(tlsObservation));
        tls.accept(Map.of(), List.of(tlsObservation));
        assertSame(endpoint, tls.endpointRows().iterator().next());
        assertEquals("fixture banner", endpoint.get("banner"));
        assertEquals("fixture", endpoint.get("server"));
        assertEquals("application", endpoint.get("title"));
        assertEquals("https", endpoint.get("service"));
        assertEquals(true, endpoint.get("serviceIdentified"));
    }

    @Test
    void publicationDetachesNestedEvidenceInBothDirections() {
        var evidence = new LinkedHashMap<String, Object>(Map.of("banner", "before"));
        var component = new LinkedHashMap<String, Object>(Map.of("name", "component"));
        var endpoint = new LinkedHashMap<String, Object>(Map.of("host", "192.0.2.1", "port", 80,
                "evidence", evidence, "fingerprint", Map.of("components", new ArrayList<>(List.of(component)))));
        var published = NetworkProbeStageResults.snapshot(List.of(endpoint));
        var row = ((List<?>) published.get("openPortResults")).get(0);
        var snapshot = (Map<?, ?>) row;
        evidence.put("banner", "after");
        assertEquals("before", ((Map<?, ?>) snapshot.get("evidence")).get("banner"));
        ((List<?>) ((Map<?, ?>) snapshot.get("fingerprint")).get("components")).clear();
        assertEquals(1, ((List<?>) ((Map<?, ?>) endpoint.get("fingerprint")).get("components")).size());
        assertEquals(List.of(80), published.get("openPortList"));
    }

    @ParameterizedTest
    @CsvSource({"PENDING,RUNNING,3", "CANCELLED,CANCELLED,3", "MATCHED,COMPLETED,4"})
    void projectsFingerprintStatesAndCountersWithoutLosingOtherEndpoints(String status, String expected, long completed) {
        var endpoint = new LinkedHashMap<String, Object>(Map.of("host", "192.0.2.1", "port", 80));
        var untouched = new LinkedHashMap<String, Object>(Map.of("host", "192.0.2.2", "port", 80, "fingerprint", Map.of("status", "COMPLETED")));
        var endpoints = List.<Map<String, Object>>of(endpoint, untouched);
        var results = new NetworkProbeStageResults(ScanStage.FINGERPRINT, endpoints, List.of());
        var matches = List.of(match(status, "MATCHED".equals(status), false), match("MATCHED", true, true),
                match("ERROR", true, false), match("INCONCLUSIVE", true, false));
        var latest = Map.<String, Object>of("analysis", Map.of("matches", matches));
        assertEquals(matches, results.accept(latest, List.of()));
        var before = NetworkProbePayloads.copyMap(endpoint);
        results.accept(latest, List.of());
        results.accept(Map.of(), List.of());
        assertEquals(before, endpoint);
        assertSame(endpoints, results.fingerprintEndpoints());
        var fingerprint = (Map<?, ?>) endpoint.get("fingerprint");
        assertEquals(expected, fingerprint.get("status"));
        assertEquals(completed, fingerprint.get("completed"));
        assertEquals(4, fingerprint.get("total"));
        assertEquals(1L, fingerprint.get("errorCount"));
        assertEquals(1L, fingerprint.get("inconclusiveCount"));
        assertEquals(1, ((List<?>) fingerprint.get("components")).size());
        assertEquals(Map.of("status", "COMPLETED"), untouched.get("fingerprint"));
    }

    @Test
    void stagesWithoutLiveEndpointsKeepTheirPersistenceContract() {
        var fingerprint = new NetworkProbeStageResults(ScanStage.FINGERPRINT, null, List.of());
        assertFalse(fingerprint.hasEndpointProjection());
        assertNull(fingerprint.fingerprintEndpoints());
        assertEquals(List.of(match("PENDING", false, false)), fingerprint.accept(
                Map.of("analysis", Map.of("matches", List.of(match("PENDING", false, false)))), List.of()));
        var reachability = new NetworkProbeStageResults(ScanStage.REACHABILITY, null, List.of());
        assertFalse(reachability.hasEndpointProjection());
        assertEquals(List.of(), reachability.fingerprintEndpoints());
        assertEquals(List.of(), reachability.accept(Map.of(), List.of(observation("tcp-connect", 80, Map.of()))));
    }

    private static Map<String, Object> observation(String stage, int port, Map<String, Object> evidence) {
        return new LinkedHashMap<>(Map.of("host", "192.0.2.1", "port", port,
                "stage", stage, "state", "open", "evidence", evidence));
    }

    private static Map<String, Object> match(String status, boolean complete, boolean matched) {
        return Map.of("host", "192.0.2.1", "port", 80, "status", status, "complete", complete, "matched", matched);
    }
}
