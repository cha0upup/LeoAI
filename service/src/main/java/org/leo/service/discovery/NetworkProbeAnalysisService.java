package org.leo.service.discovery;

import org.leo.core.util.json.JsonUtil;
import org.leo.service.fingerprint.FingerprintManageService;
import org.springframework.stereotype.Service;

import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

import static org.leo.service.discovery.NetworkProbePayloads.copyMap;
import static org.leo.service.discovery.NetworkProbePayloads.copyValue;

/** Builds fingerprint probe plans and evaluates their bounded evidence on the service side. */
@Service
public class NetworkProbeAnalysisService {

    private static final int MAX_RULES = 64;
    private static final int MAX_READ_BYTES = NetworkProbeLimits.NODE_MAX_READ_BYTES;
    private static final long CONTEXT_TTL_MS = 2L * 60L * 60L * 1000L;
    private static final List<Integer> REACHABILITY_PORTS = NetworkProbeLimits.DEFAULT_REACHABILITY_PORTS;

    private final FingerprintManageService fingerprintManageService;
    private final Map<String, ScanContext> contexts = new ConcurrentHashMap<>();

    public NetworkProbeAnalysisService(FingerprintManageService fingerprintManageService) {
        this.fingerprintManageService = fingerprintManageService;
    }

    /** Resolve and copy the exact rules once, before the workflow is queued. */
    public List<Map<String, Object>> snapshotRules(Object value) {
        Map<?, ?> selector = value instanceof Map<?, ?> map ? map : Map.of();
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        Object requestedIds = selector.containsKey("ids") ? selector.get("ids") : selector.get("fingerprintIds");
        if (requestedIds instanceof Collection<?> items) {
            for (Object item : items) if (!text(item).isEmpty()) ids.add(text(item));
        }
        if (ids.isEmpty()) {
            Set<String> tags = textSet(selector.get("tags"));
            for (Map<String, Object> summary : fingerprintManageService.listFingerprints()) {
                if (tags.isEmpty() || !disjoint(tags, textSet(summary.get("tags"))))
                    ids.add(text(summary.get("fingerprintId")));
            }
        }
        if (ids.isEmpty()) throw new IllegalArgumentException("没有匹配到可执行的 HTTP 指纹规则");
        if (ids.size() > MAX_RULES) throw new IllegalArgumentException("一次最多执行" + MAX_RULES + "条指纹规则，请按标签或指定规则筛选");
        List<Map<String, Object>> snapshots = new ArrayList<>();
        for (String id : ids) {
            try {
                Map<String, Object> snapshot = copyMap(fingerprintManageService.getFingerprintById(id));
                toRule(snapshot);
                snapshots.add(snapshot);
            } catch (Exception error) {
                throw new IllegalArgumentException("指纹规则 " + id + ": " + error.getMessage(), error);
            }
        }
        return List.copyOf(snapshots);
    }

    public void release(String taskId) { contexts.remove(taskId); }

