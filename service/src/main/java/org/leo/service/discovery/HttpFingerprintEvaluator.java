package org.leo.service.discovery;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.leo.service.discovery.NetworkProbePayloads.boundedInt;
import static org.leo.service.discovery.NetworkProbePayloads.copyMap;
import static org.leo.service.discovery.NetworkProbePayloads.defaultText;
import static org.leo.service.discovery.NetworkProbePayloads.text;

/** Evaluates one complete HTTP evidence group; no rule loading, task state, scheduling or I/O. */
final class HttpFingerprintEvaluator {
    private static final Pattern VERSION_TOKEN = Pattern.compile(
            "[0-9][0-9A-Za-z._+\\-]{0,63}(?![0-9A-Za-z._+\\-])");

    private HttpFingerprintEvaluator() { }

    record Decision(String status, boolean matched, String error, Map<String, Object> details) { }

    static Decision evaluateCompleted(Map<String, Object> expression, Map<String, Object> version,
                                      Map<Integer, Map<String, Object>> observations, int requestCount,
                                      boolean debug) {
        // Missing request evidence is a task error, not a rule mismatch.
        List<Map<String, Object>> responses = responses(observations, requestCount);
        boolean matched = false;
        String error = null;
        String status;
        try {
            if (responses.stream().anyMatch(HttpFingerprintEvaluator::isFailedResponse)) {
                status = "ERROR";
                error = "请求失败或未收到 HTTP 响应";
            } else {
                matched = evaluate(expression, responses);
                status = matched ? "MATCHED" : "NOT_MATCHED";
            }
        } catch (IncompleteEvidence incomplete) {
            status = "INCONCLUSIVE";
            error = incomplete.getMessage();
        } catch (RuntimeException failure) {
            status = "ERROR";
            error = failure.getMessage();
        }
        Map<String, Object> details = new LinkedHashMap<>();
        if (matched && !version.isEmpty()) extractVersion(details, version, responses, observations);
        if (debug) details.put("conditions", conditionTrace(expression, responses, "match"));
        return new Decision(status, matched, error, details);
    }

