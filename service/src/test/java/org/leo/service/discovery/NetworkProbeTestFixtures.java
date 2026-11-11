package org.leo.service.discovery;

import org.leo.core.agent.capability.NetworkProbeCapable;
import org.leo.service.fingerprint.FingerprintManageService;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Shared stateless test responses; stateful controls stay in each test node. */
final class NetworkProbeTestFixtures {
    private NetworkProbeTestFixtures() {}

    interface StubNode extends NetworkProbeCapable {
        @Override
        default Map<String, Object> ackNetworkProbe(String taskId, long cursor) {
            return Map.of("code", 200, "cursor", cursor);
        }

        @Override
        default Map<String, Object> pauseNetworkProbe(String taskId) {
            return Map.of("code", 200);
        }

        @Override
        default Map<String, Object> resumeNetworkProbe(String taskId) {
            return Map.of("code", 200);
        }

        @Override
        default Map<String, Object> stopNetworkProbe(String taskId) {
            return Map.of("code", 200);
        }

        @Override
        default Map<String, Object> releaseNetworkProbe(String taskId) {
            return Map.of("code", 200);
        }
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> cursorPage(Map<String, Object> response, long cursor) {
        if (!Integer.valueOf(200).equals(response.get("code"))) return response;
        Map<String, Object> page = new LinkedHashMap<>((Map<String, Object>) response.get("result"));
        page.put("cursor", cursor);
        page.put("nextCursor", cursor + ((List<?>) page.get("observations")).size());
        page.put("hasMore", false);
        return Map.of("code", 200, "result", page);
    }

    static final class FingerprintLibrary extends FingerprintManageService {
        private final Map<String, HashMap<String, Object>> fingerprints;

        FingerprintLibrary(String name, String matchField) {
            HashMap<String, Object> fingerprint = new HashMap<>();
            fingerprint.put("fingerprintId", "web_any");
            fingerprint.put("name", name);
            fingerprint.put("protocol", "http");
            fingerprint.put("tags", List.of("web", "server"));
            fingerprint.put("rule", Map.of(
                    "requests", List.of(Map.of("body", "")),
                    "match", Map.of("field", matchField, "operator", "contains", "value", name)));
            fingerprints = Map.of("web_any", fingerprint);
        }

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
    }
}