    public PreparedScan prepare(Object value) throws Exception {
        if (!(value instanceof Map<?, ?> rawScan)) {
            throw new IllegalArgumentException("scan必须是对象");
        }
        String kind = text(rawScan.get("kind")).toLowerCase(Locale.ROOT);
        if ("reachability".equals(kind)) {
            return prepareReachability(rawScan);
        }
        if (!("fingerprint".equals(kind) || "recon".equals(kind))) {
            throw new IllegalArgumentException("scan.kind必须是reachability、fingerprint或recon");
        }
        List<Map<String, Object>> sourceTargets = mapList(rawScan.get("targets"), "scan.targets");
        if (sourceTargets.isEmpty()) throw new IllegalArgumentException("scan.targets不能为空");
        boolean explicitReconRules = "recon".equals(kind) && hasExplicitRuleIds(rawScan.get("ruleSelector"));
        List<RuleDefinition> rules = rawScan.get("rules") instanceof List<?>
                ? mapList(rawScan.get("rules"), "rules").stream().map(this::toRule).toList()
                : "fingerprint".equals(kind)
                ? resolveFingerprintRules(rawScan.get("fingerprintIds"))
                : resolveReconRules(rawScan.get("ruleSelector"));
        // The workflow's RECON stage is intentionally HTTP-only. TCP Banner
        // detection remains part of SERVICE_PROBE and is handled by the
        // built-in service classifier, not by configurable fingerprint rules.
        rules = rules.stream()
                .filter(rule -> "http".equalsIgnoreCase(rule.protocol()))
                .toList();
        if (rules.isEmpty()) throw new IllegalArgumentException("没有匹配到可执行的指纹规则");
        if (rules.size() > MAX_RULES) throw new IllegalArgumentException("一次最多执行" + MAX_RULES + "条指纹规则");

        int threads = boundedInt(rawScan.get("threads"), NetworkProbeLimits.NODE_DEFAULT_THREADS,
                1, NetworkProbeLimits.NODE_MAX_THREADS);
        Map<String, Map<String, Object>> targetsById = new LinkedHashMap<>();
        for (Map<String, Object> target : sourceTargets) {
            targetsById.putIfAbsent(targetId(target), target);
        }

        List<Map<String, Object>> probes = new ArrayList<>();
        List<WorkGroup> groups = new ArrayList<>();
        Map<String, Map<String, Object>> sharedProbes = new LinkedHashMap<>();
        Map<String, List<Map<String, Object>>> aliases = new LinkedHashMap<>();
        int logicalRequests = 0;
        Set<String> ruleIds = new LinkedHashSet<>();
        for (Map.Entry<String, Map<String, Object>> targetEntry : targetsById.entrySet()) {
            String targetId = targetEntry.getKey();
            Map<String, Object> target = targetEntry.getValue();
            String targetProtocol = targetProtocol(target);
            for (RuleDefinition rule : rules) {
                if (!compatibleProtocol(targetProtocol, rule.protocol())) continue;
                if ("recon".equals(kind) && !explicitReconRules && !relevantToService(target, rule)) continue;
                groups.add(new WorkGroup(targetId, rule.id(), rule.requests().size(), Map.copyOf(target)));
                ruleIds.add(rule.id());
                for (int requestIndex = 0; requestIndex < rule.requests().size(); requestIndex++) {
                    if (logicalRequests++ >= NetworkProbeLimits.MAX_FINGERPRINT_PROBES) {
                        throw new IllegalArgumentException("指纹探测请求数不能超过"
                                + NetworkProbeLimits.MAX_FINGERPRINT_PROBES + "个");
                    }
                    Map<String, Object> probe = buildProbe(target, targetId, rule, requestIndex);
                    Map<String, Object> identity = new LinkedHashMap<>(probe);
                    identity.keySet().removeAll(List.of("probeId", "ruleId", "requestIndex"));
                    // Only read requests can share a response. Preserve writes as distinct operations.
                    String method = text(((Map<?, ?>) probe.get("httpRequest")).get("method"));
                    String key = List.of("GET", "HEAD").contains(method) ? ruleHash(identity) : text(probe.get("probeId"));
                    Map<String, Object> physical = sharedProbes.get(key);
                    if (physical == null) {
                        physical = probe;
                        sharedProbes.put(key, physical);
                        probes.add(physical);
                    }
                    aliases.computeIfAbsent(text(physical.get("probeId")), ignored -> new ArrayList<>())
                            .add(Map.of("ruleId", rule.id(), "requestIndex", requestIndex));
                }
            }
        }
        if (probes.isEmpty()) {
            String message = "recon".equals(kind) && !explicitReconRules
                    ? "没有适用于已识别服务的指纹规则"
                    : "目标协议与所选指纹规则不匹配";
            throw new IllegalArgumentException(message);
        }

        Map<String, Object> limits = new LinkedHashMap<>();
        limits.put("threads", Integer.valueOf(Math.min(threads, probes.size())));
        limits.put("timeout", Integer.valueOf(maxTimeout(probes)));
        limits.put("maxReadBytes", Integer.valueOf(maxReadBytes(probes)));
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("targets", probes);
        plan.put("stages", stageList("http-request"));
        plan.put("limits", limits);

        Map<String, RuleDefinition> rulesById = new LinkedHashMap<>();
        for (RuleDefinition rule : rules) rulesById.put(rule.id(), rule);
        ScanContext context = new ScanContext(kind, rulesById, groups,
                targetsById.keySet(), ruleIds, Collections.emptyMap(), System.currentTimeMillis());
        context.aliases.putAll(aliases);
        for (Map<String, Object> probe : probes) context.requests.put(text(probe.get("probeId")), probe);
        context.debug = Boolean.TRUE.equals(rawScan.get("debug"));
        context.logicalRequests = logicalRequests;
        return new PreparedScan(plan, context);
    }

