package org.leo.web.service;

import org.leo.service.fingerprint.FingerprintManageService;
import org.springframework.stereotype.Service;

import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Builds fingerprint probe plans and evaluates their bounded evidence on the service side. */
@Service
public class NetworkProbeAnalysisService {

    private static final int MAX_PROBES = 128;
    private static final int MAX_RULES = 64;
    private static final int MAX_READ_BYTES = 8192;
    private static final long CONTEXT_TTL_MS = 2L * 60L * 60L * 1000L;

    private final FingerprintManageService fingerprintManageService;
    private final Map<String, ScanContext> contexts = new ConcurrentHashMap<>();

    public NetworkProbeAnalysisService(FingerprintManageService fingerprintManageService) {
        this.fingerprintManageService = fingerprintManageService;
    }

    public PreparedScan prepare(Object value) throws Exception {
        if (!(value instanceof Map<?, ?> rawScan)) {
            throw new IllegalArgumentException("scan必须是对象");
        }
        String kind = text(rawScan.get("kind")).toLowerCase(Locale.ROOT);
        if (!("fingerprint".equals(kind) || "recon".equals(kind))) {
            throw new IllegalArgumentException("scan.kind必须是fingerprint或recon");
        }
        List<Map<String, Object>> sourceTargets = mapList(rawScan.get("targets"), "scan.targets");
        if (sourceTargets.isEmpty()) throw new IllegalArgumentException("scan.targets不能为空");
        List<RuleDefinition> rules = "fingerprint".equals(kind)
                ? resolveFingerprintRules(rawScan.get("fingerprintIds"))
                : resolveReconRules(rawScan.get("ruleSelector"));
        if (rules.isEmpty()) throw new IllegalArgumentException("没有匹配到可执行的指纹规则");
        if (rules.size() > MAX_RULES) throw new IllegalArgumentException("一次最多执行" + MAX_RULES + "条指纹规则");

        int threads = boundedInt(rawScan.get("threads"), 10, 1, 64);
        Map<String, Map<String, Object>> targetsById = new LinkedHashMap<>();
        for (Map<String, Object> target : sourceTargets) {
            targetsById.putIfAbsent(targetId(target), target);
        }

        List<Map<String, Object>> probes = new ArrayList<>();
        List<WorkGroup> groups = new ArrayList<>();
        Set<String> ruleIds = new LinkedHashSet<>();
        for (Map.Entry<String, Map<String, Object>> targetEntry : targetsById.entrySet()) {
            String targetId = targetEntry.getKey();
            Map<String, Object> target = targetEntry.getValue();
            String targetProtocol = targetProtocol(target);
            for (RuleDefinition rule : rules) {
                if (!compatibleProtocol(targetProtocol, rule.protocol())) continue;
                groups.add(new WorkGroup(targetId, rule.id(), rule.requests().size()));
                ruleIds.add(rule.id());
                for (int requestIndex = 0; requestIndex < rule.requests().size(); requestIndex++) {
                    probes.add(buildProbe(target, targetId, rule, requestIndex));
                    if (probes.size() > MAX_PROBES) {
                        throw new IllegalArgumentException("目标与指纹请求组合不能超过" + MAX_PROBES + "个");
                    }
                }
            }
        }
        if (probes.isEmpty()) throw new IllegalArgumentException("目标协议与所选指纹规则不匹配");

        Map<String, Object> limits = new LinkedHashMap<>();
        limits.put("threads", Integer.valueOf(Math.min(threads, probes.size())));
        limits.put("timeout", Integer.valueOf(maxTimeout(probes)));
        limits.put("maxReadBytes", Integer.valueOf(maxReadBytes(probes)));
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("targets", probes);
        plan.put("stages", List.of("tcp-exchange", "http-request"));
        plan.put("limits", limits);

        Map<String, RuleDefinition> rulesById = new LinkedHashMap<>();
        for (RuleDefinition rule : rules) rulesById.put(rule.id(), rule);
        ScanContext context = new ScanContext(kind, rulesById, groups,
                targetsById.keySet(), ruleIds, System.currentTimeMillis());
        return new PreparedScan(plan, context);
    }

