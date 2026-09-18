package org.leo.web.service.discovery;

import org.leo.web.dto.puppetnode.scan.NetworkDiscoveryDtos.ResolvedTarget;
import org.leo.web.dto.puppetnode.scan.NetworkDiscoveryDtos.ScanConfig;
import org.springframework.stereotype.Service;
import org.leo.web.service.NetworkProbeAnalysisService;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Builds the bounded, deduplicated scan plan shared by preview and execution. */
@Service
public class ScanPlanService {

    private final TargetResolver targetResolver;
    private final PortPolicyResolver portPolicyResolver;
    private final NetworkProbeAnalysisService analysisService;

    public ScanPlanService(TargetResolver targetResolver, PortPolicyResolver portPolicyResolver,
                           NetworkProbeAnalysisService analysisService) {
        this.targetResolver = targetResolver;
        this.portPolicyResolver = portPolicyResolver;
        this.analysisService = analysisService;
    }

    public ScanPlan plan(ScanConfig scan) {
        if (scan == null || scan.targets() == null) {
            throw new IllegalArgumentException("scan.targets不能为空");
        }
        List<ScanStage> stages = ScanStage.resolve(scan.stages());
        List<Map<String, Object>> fingerprintRules = stages.contains(ScanStage.FINGERPRINT)
                ? analysisService.snapshotRules(scan.fingerprint() == null ? Map.of() : Map.of(
                    "ids", scan.fingerprint().ids() == null ? List.of() : scan.fingerprint().ids(),
                    "tags", scan.fingerprint().tags() == null ? List.of() : scan.fingerprint().tags())) : List.of();
        boolean scanPorts = stages.contains(ScanStage.PORT_SCAN);
        boolean discoverHosts = stages.contains(ScanStage.REACHABILITY);
        var execution = scan.execution();
        int workers = bounded(execution == null ? null : execution.workers(),
                NetworkProbeLimits.NODE_DEFAULT_THREADS, 1, NetworkProbeLimits.NODE_MAX_THREADS, "并发线程数");
        int timeout = bounded(execution == null ? null : execution.timeoutMs(),
                NetworkProbeLimits.NODE_DEFAULT_TIMEOUT_MS, NetworkProbeLimits.NODE_MIN_TIMEOUT_MS,
                NetworkProbeLimits.NODE_MAX_TIMEOUT_MS, "超时时间");
        List<ResolvedTarget> resolved = targetResolver.resolve(scan.targets());
        List<Integer> policyPorts = scanPorts ? portPolicyResolver.resolve(scan.portPolicy()) : List.of();
        Map<String, Set<Integer>> explicitByHost = new LinkedHashMap<>();
        Set<String> policyHosts = new LinkedHashSet<>();
        for (ResolvedTarget target : resolved) {
            Set<Integer> explicit = explicitByHost.computeIfAbsent(target.ip(), ignored -> new LinkedHashSet<>());
            if (target.port() == null) policyHosts.add(target.ip());
            else explicit.add(target.port());
        }
        if (explicitByHost.size() > NetworkProbeLimits.MAX_RESOLVED_HOSTS) {
            throw new IllegalArgumentException("扫描主机数不能超过" + NetworkProbeLimits.MAX_RESOLVED_HOSTS + "个");
        }

        long combinationCount = 0;
        Set<Integer> policyPortSet = new LinkedHashSet<>(policyPorts);
        List<Map<String, Object>> reachability = new ArrayList<>();
        for (var entry : explicitByHost.entrySet()) {
            Set<Integer> explicit = entry.getValue();
            if (scanPorts) combinationCount += explicit.size();
            if (policyHosts.contains(entry.getKey())) {
                combinationCount += policyPorts.size() - explicit.stream().filter(policyPortSet::contains).count();
            }
            if (combinationCount > NetworkProbeLimits.MAX_ENDPOINT_COMBINATIONS) {
                throw new IllegalArgumentException("扫描组合数不能超过" + NetworkProbeLimits.MAX_ENDPOINT_COMBINATIONS + "个");
            }
            if (!discoverHosts) continue;
            // Reserve explicit ports before sampling policy ports, independent of input order.
            if (explicit.size() > NetworkProbeLimits.MAX_REACHABILITY_PROBES_PER_HOST) {
                throw new IllegalArgumentException("单台主机的探活端口不能超过"
                        + NetworkProbeLimits.MAX_REACHABILITY_PROBES_PER_HOST + "个");
            }
            Set<Integer> probes = new LinkedHashSet<>(explicit);
            if (policyHosts.contains(entry.getKey())) {
                addSample(probes, NetworkProbeLimits.DEFAULT_REACHABILITY_PORTS);
                addSample(probes, policyPorts);
            }
            if (reachability.size() + probes.size() > NetworkProbeLimits.MAX_REACHABILITY_PROBES) {
                throw new IllegalArgumentException("探活目标数不能超过"
                        + NetworkProbeLimits.MAX_REACHABILITY_PROBES + "个，请缩小主机范围或减少探活端口");
            }
            for (Integer port : probes) reachability.add(endpoint(entry.getKey(), port));
        }

        Map<EndpointKey, Map<String, Object>> endpoints = new LinkedHashMap<>();
        Set<Integer> ports = new LinkedHashSet<>();
        for (ResolvedTarget target : scanPorts ? resolved : List.<ResolvedTarget>of()) {
            for (Integer port : target.port() == null ? policyPorts : List.of(target.port())) {
                EndpointKey key = new EndpointKey(target.ip(), port);
                Map<String, Object> endpoint = endpoints.computeIfAbsent(key,
                        ignored -> new LinkedHashMap<>(endpoint(target.ip(), port)));
                if ("url".equalsIgnoreCase(target.source())) endpoint.putIfAbsent("baseUrl", target.rawTarget());
                // Retain applications for future supplemental scans even without fingerprinting.
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> applications = (List<Map<String, Object>>) endpoint.computeIfAbsent(
                        "applications", ignored -> new ArrayList<>());
                Map<String, Object> application = "url".equalsIgnoreCase(target.source())
                        ? Map.of("baseUrl", target.rawTarget()) : Map.of("hostname", target.host());
                if (!applications.contains(application)) applications.add(application);
                ports.add(port);
            }
        }
        if (explicitByHost.isEmpty() || (scanPorts && endpoints.isEmpty())) {
            throw new IllegalArgumentException("扫描目标展开后为空");
        }
        return new ScanPlan(scan.name() == null ? "" : scan.name().trim(), scan.targets().items().size(),
                new ArrayList<>(explicitByHost.keySet()), new ArrayList<>(ports),
                new ArrayList<>(endpoints.values()), reachability, workers, timeout, stages, fingerprintRules);
    }