    private PreparedScan prepareReachability(Map<?, ?> rawScan) {
        if (!(rawScan.get("hosts") instanceof List<?> rawHosts)) {
            throw new IllegalArgumentException("scan.hosts必须是数组");
        }
        Set<String> hosts = new LinkedHashSet<>();
        for (Object value : rawHosts) {
            String host = text(value);
            if (host.isEmpty()) throw new IllegalArgumentException("scan.hosts必须是非空字符串数组");
            hosts.add(host);
        }
        if (hosts.isEmpty()) throw new IllegalArgumentException("scan.hosts不能为空");
        if (hosts.size() > NetworkProbeLimits.MAX_RESOLVED_HOSTS) {
            throw new IllegalArgumentException("scan.hosts不能超过"
                    + NetworkProbeLimits.MAX_RESOLVED_HOSTS + "个");
        }
        List<Map<String, Object>> probes = new ArrayList<>();
        Object rawTargets = rawScan.get("targets");
        if (rawTargets instanceof List<?> targets && !targets.isEmpty()) {
            if (targets.size() > NetworkProbeLimits.MAX_REACHABILITY_PROBES) {
                throw new IllegalArgumentException("scan.targets不能超过"
                        + NetworkProbeLimits.MAX_REACHABILITY_PROBES + "个");
            }
            Set<String> seen = new LinkedHashSet<>();
            Map<String, Integer> probesByHost = new LinkedHashMap<>();
            for (Object value : targets) {
                if (!(value instanceof Map<?, ?> rawTarget)) {
                    throw new IllegalArgumentException("scan.targets中的项目必须是对象");
                }
                Map<String, Object> target = copyMap(rawTarget);
                String host = text(target.get("host"));
                int port = boundedInt(target.get("port"), -1, -1, 65535);
                if (host.isEmpty() || port < 1) {
                    throw new IllegalArgumentException("scan.targets包含无效host/port");
                }
                if (!hosts.contains(host)) {
                    throw new IllegalArgumentException("scan.targets包含不在scan.hosts中的主机");
                }
                if (seen.add(host + "\u0000" + port)) {
                    int hostProbeCount = probesByHost.getOrDefault(host, 0) + 1;
                    if (hostProbeCount > NetworkProbeLimits.MAX_REACHABILITY_PROBES_PER_HOST) {
                        throw new IllegalArgumentException("单台主机的探活端口不能超过"
                                + NetworkProbeLimits.MAX_REACHABILITY_PROBES_PER_HOST + "个");
                    }
                    probesByHost.put(host, hostProbeCount);
                    Map<String, Object> probe = new LinkedHashMap<>();
                    probe.put("host", host);
                    probe.put("port", Integer.valueOf(port));
                    probe.put("protocol", "tcp");
                    probe.put("targetId", host);
                    probes.add(probe);
                }
            }
        } else {
            List<Integer> ports = reachabilityPorts(rawScan.get("ports"));
            long probeCount = (long) hosts.size() * (long) ports.size();
            if (probeCount > NetworkProbeLimits.MAX_REACHABILITY_PROBES) {
                throw new IllegalArgumentException("探活目标数不能超过"
                        + NetworkProbeLimits.MAX_REACHABILITY_PROBES + "个");
            }
            for (String host : hosts) {
                for (Integer port : ports) {
                    Map<String, Object> probe = new LinkedHashMap<>();
                    probe.put("host", host);
                    probe.put("port", port);
                    probe.put("protocol", "tcp");
                    probe.put("targetId", host);
                    probes.add(probe);
                }
            }
        }
        if (probes.isEmpty()) throw new IllegalArgumentException("scan.targets不能为空");
        int timeout = boundedInt(rawScan.get("timeout"), NetworkProbeLimits.NODE_DEFAULT_TIMEOUT_MS, 100, 300000);
        int threads = boundedInt(rawScan.get("threads"), NetworkProbeLimits.NODE_DEFAULT_THREADS,
                1, NetworkProbeLimits.NODE_MAX_THREADS);
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("targets", probes);
        plan.put("stages", stageList("tcp-connect"));
        Map<String, Object> limits = new LinkedHashMap<>();
        limits.put("threads", Integer.valueOf(Math.min(threads, probes.size())));
        limits.put("timeout", Integer.valueOf(timeout));
        plan.put("limits", limits);
        Map<String, Integer> expectedByHost = new LinkedHashMap<>();
        for (Map<String, Object> probe : probes) {
            expectedByHost.merge(text(probe.get("host")), Integer.valueOf(1), Integer::sum);
        }
        ScanContext context = new ScanContext("reachability", new LinkedHashMap<>(), new ArrayList<>(),
                hosts, new LinkedHashSet<>(), expectedByHost, System.currentTimeMillis());
        return new PreparedScan(plan, context);
    }

