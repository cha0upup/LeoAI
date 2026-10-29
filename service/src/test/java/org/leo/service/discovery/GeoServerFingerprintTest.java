package org.leo.service.discovery;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.leo.core.util.json.JsonUtil;
import org.leo.service.fingerprint.FingerprintManageService;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class GeoServerFingerprintTest {

    private static final String RESOURCE =
            "<link rel=\"stylesheet\" href=\"./wicket/resource/org.geoserver.web.GeoServerBasePage/css/geoserver-ver-fixture.css\"/>";
    private static final String WELCOME = "<html><head><title>GeoServer: Welcome</title>"
            + RESOURCE + "</head><body>Fixture</body></html>";
    private final NetworkProbeAnalysisService service =
            new NetworkProbeAnalysisService(new FingerprintManageService());

    @Test
    void validatesTheActualBundledRuleAndUsesOnlyBoundedReadRequests() throws Exception {
        Map<String, Object> seed = seed();
        assertEquals("geoserver_any", seed.get("fingerprintId"));
        assertEquals("geoserver", seed.get("name"));
        assertEquals("http", seed.get("protocol"));
        assertEquals("any", ((Map<?, ?>) seed.get("info")).get("version"));
        assertTrue(((List<?>) seed.get("tags")).contains("gis"));
        Map<?, ?> rule = (Map<?, ?>) seed.get("rule");
        assertFalse(rule.containsKey("version"));
        new FingerprintManageService().validateRule(rule);

        var prepared = prepare("http://fixture.test:8080/");
        var probes = probes(prepared);
        assertEquals(2, probes.size());
        assertEquals(List.of("/geoserver/web/", "/web/"), paths(prepared));
        for (Map<String, Object> probe : probes) {
            Map<?, ?> request = (Map<?, ?>) probe.get("httpRequest");
            assertEquals("GET", request.get("method"));
            assertEquals("", request.get("body"));
            assertEquals(8192, probe.get("maxReadBytes"));
            assertEquals(5000, probe.get("timeout"));
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1})
    void identifiesDefaultAndRootDeploymentsWhenTheOtherPathReturns404(int matchingRequest) throws Exception {
        var match = evaluate(matchingRequest == 0 ? evidence(200, WELCOME) : evidence(404, "Not found"),
                matchingRequest == 1 ? evidence(200, WELCOME) : evidence(404, "Not found"));
        assertEquals("MATCHED", match.get("status"));
        assertEquals(true, match.get("matched"));
        assertEquals("geoserver_any", match.get("ruleId"));
        assertEquals(List.of(0, 1), match.get("requestIndices"));
        assertFalse(match.containsKey("detectedVersion"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"<title>GeoServer: 欢迎</title>", "<TITLE>GEOSERVER: WELCOME</TITLE>"})
    void acceptsLocalizedTitlesAndCaseVariations(String title) throws Exception {
        assertEquals("MATCHED", evaluate(evidence(200, title + RESOURCE), evidence(404, "Not found")).get("status"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "<title>Documentation</title><p>Connect to GeoServer at /geoserver/web/</p>",
            "<title>GeoServer: Welcome</title><p>A page without product resources</p>",
            "<title>Another application</title><p>GeoServer</p>"
                    + "<link href=\"wicket/resource/org.geoserver.web.GeoServerBasePage/css/geoserver.css\"/>",
            "<title>Apache Tomcat</title><p>The requested resource /geoserver/web/ is not available</p>",
            "<title>GeoServer</title><link href=\"wicket/resource/org.example.web.Page/style.css\"/>"
    })
    void rejectsKeywordOnlyPagesAndUnrelatedApplications(String body) throws Exception {
        assertEquals("NOT_MATCHED", evaluate(evidence(200, body), evidence(404, "Not found")).get("status"));
    }

    @ParameterizedTest
    @ValueSource(ints = {301, 302, 401, 403, 404, 500})
    void requiresSuccessfulHttpEvidenceEvenWhenAnErrorPageContainsAllMarkers(int status) throws Exception {
        assertEquals("NOT_MATCHED", evaluate(evidence(status, WELCOME), evidence(404, WELCOME)).get("status"));
    }

    @Test
    void neverCombinesTitleResourcesAndStatusFromDifferentRequests() throws Exception {
        assertEquals("NOT_MATCHED", evaluate(evidence(200, "<title>GeoServer: Welcome</title>"),
                evidence(200, RESOURCE)).get("status"));
        assertEquals("NOT_MATCHED", evaluate(evidence(200, "Unrelated application"),
                evidence(404, WELCOME)).get("status"));
    }

    @Test
    void keepsTransportErrorsAndTruncatedEvidenceDistinctFromNegativeMatches() throws Exception {
        assertEquals("ERROR", evaluate(Map.of("error", "fixture timeout"), evidence(404, "Not found")).get("status"));
        assertEquals("INCONCLUSIVE", evaluate(Map.of("statusCode", 200,
                "body", "<title>GeoServer: Welcome</title>", "truncated", true), evidence(404, "Not found")).get("status"));
        assertEquals("MATCHED", evaluate(Map.of("statusCode", 200, "body", WELCOME, "truncated", true),
                evidence(404, "Not found")).get("status"));
    }

    @ParameterizedTest
    @CsvSource({
            "http://fixture.test:8080/geoserver/,/geoserver/geoserver/web/,/geoserver/web/",
            "http://fixture.test:8080/maps/,/maps/geoserver/web/,/maps/web/"
    })
    void retainsExplicitApplicationContexts(String baseUrl, String firstPath, String secondPath) throws Exception {
        assertEquals(List.of(firstPath, secondPath), paths(prepare(baseUrl)));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> seed() throws Exception {
        try (InputStream input = getClass().getResourceAsStream("/fingerprint/geoserver_any.json")) {
            assertNotNull(input, "GeoServer seed must be packaged with core resources");
            return (Map<String, Object>) JsonUtil.fromJsonString(
                    new String(input.readAllBytes(), StandardCharsets.UTF_8), Map.class);
        }
    }

    private NetworkProbeAnalysisService.PreparedScan prepare(String baseUrl) throws Exception {
        return service.prepare(Map.of("kind", "fingerprint", "rules", List.of(seed()), "targets", List.of(
                Map.of("host", "fixture.test", "port", 8080, "protocol", "http", "baseUrl", baseUrl))));
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> probes(NetworkProbeAnalysisService.PreparedScan prepared) {
        return (List<Map<String, Object>>) prepared.plan().get("targets");
    }

    private List<String> paths(NetworkProbeAnalysisService.PreparedScan prepared) {
        return probes(prepared).stream().map(probe ->
                String.valueOf(((Map<?, ?>) probe.get("httpRequest")).get("path"))).toList();
    }

    private Map<String, Object> evidence(int status, String body) {
        return Map.of("statusCode", status, "body", body);
    }

    private Map<?, ?> evaluate(Map<String, Object> first, Map<String, Object> second) throws Exception {
        var prepared = prepare("http://fixture.test:8080/");
        String id = UUID.randomUUID().toString();
        service.register(id, prepared);
        List<Map<String, Object>> observations = new ArrayList<>();
        List<Map<String, Object>> samples = List.of(first, second);
        var probes = probes(prepared);
        for (int index = 0; index < probes.size(); index++) {
            Map<String, Object> observation = new LinkedHashMap<>(probes.get(index));
            Map<String, Object> sample = samples.get(index);
            if (sample.containsKey("error")) observation.put("error", sample.get("error"));
            else observation.put("evidence", sample);
            observations.add(observation);
        }
        Map<String, Object> response = new LinkedHashMap<>(Map.of("result",
                Map.of("status", "STOPPED", "observations", observations)));
        service.enrich(id, response);
        return (Map<?, ?>) ((List<?>) ((Map<?, ?>) ((Map<?, ?>) response.get("result")).get("analysis")).get("matches")).get(0);
    }
}