    private static void addSample(Set<Integer> probes, List<Integer> candidates) {
        for (Integer port : candidates) {
            if (probes.size() >= NetworkProbeLimits.MAX_REACHABILITY_PROBES_PER_HOST) break;
            probes.add(port);
        }
    }

    private static Map<String, Object> endpoint(String host, int port) {
        return Map.of("host", host, "port", port, "protocol", "tcp");
    }

    private static int bounded(Integer value, int fallback, int min, int max, String field) {
        if (value == null) return fallback;
        if (value < min || value > max) throw new IllegalArgumentException(field + "必须在" + min + "到" + max + "之间");
        return value;
    }

    private record EndpointKey(String host, int port) {}

    /** Only the planner can construct a validated execution snapshot. */
    public static final class ScanPlan {
        private final String name;
        private final int originalCount;
        private final List<String> hosts;
        private final List<Integer> ports;
        private final List<Map<String, Object>> targets;
        private final List<Map<String, Object>> reachabilityTargets;
        private final int workers;
        private final int timeoutMs;
        private final List<ScanStage> stages;
        private final List<Map<String, Object>> fingerprintRules;

        private ScanPlan(String name, int originalCount, List<String> hosts, List<Integer> ports,
                         List<Map<String, Object>> targets, List<Map<String, Object>> reachabilityTargets,
                         int workers, int timeoutMs, List<ScanStage> stages, List<Map<String, Object>> fingerprintRules) {
            this.name = name;
            this.originalCount = originalCount;
            this.hosts = List.copyOf(hosts);
            this.ports = List.copyOf(ports);
            this.targets = immutableRows(targets);
            this.reachabilityTargets = immutableRows(reachabilityTargets);
            this.workers = workers;
            this.timeoutMs = timeoutMs;
            this.stages = List.copyOf(stages);
            this.fingerprintRules = immutableRows(fingerprintRules);
        }

        public String name() { return name; }
        public int originalCount() { return originalCount; }
        public List<String> hosts() { return hosts; }
        public List<Integer> ports() { return ports; }
        public List<Map<String, Object>> targets() { return targets; }
        public List<Map<String, Object>> reachabilityTargets() { return reachabilityTargets; }
        public int workers() { return workers; }
        public int timeoutMs() { return timeoutMs; }
        public List<ScanStage> stages() { return stages; }
        public List<Map<String, Object>> fingerprintRules() { return fingerprintRules; }

        private static List<Map<String, Object>> immutableRows(List<Map<String, Object>> rows) {
            return rows.stream().map(row -> {
                Map<String, Object> copy = new LinkedHashMap<>();
                row.forEach((key, value) -> copy.put(key, immutableValue(value)));
                return Collections.unmodifiableMap(copy);
            }).toList();
        }

        private static Object immutableValue(Object value) {
            if (value instanceof Map<?, ?> map) {
                Map<Object, Object> copy = new LinkedHashMap<>();
                map.forEach((key, item) -> copy.put(key, immutableValue(item)));
                return Collections.unmodifiableMap(copy);
            }
            if (value instanceof List<?> list) return list.stream().map(ScanPlan::immutableValue).toList();
            return value;
        }
    }
}