    private List<Integer> reachabilityPorts(Object value) {
        if (!(value instanceof Collection<?> collection) || collection.isEmpty()) {
            return REACHABILITY_PORTS;
        }
        if (collection.size() > NetworkProbeLimits.MAX_REACHABILITY_PROBES_PER_HOST) {
            throw new IllegalArgumentException("单台主机的探活端口不能超过"
                    + NetworkProbeLimits.MAX_REACHABILITY_PROBES_PER_HOST + "个");
        }
        Set<Integer> ports = new LinkedHashSet<>();
        for (Object item : collection) {
            int port = boundedInt(item, -1, -1, 65535);
            if (port < 1) throw new IllegalArgumentException("scan.ports包含无效端口");
            ports.add(port);
        }
        if (ports.isEmpty()) throw new IllegalArgumentException("scan.ports不能为空");
        return new ArrayList<>(ports);
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
        Map<String, Object> snapshot = copyMap(rawSnapshot);
        List<Map<String, Object>> page = mapListOrEmpty(snapshot.get("observations"));
        for (Map<String, Object> observation : page) {
            Map<String, Object> request = context.requests.get(text(observation.get("probeId")));
            if (request != null) {
                Map<String, Object> requestDetails = new LinkedHashMap<>(request);
                requestDetails.keySet().removeAll(List.of("probeId", "targetId", "ruleId", "requestIndex"));
                observation.put("request", requestDetails);
            }
        }
        snapshot.put("observations", page);
        List<Map<String, Object>> observations = context.remember(page);
        if ("reachability".equals(context.kind())) {
            enrichReachability(context, snapshot, observations);
            componentResult.put("result", snapshot);
            if ("STOPPED".equals(String.valueOf(snapshot.get("status")))) {
                context.finishedAt = System.currentTimeMillis();
            }
            cleanup();
            return;
        }
        List<Map<String, Object>> logical = new ArrayList<>();
        for (Map<String, Object> observation : observations) {
            for (Map<String, Object> alias : context.aliases.getOrDefault(text(observation.get("probeId")), List.of(Map.of()))) {
                Map<String, Object> copy = new LinkedHashMap<>(observation);
                copy.putAll(alias);
                logical.add(copy);
            }
        }
        context.rememberGrouped(logical);
        Map<String, Map<Integer, Map<String, Object>>> grouped = context.groupedObservations();

        List<Map<String, Object>> matches = new ArrayList<>();
        int completed = 0;
        int hitCount = 0;
        for (WorkGroup group : context.groups()) {
            String key = groupKey(group.targetId(), group.ruleId());
            Map<String, Object> evaluated = context.completedMatches.get(key);
            if (evaluated != null) {
                matches.add(evaluated);
                completed++;
                if (Boolean.TRUE.equals(evaluated.get("matched"))) hitCount++;
                continue;
            }
            Map<Integer, Map<String, Object>> groupObservations = grouped.getOrDefault(key, Collections.emptyMap());
            boolean complete = groupObservations.size() >= group.requestCount();
            boolean matched = false;
            String matchError = null;
            String status = "PENDING";
            if (!complete && "STOPPED".equals(text(snapshot.get("status"))) && !Boolean.TRUE.equals(snapshot.get("hasMore"))) {
                status = "CANCELLED".equals(text(snapshot.get("outcome"))) ? "CANCELLED" : "INCONCLUSIVE";
                matchError = "规则所需响应尚未全部采集";
            }
            if (complete) {
                completed++;
                try {
                    List<Map<String, Object>> responses = responses(groupObservations, group.requestCount());
                    if (responses.stream().anyMatch(response -> response.containsKey("error") || !(response.get("status") instanceof Number statusCode) || statusCode.intValue() < 100)) {
                        status = "ERROR";
                        matchError = "请求失败或未收到 HTTP 响应";
                    } else {
                        matched = evaluate(context.rulesById().get(group.ruleId()).match(), responses);
                        status = matched ? "MATCHED" : "NOT_MATCHED";
                        if (matched) hitCount++;
                    }
                } catch (IncompleteEvidence error) {
                    status = "INCONCLUSIVE";
                    matchError = error.getMessage();
                } catch (RuntimeException error) {
                    status = "ERROR";
                    matchError = error.getMessage();
                }
            }
            Map<String, Object> match = new LinkedHashMap<>();
            match.put("targetId", group.targetId());
            match.put("ruleId", group.ruleId());
            RuleDefinition rule = context.rulesById().get(group.ruleId());
            match.put("ruleName", rule == null ? "" : rule.name());
            match.put("protocol", rule == null ? "" : rule.protocol());
            match.put("ruleHash", rule == null ? "" : rule.hash());
            match.put("host", group.target().get("host"));
            match.put("port", group.target().get("port"));
            match.put("url", group.target().get("baseUrl"));
            match.put("status", status);
            match.put("probeIds", new TreeMap<>(groupObservations).values().stream()
                    .map(observation -> text(observation.get("probeId"))).filter(id -> !id.isEmpty()).toList());
            match.put("requestIndices", new ArrayList<>(new TreeMap<>(groupObservations).keySet()));
            match.put("complete", Boolean.valueOf(complete));
            match.put("matched", Boolean.valueOf(matched));
            match.put("evidenceCount", Integer.valueOf(groupObservations.size()));
            if (matchError != null && !matchError.isBlank()) match.put("error", matchError);
            if (complete && rule != null) {
                List<Map<String, Object>> responseList = responses(groupObservations, group.requestCount());
                if (matched && !rule.version().isEmpty()) extractVersion(match, rule.version(), responseList, groupObservations);
                if (context.debug) match.put("conditions", conditionTrace(rule.match(), responseList, "match"));
            }
            matches.add(match);
            if (complete) {
                // Retain only the decision after a rule is evaluated. Raw evidence
                // is held by the current result page until its database commit.
                context.completedMatches.put(key, match);
                context.discardGroup(key);
            }
        }

        Map<String, Object> analysis = new LinkedHashMap<>();
        analysis.put("kind", context.kind());
        analysis.put("total", Integer.valueOf(context.groups().size()));
        analysis.put("completed", Integer.valueOf(completed));
        analysis.put("targetCount", Integer.valueOf(context.targetIds().size()));
        analysis.put("ruleCount", Integer.valueOf(context.ruleIds().size()));
        analysis.put("hitCount", Integer.valueOf(hitCount));
        analysis.put("logicalRequestCount", context.logicalRequests);
        analysis.put("networkRequestCount", context.requests.size());
        analysis.put("savedRequestCount", context.logicalRequests - context.requests.size());
        analysis.put("matches", matches);
        snapshot.put("analysis", analysis);
        componentResult.put("result", snapshot);
        if ("STOPPED".equals(String.valueOf(snapshot.get("status")))) context.finishedAt = System.currentTimeMillis();
        cleanup();
    }

