package org.leo.web.service.discovery;

import org.leo.web.dto.puppetnode.scan.NetworkDiscoveryDtos.ResolvedTarget;
import org.leo.web.dto.puppetnode.scan.NetworkDiscoveryDtos.ScanConfig;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
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

    public ScanPlanService(TargetResolver targetResolver, PortPolicyResolver portPolicyResolver) {
        this.targetResolver = targetResolver;
        this.portPolicyResolver = portPolicyResolver;
    }

    public ScanPlan plan(ScanConfig scan) {
        if (scan == null || scan.targets() == null) {
            throw new IllegalArgumentException("scan.targets不能为空");
        }
        var execution = scan.execution();
        int workers = bounded(execution == null ? null : execution.workers(),
                NetworkProbeLimits.NODE_DEFAULT_THREADS, 1, NetworkProbeLimits.NODE_MAX_THREADS, "并发线程数");
        int timeout = bounded(execution == null ? null : execution.timeoutMs(),
                NetworkProbeLimits.NODE_DEFAULT_TIMEOUT_MS, NetworkProbeLimits.NODE_MIN_TIMEOUT_MS,
                NetworkProbeLimits.NODE_MAX_TIMEOUT_MS, "超时时间");
        List<ResolvedTarget> resolved = targetResolver.resolve(scan.targets());
        List<Integer> policyPorts = portPolicyResolver.resolve(scan.portPolicy());
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
            combinationCount += explicit.size();
            if (policyHosts.contains(entry.getKey())) {
                combinationCount += policyPorts.size() - explicit.stream().filter(policyPortSet::contains).count();
            }
            if (combinationCount > NetworkProbeLimits.MAX_ENDPOINT_COMBINATIONS) {
                throw new IllegalArgumentException("扫描组合数不能超过" + NetworkProbeLimits.MAX_ENDPOINT_COMBINATIONS + "个");
            }
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
        for (ResolvedTarget target : resolved) {
            for (Integer port : target.port() == null ? policyPorts : List.of(target.port())) {
                EndpointKey key = new EndpointKey(target.ip(), port);
                Map<String, Object> endpoint = endpoints.computeIfAbsent(key, ignored -> {
                    Map<String, Object> value = new LinkedHashMap<>(endpoint(target.ip(), port));
                    value.put("targetId", target.targetId());
                    return value;
                });
                if ("url".equalsIgnoreCase(target.source())) endpoint.putIfAbsent("baseUrl", target.rawTarget());
                ports.add(port);
            }
        }
        if (endpoints.isEmpty()) throw new IllegalArgumentException("扫描目标展开后为空");
        return new ScanPlan(scan.name() == null ? "" : scan.name().trim(), scan.targets().items().size(),
                List.copyOf(explicitByHost.keySet()), List.copyOf(ports),
                endpoints.values().stream().map(Map::copyOf).toList(), List.copyOf(reachability), workers, timeout);
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

    public record ScanPlan(String name, int originalCount, List<String> hosts, List<Integer> ports,
                           List<Map<String, Object>> targets, List<Map<String, Object>> reachabilityTargets,
                           int workers, int timeoutMs) {
        public Map<String, Object> workflowRequest() {
            Map<String, Object> request = new LinkedHashMap<>();
            request.put("name", name);
            request.put("hosts", hosts);
            request.put("ports", ports);
            request.put("targets", targets);
            request.put("reachabilityTargets", reachabilityTargets);
            request.put("threads", workers);
            request.put("timeout", timeoutMs);
            request.put("probeServices", true);
            return request;
        }
    }
}