    public void register(String taskId, PreparedScan prepared) {
        if (taskId == null || taskId.isBlank() || prepared == null) return;
        cleanup();
        contexts.put(taskId, prepared.context());
    }

    public void enrich(String taskId, Map<String, Object> componentResult) {
        ScanContext context = contexts.get(taskId);
        if (context == null || componentResult == null) return;
        Object snapshotValue = componentResult.get("result");
        if (!(snapshotValue instanceof Map<?, ?> rawSnapshot)) return;
        Map<String, Object> snapshot = castMap(rawSnapshot);
        List<Map<String, Object>> observations = mapListOrEmpty(snapshot.get("observations"));
        Map<String, Map<Integer, Map<String, Object>>> grouped = groupObservations(observations);

        List<Map<String, Object>> matches = new ArrayList<>();
        int completed = 0;
        int hitCount = 0;
        for (WorkGroup group : context.groups()) {
            String key = groupKey(group.targetId(), group.ruleId());
            Map<Integer, Map<String, Object>> groupObservations = grouped.getOrDefault(key, Map.of());
            boolean complete = groupObservations.size() >= group.requestCount();
            boolean matched = false;
            String matchError = null;
            if (complete) {
                completed++;
                try {
                    List<Map<String, Object>> responses = responses(groupObservations, group.requestCount());
                    matched = evaluate(context.rulesById().get(group.ruleId()).match(), responses);
                    if (matched) hitCount++;
                } catch (RuntimeException error) {
                    matchError = error.getMessage();
                }
            }
            Map<String, Object> match = new LinkedHashMap<>();
            match.put("targetId", group.targetId());
            match.put("ruleId", group.ruleId());
            match.put("complete", Boolean.valueOf(complete));
            match.put("matched", Boolean.valueOf(matched));
            match.put("evidenceCount", Integer.valueOf(groupObservations.size()));
            if (matchError != null && !matchError.isBlank()) match.put("error", matchError);
            matches.add(match);
        }

        Map<String, Object> analysis = new LinkedHashMap<>();
        analysis.put("kind", context.kind());
        analysis.put("total", Integer.valueOf(context.groups().size()));
        analysis.put("completed", Integer.valueOf(completed));
        analysis.put("targetCount", Integer.valueOf(context.targetIds().size()));
        analysis.put("ruleCount", Integer.valueOf(context.ruleIds().size()));
        analysis.put("hitCount", Integer.valueOf(hitCount));
        analysis.put("matches", matches);
        snapshot.put("analysis", analysis);
        componentResult.put("result", snapshot);
        if ("STOPPED".equals(String.valueOf(snapshot.get("status")))) context.finishedAt = System.currentTimeMillis();
        cleanup();
    }

    private List<RuleDefinition> resolveFingerprintRules(Object value) throws Exception {
        List<?> ids;
        if (value instanceof List<?> list) ids = list;
        else if (value == null) ids = List.of();
        else ids = List.of(value);
        List<RuleDefinition> result = new ArrayList<>();
        for (Object idValue : ids) {
            String id = text(idValue);
            if (id.isEmpty()) continue;
            result.add(toRule(fingerprintManageService.getFingerprintById(id)));
        }
        return result;
    }

    private List<RuleDefinition> resolveReconRules(Object value) throws Exception {
        Map<?, ?> selector = value instanceof Map<?, ?> map ? map : Map.of();
        Object idsValue = selector.get("fingerprintIds");
        if (idsValue instanceof List<?> ids && !ids.isEmpty()) return resolveFingerprintRules(ids);
        String protocol = text(selector.get("protocol")).toLowerCase(Locale.ROOT);
        Set<String> tags = textSet(selector.get("tags"));
        List<Map<String, Object>> summaries = protocol.isEmpty()
                ? fingerprintManageService.listFingerprints()
                : fingerprintManageService.getFingerprintsByProtocol(protocol);
        List<RuleDefinition> result = new ArrayList<>();
        for (Map<String, Object> summary : summaries) {
            if (!tags.isEmpty() && disjoint(tags, textSet(summary.get("tags")))) continue;
            result.add(toRule(fingerprintManageService.getFingerprintById(text(summary.get("fingerprintId")))));
        }
        return result;
    }