    private void enrichReachability(ScanContext context, Map<String, Object> snapshot,
                                    List<Map<String, Object>> observations) {
        for (Map<String, Object> observation : observations) {
            if (!"tcp-connect".equals(text(observation.get("stage")))) continue;
            String host = text(observation.get("host"));
            if (host.isEmpty()) continue;
            context.completedByHost.merge(host, Integer.valueOf(1), Integer::sum);
            if ("open".equalsIgnoreCase(text(observation.get("state")))) context.reachableHosts.add(host);
        }

        List<String> reachable = new ArrayList<>();
        List<String> unreachable = new ArrayList<>();
        List<String> pending = new ArrayList<>();
        for (String host : context.targetIds()) {
            if (context.reachableHosts.contains(host)) {
                reachable.add(host);
            } else if (context.completedByHost.getOrDefault(host, Integer.valueOf(0))
                    >= context.expectedByHost.getOrDefault(host, Integer.valueOf(REACHABILITY_PORTS.size()))) {
                unreachable.add(host);
            } else {
                pending.add(host);
            }
        }

        Map<String, Object> analysis = new LinkedHashMap<>();
        analysis.put("kind", "reachability");
        analysis.put("total", Integer.valueOf(context.targetIds().size()));
        analysis.put("completed", Integer.valueOf(reachable.size() + unreachable.size()));
        analysis.put("targetCount", Integer.valueOf(context.targetIds().size()));
        analysis.put("hitCount", Integer.valueOf(reachable.size()));
        analysis.put("reachableHostList", reachable);
        analysis.put("unreachableHostList", unreachable);
        analysis.put("pendingHostList", pending);
        snapshot.put("scanKind", "host-reachability");
        snapshot.put("analysis", analysis);
    }

