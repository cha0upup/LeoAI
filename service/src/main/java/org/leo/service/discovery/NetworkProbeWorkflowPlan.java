package org.leo.service.discovery;

import org.leo.service.discovery.ScanPlanService.ScanPlan;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.leo.service.discovery.NetworkProbePayloads.copyMap;
import static org.leo.service.discovery.NetworkProbePayloads.endpointKey;
import static org.leo.service.discovery.NetworkProbePayloads.integer;
import static org.leo.service.discovery.NetworkProbePayloads.mapRows;
import static org.leo.service.discovery.NetworkProbePayloads.text;

/** Execution definition and derived stage requests, independent of task lifecycle and I/O. */
record NetworkProbeWorkflowPlan(List<String> hosts, List<Integer> ports,
                                List<Map<String, Object>> targets, int timeout, int threads,
                                List<ScanStage> stages, String name,
                                List<Map<String, Object>> reachabilityTargets,
                                List<Map<String, Object>> fingerprintRules, List<Map<String, Object>> seedEndpoints,
                                String sourceTaskId, boolean debug) {

    /** Silent banner probes should not hold up discovery. */
    private static final int SERVICE_BANNER_TIMEOUT_MS = 300;
    private static final int HTTP_IDENTIFICATION_TIMEOUT_MS = 700;

    static NetworkProbeWorkflowPlan from(ScanPlan plan) {
        if (plan == null) throw new IllegalArgumentException("扫描计划不能为空");
        return new NetworkProbeWorkflowPlan(plan.hosts(), plan.ports(), plan.targets(),
                plan.timeoutMs(), plan.workers(), plan.stages(), plan.name(), plan.reachabilityTargets(),
                plan.fingerprintRules(), List.of(), "", false);
    }

    static NetworkProbeWorkflowPlan fingerprint(List<Map<String, Object>> endpoints,
            List<Map<String, Object>> targets, List<Map<String, Object>> rules,
            String name, String sourceTaskId, boolean debug) {
        endpoints = mapRows(endpoints);
        targets = mapRows(targets);
        rules = mapRows(rules);
        if (endpoints.isEmpty()) throw new IllegalArgumentException("组件识别需要至少一个资产");
        List<String> hosts = endpoints.stream().map(endpoint -> text(endpoint.get("host"))).distinct().toList();
        return new NetworkProbeWorkflowPlan(hosts, List.of(), targets, 3000, 16,
                List.of(ScanStage.FINGERPRINT), name, List.of(), rules, endpoints, sourceTaskId, debug);
    }

    Map<String, Object> fingerprintPreflight() {
        return Map.of("kind", "fingerprint", "targets", fingerprintTargets(seedEndpoints()), "rules", fingerprintRules());
    }

    Map<String, Object> fingerprintRequest(List<Map<String, Object>> applications) {
        return Map.of("kind", "fingerprint", "targets", applications,
                "rules", fingerprintRules(), "threads", Math.min(32, threads()), "debug", debug());
    }

    Map<String, Object> durableConfig() {
        Map<String, Object> config = new LinkedHashMap<>();
        if (!name().isEmpty()) config.put("name", name());
        config.put("hosts", hosts());
        config.put("ports", ports());
        config.put("timeout", Integer.valueOf(timeout()));
        config.put("threads", Integer.valueOf(threads()));
        config.put("stages", stages().stream().map(Enum::name).toList());
        if (!sourceTaskId().isEmpty()) config.put("sourceTaskId", sourceTaskId());
        if (debug()) config.put("debug", true);
        // Preserve original hostnames and application paths for later supplemental scans.
        config.put("targets", targets());
        if (!fingerprintRules().isEmpty()) {
            config.put("fingerprintRules", fingerprintRules());
        }
        return config;
    }

    List<Map<String, Object>> seedObservations() {
        List<Map<String, Object>> observations = new ArrayList<>();
        for (Map<String, Object> endpoint : seedEndpoints()) {
            Map<String, Object> observation = new LinkedHashMap<>();
            for (String key : List.of("host", "port", "protocol", "service", "confidence"))
                if (endpoint.get(key) != null) observation.put(key, endpoint.get(key));
            observation.put("state", "open");
            observation.put("stage", "asset-context");
            observation.put("workflowStage", "SERVICE_PROBE");
            observation.put("sourceTaskId", sourceTaskId());
            observation.put("contextOnly", true);
            Map<String, Object> evidence = new LinkedHashMap<>();
            for (String key : List.of("title", "statusCode", "server", "banner", "contentType", "location"))
                if (endpoint.get(key) != null) evidence.put(key, endpoint.get(key));
            if (endpoint.get("responseSize") != null) evidence.put("bodyLength", endpoint.get("responseSize"));
            observation.put("evidence", evidence);
            observations.add(observation);
        }
        return observations;
    }

    List<Map<String, Object>> fingerprintTargets(List<Map<String, Object>> endpoints) {
        Map<String, Map<String, Object>> configured = new LinkedHashMap<>();
        for (Map<String, Object> target : targets()) configured.put(endpointKey(target), target);
        Map<String, Map<String, Object>> applications = new LinkedHashMap<>();
        for (Map<String, Object> endpoint : endpoints) {
            Map<String, Object> original = configured.getOrDefault(endpointKey(endpoint), Map.of());
            List<Map<String, Object>> inputs = mapRows(original.get("applications"));
            if (inputs.isEmpty() && original.containsKey("baseUrl")) inputs = List.of(Map.of("baseUrl", original.get("baseUrl")));
            if (inputs.isEmpty()) inputs = List.of(Map.of("hostname", endpoint.get("host")));
            String service = text(endpoint.get("service"));
            for (Map<String, Object> input : inputs) {
                String url = text(input.get("baseUrl"));
                if (url.isEmpty()) {
                    if (!List.of("http", "https").contains(service)) continue;
                    url = service + "://" + ServiceIdentificationPolicy.hostForUrl(text(input.get("hostname"))) + ":" + endpoint.get("port") + "/";
                }
                try {
                    java.net.URI uri = java.net.URI.create(url);
                    if (!List.of("http", "https").contains(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null)
                        throw new IllegalArgumentException("无效的应用 URL");
                    String path = uri.getRawPath();
                    if (path == null || path.isEmpty()) path = "/";
                    if (!path.endsWith("/")) path += "/";
                    url = uri.getScheme() + "://" + uri.getRawAuthority() + path;
                    Map<String, Object> target = new LinkedHashMap<>();
                    target.put("host", endpoint.get("host"));
                    target.put("port", endpoint.get("port"));
                    target.put("protocol", uri.getScheme());
                    target.put("baseUrl", url);
                    String id = endpointKey(endpoint) + "|" + url;
                    target.put("targetId", id);
                    applications.putIfAbsent(id, target);
                } catch (IllegalArgumentException error) {
                    throw new IllegalArgumentException("无法创建组件识别目标: " + url, error);
                }
            }
        }
        return new ArrayList<>(applications.values());
    }

    Map<String, Object> reachabilityScan() {
        Map<String, Object> scan = new LinkedHashMap<>();
        scan.put("kind", "reachability");
        scan.put("hosts", hosts());
        scan.put("targets", reachabilityTargets());
        scan.put("timeout", Integer.valueOf(timeout()));
        scan.put("threads", Integer.valueOf(threads()));
        return scan;
    }

    Map<String, Object> portPlan(Set<String> hosts) {
        List<Map<String, Object>> targets = new ArrayList<>();
        for (Map<String, Object> target : targets()) {
            if (hosts.contains(text(target.get("host")))) {
                targets.add(copyMap(target));
            }
        }
        return probePlan(targets, "tcp-connect");
    }

    Map<String, Object> servicePlan(List<Map<String, Object>> endpoints) {
        List<Map<String, Object>> targets = new ArrayList<>();
        Integer timeout = Math.min(timeout(), SERVICE_BANNER_TIMEOUT_MS);
        for (Map<String, Object> endpoint : endpoints) {
            Map<String, Object> target = new LinkedHashMap<>(endpoint);
            target.put("protocol", "tcp");
            target.remove("service");
            target.remove("serviceIdentified");
            target.put("stage", "tcp-exchange");
            target.put("timeout", timeout);
            targets.add(target);
        }
        return probePlan(targets, "tcp-exchange");
    }

    Map<String, Object> serviceHttpPlan(List<Map<String, Object>> endpoints, boolean forceHttps) {
        List<Map<String, Object>> targets = new ArrayList<>();
        Integer timeout = Math.min(timeout(), HTTP_IDENTIFICATION_TIMEOUT_MS);
        for (Map<String, Object> endpoint : endpoints) {
            int port = integer(endpoint.get("port"), -1);
            String scheme = forceHttps ? "https" : ServiceIdentificationPolicy.httpProbeScheme(port);
            Map<String, Object> target = new LinkedHashMap<>(endpoint);
            target.put("protocol", scheme);
            target.put("baseUrl", scheme + "://" + ServiceIdentificationPolicy.hostForUrl(text(endpoint.get("host"))) + ":" + port + "/");
            target.put("stage", "http-head");
            target.put("timeout", timeout);
            targets.add(target);
        }
        return probePlan(targets, "http-head");
    }

    private Map<String, Object> probePlan(List<Map<String, Object>> targets, String stage) {
        Map<String, Object> limits = new LinkedHashMap<>();
        limits.put("timeout", Integer.valueOf(timeout()));
        limits.put("threads", Integer.valueOf(Math.min(threads(), Math.max(1, targets.size()))));
        limits.put("maxReadBytes", Integer.valueOf(NetworkProbeLimits.NODE_MAX_READ_BYTES));
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("targets", new ArrayList<>(targets));
        plan.put("stages", new ArrayList<>(List.of(stage)));
        plan.put("limits", limits);
        return plan;
    }
}
