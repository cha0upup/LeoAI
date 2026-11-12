package org.leo.service.discovery;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class HttpFingerprintEvaluatorTest {
    @ParameterizedTest
    @CsvSource({"all,404,NOT_MATCHED", "all,200,INCONCLUSIVE", "any,200,MATCHED", "any,404,INCONCLUSIVE"})
    void truncatedEvidencePropagatesThroughBooleanExpressionsWithoutBecomingAMismatch(
            String mode, int status, String expected) {
        var unknown = Map.of("field", "body", "value", "absent");
        var known = Map.of("field", "status", "operator", "equals", "value", status);
        var observation = observation("probe-0", 200, "prefix", "", true, false);
        for (var children : List.of(List.of(unknown, known), List.of(known, unknown))) {
            Map<String, Object> expression = Map.of(mode, children);
            var decision = evaluate(expression, Map.of(), Map.of(0, observation), 1, false);
            assertEquals(expected, decision.status());
            assertEquals("MATCHED".equals(expected), decision.matched());
            assertEquals(expected, evaluate(Map.of("not", Map.of("not", expression)), Map.of(),
                    Map.of(0, observation), 1, false).status());
        }
    }

    @Test
    void missingRequestSlotsRemainATaskErrorRatherThanARuleDecision() {
        var error = assertThrows(IllegalArgumentException.class, () -> evaluate(
                Map.of("field", "body", "value", "marker"), Map.of(),
                Map.of(1, observation("probe-1", 200, "marker", "", false, false)), 2, false));
        assertEquals("探测证据不完整", error.getMessage());
    }

    @Test
    void failedRequestsAndNonNumericHttpStatusesTakePrecedenceOverPositiveConditions() {
        var valid = observation("probe-0", 200, "marker", "", false, false);
        var failed = new LinkedHashMap<>(valid);
        failed.put("error", "fixture timeout");
        failed.put("errorCode", "TIMEOUT");
        for (var unusable : List.of(failed, observation("probe-1", "200", "marker", "", false, false))) {
            var decision = evaluate(Map.of("any", List.of(Map.of("request", 0, "field", "body", "value", "marker"))),
                    Map.of(), Map.of(0, valid, 1, unusable), 2, true);
            assertEquals("ERROR", decision.status());
            assertFalse(decision.matched());
            assertNotNull(decision.error());
            assertEquals("ERROR", ((Map<?, ?>) ((List<?>) decision.details().get("conditions")).get(0)).get("status"));
        }
    }

    @Test
    void versionExtractionUsesTheSelectedRequestAndOnlyConclusiveObservedBoundaries() {
        var rule = Map.<String, Object>of("field", "status", "operator", "equals", "value", 200);
        var extractor = Map.<String, Object>of("request", 1, "field", "headers", "prefix", "Server/", "suffix", ";");
        var complete = evaluate(rule, extractor, Map.of(
                0, observation("probe-0", 200, "", "Server/9.9;", false, false),
                1, observation("probe-1", 200, "", "Server/2.4.5;", false, false)), 2, false);
        assertEquals("MATCHED", complete.status());
        assertEquals("2.4.5", complete.details().get("detectedVersion"));
        assertEquals(Map.of("request", 1, "field", "headers", "probeId", "probe-1", "offset", 7), complete.details().get("versionEvidence"));
        var truncated = evaluate(rule, Map.of("field", "headers", "prefix", "Server/"),
                Map.of(0, observation("probe-0", 200, "", "Server/2.4", false, true)), 1, false);
        assertEquals("INCONCLUSIVE", truncated.details().get("versionStatus"));
        assertFalse(truncated.details().containsKey("detectedVersion"));
        var absent = evaluate(rule, Map.of("field", "headers", "prefix", "missing/"),
                Map.of(0, observation("probe-0", 200, "", "Server/2.4.5;", false, false)), 1, false);
        assertEquals("NOT_FOUND", absent.details().get("versionStatus"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void diagnosticsAreDetachedFromRuleInputsAndRemainIndependentOfLaterEvaluations() {
        var leaf = new LinkedHashMap<String, Object>(Map.of("field", "body", "value", "marker"));
        var expression = new LinkedHashMap<String, Object>(Map.of("all", List.of(leaf)));
        var evidence = Map.of(0, observation("probe-0", 200, "marker", "", false, false));
        var decision = evaluate(expression, Map.of(), evidence, 1, true);
        var trace = (List<Map<String, Object>>) decision.details().get("conditions");
        assertEquals(List.of("match", "match.all[0]"), trace.stream().map(row -> row.get("path")).toList());
        var root = (Map<String, Object>) trace.get(0).get("expression");
        ((Map<String, Object>) ((List<?>) root.get("all")).get(0)).put("value", "changed");
        ((Map<String, Object>) trace.get(1).get("expression")).put("value", "changed-child");
        assertEquals("marker", leaf.get("value"));
        assertEquals("MATCHED", evaluate(expression, Map.of(), evidence, 1, false).status());
        assertTrue(evaluate(expression, Map.of(), evidence, 1, false).details().isEmpty());
    }

    private static HttpFingerprintEvaluator.Decision evaluate(Map<String, Object> expression, Map<String, Object> version,
            Map<Integer, Map<String, Object>> observations, int count, boolean debug) {
        return HttpFingerprintEvaluator.evaluateCompleted(expression, version, observations, count, debug);
    }

    private static Map<String, Object> observation(String probeId, Object status, String body, String headers,
                                                  boolean truncated, boolean headersTruncated) {
        return Map.of("probeId", probeId, "evidence", Map.of("statusCode", status, "body", body,
                "headers", headers, "bodyLength", body.length(), "truncated", truncated, "headersTruncated", headersTruncated));
    }
}