    private List<RuleDefinition> resolveFingerprintRules(Object value) throws Exception {
        List<?> ids;
        if (value instanceof Collection<?> collection) ids = new ArrayList<>(collection);
        else if (value == null) ids = Collections.emptyList();
        else ids = Collections.singletonList(value);
        List<RuleDefinition> result = new ArrayList<>();
        for (Object idValue : ids) {
            String id = text(idValue);
            if (id.isEmpty()) continue;
            result.add(toRule(fingerprintManageService.getFingerprintById(id)));
        }
        return result;
    }

    private List<RuleDefinition> resolveReconRules(Object value) throws Exception {
        Map<?, ?> selector = value instanceof Map<?, ?> map ? map : Collections.emptyMap();
        Object idsValue = selector.get("fingerprintIds");
        if (idsValue instanceof Collection<?> ids && !ids.isEmpty()) return resolveFingerprintRules(ids);
        Set<String> tags = textSet(selector.get("tags"));
        List<Map<String, Object>> summaries = fingerprintManageService.listFingerprints();
        List<RuleDefinition> result = new ArrayList<>();
        for (Map<String, Object> summary : summaries) {
            if (!tags.isEmpty() && disjoint(tags, textSet(summary.get("tags")))) continue;
            result.add(toRule(fingerprintManageService.getFingerprintById(text(summary.get("fingerprintId")))));
        }
        return result;
    }

    private boolean hasExplicitRuleIds(Object value) {
        if (!(value instanceof Map<?, ?> selector)) return false;
        return selector.get("fingerprintIds") instanceof Collection<?> ids && !ids.isEmpty();
    }