    private static List<Map<String, Object>> responses(Map<Integer, Map<String, Object>> observations, int count) {
        List<Map<String, Object>> responses = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            Map<String, Object> observation = observations.get(index);
            if (observation == null) throw new IllegalArgumentException("探测证据不完整");
            Map<String, Object> response = new LinkedHashMap<>();
            if (observation.get("error") != null) {
                response.put("error", observation.get("error"));
                response.put("errorCode", observation.get("errorCode"));
            }
            Map<String, Object> evidence = observation.get("evidence") instanceof Map<?, ?> map
                    ? copyMap(map) : Collections.emptyMap();
            response.put("status", evidence.get("statusCode"));
            response.put("body", evidence.get("body") == null ? "" : String.valueOf(evidence.get("body")));
            response.put("bodyLength", evidence.getOrDefault("bodyLength", Integer.valueOf(0)));
            response.put("truncated", evidence.getOrDefault("truncated", Boolean.FALSE));
            response.put("headersTruncated", evidence.getOrDefault("headersTruncated", Boolean.FALSE));
            response.put("headers", evidence.get("headers") == null ? "" : String.valueOf(evidence.get("headers")));
            response.put("raw", String.valueOf(evidence.getOrDefault("statusCode", "")) + "\r\n"
                    + response.get("headers") + "\r\n\r\n" + response.get("body"));
            responses.add(response);
        }
        return responses;
    }

    private static boolean isFailedResponse(Map<String, Object> response) {
        return response.containsKey("error") || !(response.get("status") instanceof Number code) || code.intValue() < 100;
    }

    private static boolean evaluate(Map<?, ?> expression, List<Map<String, Object>> responses) {
        boolean all = expression.containsKey("all");
        if (all || expression.containsKey("any")) {
            // ALL settles on false and ANY on true, even after incomplete evidence.
            boolean shortCircuitResult = !all;
            IncompleteEvidence incomplete = null;
            // Rules are validated and snapshotted before evaluation; children are read-only here.
            for (Object child : (List<?>) expression.get(all ? "all" : "any")) {
                try { if (evaluate((Map<?, ?>) child, responses) == shortCircuitResult) return shortCircuitResult; }
                catch (IncompleteEvidence error) { incomplete = error; }
            }
            if (incomplete != null) throw incomplete;
            return all;
        }
        if (expression.get("not") instanceof Map<?, ?> child) return !evaluate(child, responses);

        int requestIndex = boundedInt(expression.get("request"), 0, 0, Math.max(0, responses.size() - 1));
        Map<String, Object> response = responses.get(requestIndex);
        String field = text(expression.get("field"));
        Object actualValue = response.get(field);
        String operator = defaultText(expression.get("operator"), "contains").toLowerCase(Locale.ROOT);
        boolean incomplete = (Boolean.TRUE.equals(response.get("headersTruncated")) && List.of("headers", "raw").contains(field))
                || (Boolean.TRUE.equals(response.get("truncated")) && List.of("body", "raw", "bodyLength").contains(field));
        boolean ignoreCase = !Boolean.FALSE.equals(expression.get("ignoreCase"));
        Object expectedValue = expression.get("value");
        if (incomplete) {
            String prefix = actualValue == null ? "" : String.valueOf(actualValue);
            String needle = expectedValue == null ? "" : String.valueOf(expectedValue);
            if (ignoreCase) { prefix = prefix.toLowerCase(Locale.ROOT); needle = needle.toLowerCase(Locale.ROOT); }
            // A positive substring is conclusive even in a prefix. Its absence is unknown,
            // and must remain unknown through any enclosing NOT expression.
            if ("contains".equals(operator) && prefix.contains(needle)) return true;
            if ("notcontains".equals(operator) && prefix.contains(needle)) return false;
            if ("startswith".equals(operator) && prefix.startsWith(needle)) return true;
            if ("exists".equals(operator) && !prefix.isEmpty()) return true;
            throw new IncompleteEvidence("响应已截断，无法完整判断该条件");
        }
        if ("in".equals(operator)) {
            if (!(expectedValue instanceof Collection<?> values)) return false;
            for (Object expected : values) if (equalsValue(actualValue, expected, ignoreCase)) return true;
            return false;
        }
        if ("equals".equals(operator)) return equalsValue(actualValue, expectedValue, ignoreCase);
        if ("exists".equals(operator)) return actualValue != null && !String.valueOf(actualValue).isEmpty();
        String actual = actualValue == null ? "" : String.valueOf(actualValue);
        String expected = expectedValue == null ? "" : String.valueOf(expectedValue);
        if (ignoreCase) {
            actual = actual.toLowerCase(Locale.ROOT);
            expected = expected.toLowerCase(Locale.ROOT);
        }
        if ("startswith".equals(operator)) return actual.startsWith(expected);
        if ("endswith".equals(operator)) return actual.endsWith(expected);
        if ("notcontains".equals(operator)) return !actual.contains(expected);
        if (!"contains".equals(operator)) throw new IllegalArgumentException("不支持的匹配操作: " + operator);
        return actual.contains(expected);
    }

    /** Literal boundaries and a bounded version token avoid running user-supplied regex. */
    private static void extractVersion(Map<String, Object> match, Map<String, Object> extractor,
                                List<Map<String, Object>> responses, Map<Integer, Map<String, Object>> observations) {
        int index = ((Number) extractor.getOrDefault("request", 0)).intValue();
        Map<String, Object> response = responses.get(index);
        String field = text(extractor.get("field"));
        String source = String.valueOf(response.getOrDefault(field, ""));
        String prefix = String.valueOf(extractor.get("prefix"));
        boolean ignoreCase = !Boolean.FALSE.equals(extractor.get("ignoreCase"));
        boolean truncated = Boolean.TRUE.equals(response.get("headers".equals(field) ? "headersTruncated" : "truncated"));
        String suffix = String.valueOf(extractor.getOrDefault("suffix", ""));
        Matcher token = VERSION_TOKEN.matcher(source);
        int offset = 0;
        while ((offset = literalIndexOf(source, prefix, offset, ignoreCase)) >= 0) {
            int start = offset + prefix.length();
            token.region(start, source.length());
            if (token.lookingAt()) {
                int end = token.end();
                boolean suffixMatches = suffix.isEmpty() || source.regionMatches(ignoreCase, end, suffix, 0, suffix.length());
                if (suffixMatches && !(truncated && end == source.length())) {
                    match.put("detectedVersion", token.group());
                    match.put("versionStatus", "EXTRACTED");
                    match.put("versionEvidence", Map.of("request", index, "field", field,
                            "probeId", text(observations.get(index).get("probeId")), "offset", start));
                    return;
                }
            }
            offset = start;
        }
        match.put("versionStatus", truncated ? "INCONCLUSIVE" : "NOT_FOUND");
    }

    private static int literalIndexOf(String source, String prefix, int offset, boolean ignoreCase) {
        for (int i = offset; i <= source.length() - prefix.length(); i++)
            if (source.regionMatches(ignoreCase, i, prefix, 0, prefix.length())) return i;
        return -1;
    }

    private static List<Map<String, Object>> conditionTrace(Map<String, Object> expression,
                                                     List<Map<String, Object>> responses, String path) {
        List<Map<String, Object>> trace = new ArrayList<>();
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("path", path);
        item.put("expression", copyMap(expression));
        try {
            int index = boundedInt(expression.get("request"), 0, 0, responses.size() - 1);
            List<Map<String, Object>> relevant = expression.containsKey("field") ? List.of(responses.get(index)) : responses;
            if (relevant.stream().anyMatch(HttpFingerprintEvaluator::isFailedResponse)) {
                item.put("status", "ERROR");
            } else item.put("status", evaluate(expression, responses) ? "MATCHED" : "NOT_MATCHED");
        } catch (IncompleteEvidence error) { item.put("status", "INCONCLUSIVE"); }
        catch (RuntimeException error) { item.put("status", "ERROR"); }
        trace.add(item);
        for (String key : List.of("all", "any")) {
            if (expression.get(key) instanceof List<?> children) {
                for (int i = 0; i < children.size(); i++)
                    trace.addAll(conditionTrace(copyMap((Map<?, ?>) children.get(i)), responses, path + "." + key + "[" + i + "]"));
            }
        }
        if (expression.get("not") instanceof Map<?, ?> child)
            trace.addAll(conditionTrace(copyMap(child), responses, path + ".not"));
        return trace;
    }

    private static boolean equalsValue(Object actual, Object expected, boolean ignoreCase) {
        if (actual instanceof Number && expected instanceof Number) {
            return Double.compare(((Number) actual).doubleValue(), ((Number) expected).doubleValue()) == 0;
        }
        String left = actual == null ? "" : String.valueOf(actual);
        String right = expected == null ? "" : String.valueOf(expected);
        return ignoreCase ? left.equalsIgnoreCase(right) : left.equals(right);
    }

    private static final class IncompleteEvidence extends RuntimeException {
        private IncompleteEvidence(String message) { super(message); }
    }
}