    private RuleDefinition toRule(Map<String, Object> fingerprint) {
        String id = text(fingerprint.get("fingerprintId"));
        String protocol = text(fingerprint.get("protocol")).toLowerCase(Locale.ROOT);
        if (!(fingerprint.get("rule") instanceof Map<?, ?> rawRule)) {
            throw new IllegalArgumentException("指纹缺少rule: " + id);
        }
        List<Map<String, Object>> requests = mapList(rawRule.get("requests"), "rule.requests");
        if (requests.isEmpty()) throw new IllegalArgumentException("指纹请求不能为空: " + id);
        if (!(rawRule.get("match") instanceof Map<?, ?> rawMatch)) {
            throw new IllegalArgumentException("指纹缺少声明式match: " + id);
        }
        return new RuleDefinition(id, protocol, requests, castMap(rawMatch));
    }

    private Map<String, Object> buildProbe(Map<String, Object> source, String targetId,
                                           RuleDefinition rule, int requestIndex) {
        Map<String, Object> request = rule.requests().get(requestIndex);
        Map<String, Object> probe = new LinkedHashMap<>();
        copyIfPresent(source, probe, "host");
        copyIfPresent(source, probe, "port");
        copyIfPresent(source, probe, "baseUrl");
        copyIfPresent(source, probe, "protocol");
        probe.put("targetId", targetId);
        probe.put("probeId", targetId + "|" + rule.id() + "|" + requestIndex);
        probe.put("ruleId", rule.id());
        probe.put("requestIndex", Integer.valueOf(requestIndex));
        probe.put("timeout", Integer.valueOf(boundedInt(request.get("timeout"), 3000, 100, 300000)));
        probe.put("maxReadBytes", Integer.valueOf(boundedInt(request.get("maxBodyBytes"),
                MAX_READ_BYTES, 256, MAX_READ_BYTES)));
        if ("tcp".equals(rule.protocol())) {
            probe.put("protocol", "tcp");
            probe.put("stage", "tcp-exchange");
            if (request.containsKey("body")) probe.put("request", rawText(request.get("body")));
        } else {
            probe.put("stage", "http-request");
            Map<String, Object> httpRequest = new LinkedHashMap<>();
            httpRequest.put("method", defaultText(request.get("method"), "GET"));
            String path = text(request.get("uri"));
            if (path.isEmpty()) path = defaultText(request.get("path"), "/");
            httpRequest.put("path", path);
            httpRequest.put("charset", defaultText(request.get("charset"), "UTF-8"));
            copyIfPresent(request, httpRequest, "headers");
            copyIfPresent(request, httpRequest, "body");
            probe.put("httpRequest", httpRequest);
        }
        return probe;
    }

    private Map<String, Map<Integer, Map<String, Object>>> groupObservations(List<Map<String, Object>> observations) {
        Map<String, Map<Integer, Map<String, Object>>> grouped = new LinkedHashMap<>();
        for (Map<String, Object> observation : observations) {
            String targetId = text(observation.get("targetId"));
            String ruleId = text(observation.get("ruleId"));
            if (targetId.isEmpty() || ruleId.isEmpty()) continue;
            int requestIndex = boundedInt(observation.get("requestIndex"), 0, 0, 255);
            grouped.computeIfAbsent(groupKey(targetId, ruleId), ignored -> new LinkedHashMap<>())
                    .put(requestIndex, observation);
        }
        return grouped;
    }