    private boolean relevantToService(Map<String, Object> target, RuleDefinition rule) {
        String service = text(target.get("service")).toLowerCase(Locale.ROOT);
        if (service.isEmpty() || "unknown".equals(service)) return true;
        if ("http".equals(service) || "https".equals(service)) return true;
        return false;
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
        fingerprintManageService.validateRule(rawRule);
        if (!"http".equals(protocol)) throw new IllegalArgumentException("仅支持 HTTP 指纹: " + id);
        return new RuleDefinition(id, text(fingerprint.get("name")), protocol,
                textSet(fingerprint.get("tags")), requests, copyMap(rawMatch), rawRule.get("version") instanceof Map<?, ?> version ? copyMap(version) : Map.of(), ruleHash(fingerprint));
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
        probe.put("timeout", Integer.valueOf(boundedInt(request.get("timeout"),
                    NetworkProbeLimits.NODE_DEFAULT_TIMEOUT_MS, 100, 300000)));
        probe.put("maxReadBytes", Integer.valueOf(boundedInt(request.get("maxBodyBytes"),
                MAX_READ_BYTES, 256, MAX_READ_BYTES)));
        probe.put("stage", "http-request");
        Map<String, Object> httpRequest = new LinkedHashMap<>();
        httpRequest.put("method", defaultText(request.get("method"), "GET").toUpperCase(Locale.ROOT));
        String path = text(request.get("uri"));
        if (path.isEmpty()) path = defaultText(request.get("path"), "/");
        // Both runtimes receive an origin URL plus the fully joined application path.
        // PHP sends path verbatim, while Java joins it to baseUrl.
        if (!text(source.get("baseUrl")).isEmpty()) {
            java.net.URI base = java.net.URI.create(text(source.get("baseUrl")));
            String prefix = base.getRawPath() == null ? "/" : base.getRawPath();
            if (!prefix.endsWith("/")) prefix += "/";
            path = prefix + (path.startsWith("/") ? path.substring(1) : path);
            probe.put("baseUrl", base.getScheme() + "://" + base.getRawAuthority() + "/");
        }
        httpRequest.put("path", path);
        httpRequest.put("charset", defaultText(request.get("charset"), "UTF-8"));
        httpRequest.put("headers", request.get("headers") == null ? Map.of() : copyValue(request.get("headers")));
        httpRequest.put("body", request.get("body") == null ? "" : String.valueOf(request.get("body")));
        probe.put("httpRequest", httpRequest);
        return probe;
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

    private boolean evaluate(Map<String, Object> expression, List<Map<String, Object>> responses) {
        if (expression.containsKey("all")) {
            IncompleteEvidence incomplete = null;
            for (Map<String, Object> child : expressionList(expression.get("all"))) {
                try { if (!evaluate(child, responses)) return false; }
                catch (IncompleteEvidence error) { incomplete = error; }
            }
            if (incomplete != null) throw incomplete;
            return true;
        }
        if (expression.containsKey("any")) {
            IncompleteEvidence incomplete = null;
            for (Map<String, Object> child : expressionList(expression.get("any"))) {
                try { if (evaluate(child, responses)) return true; }
                catch (IncompleteEvidence error) { incomplete = error; }
            }
            if (incomplete != null) throw incomplete;
            return false;
        }
        if (expression.get("not") instanceof Map<?, ?> child) return !evaluate(copyMap(child), responses);

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
    private void extractVersion(Map<String, Object> match, Map<String, Object> extractor,
                                List<Map<String, Object>> responses, Map<Integer, Map<String, Object>> observations) {
        int index = ((Number) extractor.getOrDefault("request", 0)).intValue();
        Map<String, Object> response = responses.get(index);
        String field = text(extractor.get("field"));
        String source = String.valueOf(response.getOrDefault(field, ""));
        String prefix = String.valueOf(extractor.get("prefix"));
        boolean ignoreCase = !Boolean.FALSE.equals(extractor.get("ignoreCase"));
        boolean truncated = Boolean.TRUE.equals(response.get("headers".equals(field) ? "headersTruncated" : "truncated"));
        String suffix = String.valueOf(extractor.getOrDefault("suffix", ""));
        int offset = 0;
        while ((offset = literalIndexOf(source, prefix, offset, ignoreCase)) >= 0) {
            int start = offset + prefix.length();
            java.util.regex.Matcher token = java.util.regex.Pattern.compile("[0-9][0-9A-Za-z._+\\-]{0,63}").matcher(source);
            token.region(start, source.length());
            if (token.lookingAt()) {
                int end = token.end();
                boolean boundary = end == source.length() || !String.valueOf(source.charAt(end)).matches("[0-9A-Za-z._+\\-]");
                boolean suffixMatches = suffix.isEmpty() || source.regionMatches(ignoreCase, end, suffix, 0, suffix.length());
                if (boundary && suffixMatches && !(truncated && end == source.length())) {
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

    private int literalIndexOf(String source, String prefix, int offset, boolean ignoreCase) {
        for (int i = offset; i <= source.length() - prefix.length(); i++)
            if (source.regionMatches(ignoreCase, i, prefix, 0, prefix.length())) return i;
        return -1;
    }

    private List<Map<String, Object>> conditionTrace(Map<String, Object> expression,
                                                     List<Map<String, Object>> responses, String path) {
        List<Map<String, Object>> trace = new ArrayList<>();
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("path", path);
        item.put("expression", expression);
        try {
            int index = boundedInt(expression.get("request"), 0, 0, responses.size() - 1);
            List<Map<String, Object>> relevant = expression.containsKey("field") ? List.of(responses.get(index)) : responses;
            if (relevant.stream().anyMatch(response -> response.containsKey("error")
                    || !(response.get("status") instanceof Number code) || code.intValue() < 100)) {
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
        String explicit = text(target.get("targetId"));
        if (!explicit.isEmpty()) return explicit;
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
            result.add(copyMap(map));
        }
        return result;
    }

    private static List<Map<String, Object>> mapListOrEmpty(Object value) {
        return value instanceof List<?> ? mapList(value, "observations") : new ArrayList<>();
    }

    private static void copyIfPresent(Map<String, Object> source, Map<String, Object> target, String key) {
        if (source.containsKey(key) && source.get(key) != null) target.put(key, copyValue(source.get(key)));
    }

    private static List<String> stageList(String... values) {
        List<String> result = new ArrayList<>();
        result.addAll(Arrays.asList(values));
        return result;
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

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static final class IncompleteEvidence extends RuntimeException {
        private IncompleteEvidence(String message) { super(message); }
    }

    private static Object canonical(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new TreeMap<>();
            map.forEach((key, item) -> result.put(String.valueOf(key), canonical(item)));
            return result;
        }
        if (value instanceof Collection<?> list) return list.stream().map(NetworkProbeAnalysisService::canonical).toList();
        return value;
    }

    private static String ruleHash(Map<String, Object> rule) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(JsonUtil.toJsonString(canonical(rule)).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) { throw new IllegalStateException("无法计算规则摘要", error); }
    }

    public record PreparedScan(Map<String, Object> plan, ScanContext context) { }

    public record RuleDefinition(String id, String name, String protocol, Set<String> tags,
                                 List<Map<String, Object>> requests, Map<String, Object> match, Map<String, Object> version, String hash) { }

    public record WorkGroup(String targetId, String ruleId, int requestCount, Map<String, Object> target) { }

    public static final class ScanContext {
        private final Map<String, List<Map<String, Object>>> aliases = new LinkedHashMap<>();
        private final Map<String, Map<String, Object>> requests = new LinkedHashMap<>();
        private boolean debug;
        private int logicalRequests;
        private final String kind;
        private final Map<String, RuleDefinition> rulesById;
        private final List<WorkGroup> groups;
        private final Set<String> targetIds;
        private final Set<String> ruleIds;
        private final Map<String, Integer> expectedByHost;
        private final Map<String, Integer> completedByHost = new LinkedHashMap<>();
        private final Set<String> reachableHosts = new LinkedHashSet<>();
        private final Set<String> seenObservationKeys = new LinkedHashSet<>();
        private final Map<String, Map<Integer, Map<String, Object>>> groupedObservations = new LinkedHashMap<>();
        private final Map<String, Map<String, Object>> completedMatches = new ConcurrentHashMap<>();
        private final long createdAt;
        private volatile long finishedAt;

        private ScanContext(String kind, Map<String, RuleDefinition> rulesById,
                            List<WorkGroup> groups, Set<String> targetIds,
                            Set<String> ruleIds, Map<String, Integer> expectedByHost,
                            long createdAt) {
            this.kind = kind;
            this.rulesById = Collections.unmodifiableMap(new LinkedHashMap<>(rulesById));
            this.groups = Collections.unmodifiableList(new ArrayList<>(groups));
            this.targetIds = Collections.unmodifiableSet(new LinkedHashSet<>(targetIds));
            this.ruleIds = Collections.unmodifiableSet(new LinkedHashSet<>(ruleIds));
            this.expectedByHost = Collections.unmodifiableMap(new LinkedHashMap<>(expectedByHost));
            this.createdAt = createdAt;
        }

        private synchronized List<Map<String, Object>> remember(List<Map<String, Object>> values) {
            List<Map<String, Object>> fresh = new ArrayList<>();
            for (Map<String, Object> value : values) {
                String key = observationKey(value);
                if (seenObservationKeys.add(key)) fresh.add(new LinkedHashMap<>(value));
            }
            return fresh;
        }

        private synchronized void rememberGrouped(List<Map<String, Object>> values) {
            for (Map<String, Object> observation : values) {
                String targetId = text(observation.get("targetId"));
                String ruleId = text(observation.get("ruleId"));
                if (targetId.isEmpty() || ruleId.isEmpty()) continue;
                int requestIndex = boundedInt(observation.get("requestIndex"), 0, 0, 255);
                groupedObservations.computeIfAbsent(groupKey(targetId, ruleId),
                                ignored -> new LinkedHashMap<>())
                        .put(requestIndex, new LinkedHashMap<>(observation));
            }
        }

        private synchronized void discardGroup(String key) { groupedObservations.remove(key); }

        private synchronized Map<String, Map<Integer, Map<String, Object>>> groupedObservations() {
            Map<String, Map<Integer, Map<String, Object>>> copy = new LinkedHashMap<>();
            for (Map.Entry<String, Map<Integer, Map<String, Object>>> entry : groupedObservations.entrySet()) {
                copy.put(entry.getKey(), new LinkedHashMap<>(entry.getValue()));
            }
            return copy;
        }

        private static String observationKey(Map<String, Object> observation) {
            String probeId = text(observation.get("probeId"));
            if (!probeId.isEmpty()) return "probe\u0000" + probeId;
            return text(observation.get("host")) + "\u0000"
                    + text(observation.get("port")) + "\u0000"
                    + text(observation.get("stage")) + "\u0000"
                    + text(observation.get("targetId")) + "\u0000"
                    + text(observation.get("ruleId")) + "\u0000"
                    + text(observation.get("requestIndex"));
        }

        public String kind() { return kind; }
        public Map<String, RuleDefinition> rulesById() { return rulesById; }
        public List<WorkGroup> groups() { return groups; }
        public Set<String> targetIds() { return targetIds; }
        public Set<String> ruleIds() { return ruleIds; }
        public long createdAt() { return createdAt; }
    }
}
