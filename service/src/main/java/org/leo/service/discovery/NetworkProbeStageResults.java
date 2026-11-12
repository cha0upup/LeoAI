package org.leo.service.discovery;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.leo.service.discovery.NetworkProbePayloads.copyMap;
import static org.leo.service.discovery.NetworkProbePayloads.copyMapOrEmpty;
import static org.leo.service.discovery.NetworkProbePayloads.endpointKey;
import static org.leo.service.discovery.NetworkProbePayloads.integer;
import static org.leo.service.discovery.NetworkProbePayloads.mapRows;
import static org.leo.service.discovery.NetworkProbePayloads.text;

/**
 * Worker-owned endpoint projection for a stage. Service and fingerprint stages
 * update the same live maps; publication always takes a detached snapshot.
 * Replayed pages retain earlier evidence and the first discovered endpoint.
 */
final class NetworkProbeStageResults {
    private final ScanStage stage;
    private final List<Map<String, Object>> liveEndpoints;
    private final Map<String, Map<String, Object>> endpointsByKey = new LinkedHashMap<>();

    NetworkProbeStageResults(ScanStage stage, List<Map<String, Object>> liveEndpoints,
                            Collection<Map<String, Object>> cachedObservations) {
        this.stage = stage;
        this.liveEndpoints = liveEndpoints;
        if (stage == ScanStage.PORT_SCAN) collectOpenEndpoints(endpointsByKey, cachedObservations);
        if (stage == ScanStage.SERVICE_PROBE && liveEndpoints != null) {
            for (var endpoint : liveEndpoints) endpointsByKey.put(endpointKey(endpoint), endpoint);
        }
    }

    List<Map<String, Object>> accept(Map<String, Object> latest, List<Map<String, Object>> observations) {
        List<Map<String, Object>> matches = stage == ScanStage.FINGERPRINT
                ? mapRows(copyMapOrEmpty(latest.get("analysis")).get("matches")) : List.of();
        switch (stage) {
            case PORT_SCAN -> collectOpenEndpoints(endpointsByKey, observations);
            case SERVICE_PROBE -> {
                if (liveEndpoints != null) applyServiceEvidence(endpointsByKey, observations);
            }
            case FINGERPRINT -> {
                if (liveEndpoints != null) applyFingerprintMatches(liveEndpoints, matches);
            }
            case REACHABILITY -> { }
        }
        return matches;
    }

    boolean hasEndpointProjection() {
        return stage == ScanStage.PORT_SCAN
                || (liveEndpoints != null && (stage == ScanStage.SERVICE_PROBE || stage == ScanStage.FINGERPRINT));
    }

    Collection<Map<String, Object>> endpointRows() {
        return stage == ScanStage.PORT_SCAN ? endpointsByKey.values() : liveEndpoints;
    }

    List<Map<String, Object>> openPortResults() {
        return new ArrayList<>(endpointsByKey.values());
    }

    List<Map<String, Object>> fingerprintEndpoints() {
        return stage == ScanStage.FINGERPRINT ? liveEndpoints : List.of();
    }

    void finish(Map<String, Object> latest) {
        if (stage == ScanStage.PORT_SCAN) latest.put("openPortResults", openPortResults());
    }

    static Map<String, Object> snapshot(Collection<Map<String, Object>> endpoints) {
        List<Map<String, Object>> results = new ArrayList<>(endpoints.size());
        List<Object> ports = new ArrayList<>(endpoints.size());
        for (Map<String, Object> endpoint : endpoints) {
            results.add(copyMap(endpoint));
            ports.add(endpoint.get("port"));
        }
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("openPortResults", results);
        snapshot.put("openPortList", ports);
        return snapshot;
    }

    private static void collectOpenEndpoints(Map<String, Map<String, Object>> byEndpoint,
                                             Collection<Map<String, Object>> observations) {
        for (Map<String, Object> observation : observations) {
            if (!"tcp-connect".equals(text(observation.get("stage")))
                    || !"open".equalsIgnoreCase(text(observation.get("state")))) continue;
            String host = text(observation.get("host"));
            int port = integer(observation.get("port"), -1);
            if (host.isEmpty() || port < 1) continue;
            Map<String, Object> endpoint = new LinkedHashMap<>();
            endpoint.put("host", host);
            endpoint.put("port", Integer.valueOf(port));
            endpoint.put("protocol", "tcp");
            endpoint.put("state", "open");
            endpoint.put("endpointId", "tcp|" + host + "|" + port);
            endpoint.put("discoveredAt", Long.valueOf(System.currentTimeMillis()));
            if (observation.get("latencyMs") != null) endpoint.put("responseTime", observation.get("latencyMs"));
            endpoint.put("probe", "connect");
            byEndpoint.putIfAbsent(host + ":" + port, endpoint);
        }
    }

    private static void applyServiceEvidence(Map<String, Map<String, Object>> endpointsByKey,
                                              Collection<Map<String, Object>> observations) {
        for (Map<String, Object> observation : observations) {
            Map<String, Object> endpoint = endpointsByKey.get(endpointKey(observation));
            if (endpoint != null) ServiceIdentificationPolicy.applyServiceObservation(endpoint, observation);
        }
    }

    private static void applyFingerprintMatches(List<Map<String, Object>> endpoints, List<Map<String, Object>> matches) {
        Map<String, List<Map<String, Object>>> byEndpoint = new LinkedHashMap<>();
        for (Map<String, Object> match : matches)
            byEndpoint.computeIfAbsent(endpointKey(match), ignored -> new ArrayList<>()).add(match);
        for (Map<String, Object> endpoint : endpoints) {
            List<Map<String, Object>> results = byEndpoint.get(endpointKey(endpoint));
            if (results == null) continue;
            Map<String, Object> fingerprint = new LinkedHashMap<>();
            fingerprint.put("status", results.stream().anyMatch(result -> "PENDING".equals(result.get("status")))
                    ? "RUNNING" : results.stream().anyMatch(result -> "CANCELLED".equals(result.get("status"))) ? "CANCELLED" : "COMPLETED");
            fingerprint.put("components", results.stream().filter(result -> Boolean.TRUE.equals(result.get("matched"))).toList());
            fingerprint.put("completed", results.stream().filter(result -> Boolean.TRUE.equals(result.get("complete"))).count());
            fingerprint.put("total", results.size());
            fingerprint.put("errorCount", results.stream().filter(result -> "ERROR".equals(result.get("status"))).count());
            fingerprint.put("inconclusiveCount", results.stream().filter(result -> "INCONCLUSIVE".equals(result.get("status"))).count());
            endpoint.put("fingerprint", fingerprint);
        }
    }
}