    private List<Map<String, Object>> responses(Map<Integer, Map<String, Object>> observations, int count) {
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
                    ? castMap(map) : Map.of();
            String stage = text(observation.get("stage"));
            if ("tcp-exchange".equals(stage)) {
                response.put("raw", defaultText(evidence.get("banner"), ""));
                response.put("bodyLength", evidence.getOrDefault("bytes", Integer.valueOf(0)));
                response.put("truncated", evidence.getOrDefault("truncated", Boolean.FALSE));
            } else {
                response.put("status", evidence.get("statusCode"));
                response.put("body", defaultText(evidence.get("body"), ""));
                response.put("bodyLength", evidence.getOrDefault("bodyLength", Integer.valueOf(0)));
                response.put("truncated", evidence.getOrDefault("truncated", Boolean.FALSE));
                response.put("headers", defaultText(evidence.get("headers"), ""));
            }
            responses.add(response);
        }
        return responses;
    }

    private boolean evaluate(Map<String, Object> expression, List<Map<String, Object>> responses) {
        if (expression.containsKey("all")) {
            for (Map<String, Object> child : expressionList(expression.get("all"))) {
                if (!evaluate(child, responses)) return false;
            }
            return true;
        }
        if (expression.containsKey("any")) {
            for (Map<String, Object> child : expressionList(expression.get("any"))) {
                if (evaluate(child, responses)) return true;
            }
            return false;
        }
        if (expression.get("not") instanceof Map<?, ?> child) return !evaluate(castMap(child), responses);

        int requestIndex = boundedInt(expression.get("request"), 0, 0, Math.max(0, responses.size() - 1));
        Map<String, Object> response = responses.get(requestIndex);
        String field = text(expression.get("field"));
        Object actualValue = response.get(field);
        String operator = defaultText(expression.get("operator"), "contains").toLowerCase(Locale.ROOT);
        boolean ignoreCase = !Boolean.FALSE.equals(expression.get("ignoreCase"));
        Object expectedValue = expression.get("value");
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

    private List<Map<String, Object>> expressionList(Object value) {
        List<Map<String, Object>> result = mapList(value, "match expression");
        if (result.isEmpty()) throw new IllegalArgumentException("match表达式不能为空");
        return result;
    }

    private boolean equalsValue(Object actual, Object expected, boolean ignoreCase) {
        if (actual instanceof Number && expected instanceof Number) {
            return Double.compare(((Number) actual).doubleValue(), ((Number) expected).doubleValue()) == 0;
        }
        String left = actual == null ? "" : String.valueOf(actual);
        String right = expected == null ? "" : String.valueOf(expected);
        return ignoreCase ? left.equalsIgnoreCase(right) : left.equals(right);
    }

    private int maxTimeout(List<Map<String, Object>> probes) {
        int max = 3000;
        for (Map<String, Object> probe : probes) max = Math.max(max, ((Number) probe.get("timeout")).intValue());
        return max;
    }

    private int maxReadBytes(List<Map<String, Object>> probes) {
        int max = 256;
        for (Map<String, Object> probe : probes) max = Math.max(max, ((Number) probe.get("maxReadBytes")).intValue());
        return max;
    }

    private String targetId(Map<String, Object> target) {
        String protocol = targetProtocol(target);
        if (!"tcp".equals(protocol)) {
            String baseUrl = text(target.get("baseUrl"));
            if (!baseUrl.isEmpty()) return baseUrl;
        }
        String host = text(target.get("host"));
        int port = boundedInt(target.get("port"), -1, -1, 65535);
        if (host.isEmpty() && !text(target.get("baseUrl")).isEmpty()) {
            try {
                URL url = new URL(text(target.get("baseUrl")));
                host = url.getHost();
                port = url.getPort() > 0 ? url.getPort() : ("https".equals(url.getProtocol()) ? 443 : 80);
            } catch (Exception ignored) {
            }
        }
        if (host.isEmpty() || port <= 0) throw new IllegalArgumentException("扫描目标缺少host/port");
        return host + ":" + port;
    }

    private String targetProtocol(Map<String, Object> target) {
        String protocol = text(target.get("protocol")).toLowerCase(Locale.ROOT);
        if ("https".equals(protocol)) return "http";
        if (!protocol.isEmpty()) return protocol;
        String baseUrl = text(target.get("baseUrl")).toLowerCase(Locale.ROOT);
        return baseUrl.startsWith("http://") || baseUrl.startsWith("https://") ? "http" : "tcp";
    }

    private boolean compatibleProtocol(String targetProtocol, String ruleProtocol) {
        return targetProtocol.equals(ruleProtocol)
                || ("http".equals(ruleProtocol) && "https".equals(targetProtocol));
    }

    private void cleanup() {
        long now = System.currentTimeMillis();
        contexts.entrySet().removeIf(entry -> {
            ScanContext context = entry.getValue();
            long reference = context.finishedAt > 0 ? context.finishedAt : context.createdAt();
            return now - reference > CONTEXT_TTL_MS;
        });
    }

    private static String groupKey(String targetId, String ruleId) {
        return targetId + "\u0000" + ruleId;
    }

    private static boolean disjoint(Set<String> left, Set<String> right) {
        for (String value : left) if (right.contains(value)) return false;
        return true;
    }

    private static Set<String> textSet(Object value) {
        Set<String> result = new LinkedHashSet<>();
        if (value instanceof Collection<?> collection) {
            for (Object item : collection) {
                String text = text(item).toLowerCase(Locale.ROOT);
                if (!text.isEmpty()) result.add(text);
            }
        }
        return result;
    }

    private static List<Map<String, Object>> mapList(Object value, String field) {
        if (!(value instanceof List<?> list)) throw new IllegalArgumentException(field + "必须是数组");
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) throw new IllegalArgumentException(field + "中的项目必须是对象");
            result.add(castMap(map));
        }
        return result;
    }

    private static List<Map<String, Object>> mapListOrEmpty(Object value) {
        return value instanceof List<?> ? mapList(value, "observations") : List.of();
    }

    private static Map<String, Object> castMap(Map<?, ?> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            if (entry.getKey() != null) result.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return result;
    }

    private static void copyIfPresent(Map<String, Object> source, Map<String, Object> target, String key) {
        if (source.containsKey(key) && source.get(key) != null) target.put(key, source.get(key));
    }

    private static int boundedInt(Object value, int fallback, int min, int max) {
        int result = fallback;
        try {
            if (value instanceof Number number) result = number.intValue();
            else if (value != null && !String.valueOf(value).isBlank()) result = Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            result = fallback;
        }
        return Math.max(min, Math.min(max, result));
    }

    private static String defaultText(Object value, String fallback) {
        String result = text(value);
        return result.isEmpty() ? fallback : result;
    }

    private static String rawText(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    public record PreparedScan(Map<String, Object> plan, ScanContext context) { }

    public record RuleDefinition(String id, String protocol, List<Map<String, Object>> requests,
                                 Map<String, Object> match) { }

    public record WorkGroup(String targetId, String ruleId, int requestCount) { }

    public static final class ScanContext {
        private final String kind;
        private final Map<String, RuleDefinition> rulesById;
        private final List<WorkGroup> groups;
        private final Set<String> targetIds;
        private final Set<String> ruleIds;
        private final long createdAt;
        private volatile long finishedAt;

        private ScanContext(String kind, Map<String, RuleDefinition> rulesById,
                            List<WorkGroup> groups, Set<String> targetIds,
                            Set<String> ruleIds, long createdAt) {
            this.kind = kind;
            this.rulesById = Map.copyOf(rulesById);
            this.groups = List.copyOf(groups);
            this.targetIds = Set.copyOf(targetIds);
            this.ruleIds = Set.copyOf(ruleIds);
            this.createdAt = createdAt;
        }

        public String kind() { return kind; }
        public Map<String, RuleDefinition> rulesById() { return rulesById; }
        public List<WorkGroup> groups() { return groups; }
        public Set<String> targetIds() { return targetIds; }
        public Set<String> ruleIds() { return ruleIds; }
        public long createdAt() { return createdAt; }
    }
}
