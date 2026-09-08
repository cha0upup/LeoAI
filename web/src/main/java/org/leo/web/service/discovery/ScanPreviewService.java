package org.leo.web.service.discovery;

import org.leo.web.dto.puppetnode.scan.NetworkDiscoveryDtos.*;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 扫描预览服务 - 在实际扫描前展示预期规模和潜在问题。
 */
@Service
public class ScanPreviewService {

    private static final int WARN_COMBINATIONS = 4096;
    private static final int WARN_REACHABILITY_PROBES = 10000;

    private final TargetResolver targetResolver;
    private final PortPolicyResolver portPolicyResolver;

    public ScanPreviewService(TargetResolver targetResolver, PortPolicyResolver portPolicyResolver) {
        this.targetResolver = targetResolver;
        this.portPolicyResolver = portPolicyResolver;
    }

    /**
     * 预览扫描配置。
     *
     * @param scan 扫描配置
     * @return 预览结果
     */
    public PreviewResponse preview(ScanConfig scan) {
        List<String> warnings = new ArrayList<>();
        List<String> errors = new ArrayList<>();

        try {
            // 解析目标
            List<ResolvedTarget> targets = targetResolver.resolve(scan.targets());
            int hostCount = countUniqueHosts(targets);
            if (hostCount > NetworkProbeLimits.MAX_RESOLVED_HOSTS) {
                throw new IllegalArgumentException("扫描主机数不能超过"
                        + NetworkProbeLimits.MAX_RESOLVED_HOSTS + "个");
            }
            int originalCount = scan.targets().items() != null ? scan.targets().items().size() : 0;

            // 解析端口策略。显式 host:port/URL 目标只使用自身端口，不与策略做笛卡尔积。
            List<Integer> policyPorts = portPolicyResolver.resolve(scan.portPolicy());
            Set<Integer> effectivePorts = new LinkedHashSet<>();
            Map<String, Set<Integer>> explicitPortsByHost = new HashMap<>();
            Set<String> hostsUsingPolicy = new LinkedHashSet<>();
            Map<String, Set<Integer>> reachabilityPortsByHost = new HashMap<>();
            for (ResolvedTarget target : targets) {
                String host = target.ip();
                if (target.port() == null) {
                    hostsUsingPolicy.add(host);
                    Set<Integer> reachabilityPorts = reachabilityPortsByHost
                            .computeIfAbsent(host, ignored -> new LinkedHashSet<>());
                    reachabilityPorts.addAll(NetworkProbeLimits.DEFAULT_REACHABILITY_PORTS);
                    for (Integer port : policyPorts) {
                        if (reachabilityPorts.size() >= NetworkProbeLimits.MAX_REACHABILITY_PROBES_PER_HOST) break;
                        reachabilityPorts.add(port);
                    }
                } else {
                    explicitPortsByHost.computeIfAbsent(host, ignored -> new LinkedHashSet<>())
                            .add(target.port());
                    effectivePorts.add(target.port());
                    Set<Integer> reachabilityPorts = reachabilityPortsByHost
                            .computeIfAbsent(host, ignored -> new LinkedHashSet<>());
                    if (reachabilityPorts.size() >= NetworkProbeLimits.MAX_REACHABILITY_PROBES_PER_HOST
                            && !reachabilityPorts.contains(target.port())) {
                        throw new IllegalArgumentException("单台主机的探活端口不能超过"
                                + NetworkProbeLimits.MAX_REACHABILITY_PROBES_PER_HOST + "个");
                    }
                    reachabilityPorts.add(target.port());
                }
            }
            long reachabilityProbeCount = reachabilityPortsByHost.values().stream()
                    .mapToLong(Set::size).sum();
            if (reachabilityProbeCount > NetworkProbeLimits.MAX_REACHABILITY_PROBES) {
                throw new IllegalArgumentException("探活目标数不能超过"
                        + NetworkProbeLimits.MAX_REACHABILITY_PROBES + "个，请缩小主机范围或减少探活端口");
            }
            if (!hostsUsingPolicy.isEmpty()) effectivePorts.addAll(policyPorts);
            long combinationCount = explicitPortsByHost.values().stream()
                    .mapToLong(Set::size).sum();
            for (String host : hostsUsingPolicy) {
                Set<Integer> explicitPorts = explicitPortsByHost.getOrDefault(host, Set.of());
                combinationCount += policyPorts.stream()
                        .filter(port -> !explicitPorts.contains(port))
                        .count();
            }
            if (combinationCount > NetworkProbeLimits.MAX_ENDPOINT_COMBINATIONS) {
                throw new IllegalArgumentException("扫描组合数不能超过"
                        + NetworkProbeLimits.MAX_ENDPOINT_COMBINATIONS + "个");
            }
            int portCount = effectivePorts.size();

            int serviceProbeCount = estimateServiceProbeCount(combinationCount);

            // 警告
            if (combinationCount > WARN_COMBINATIONS) {
                warnings.add("组合数较大，扫描可能需要较长时间: " + combinationCount);
            }

            if (serviceProbeCount > 5000) {
                warnings.add("服务识别和指纹分析将产生大量探测请求: " + serviceProbeCount);
            }
            if (reachabilityProbeCount > WARN_REACHABILITY_PROBES) {
                warnings.add("探活请求数较大，扫描可能需要较长时间: " + reachabilityProbeCount);
            }

            String estimatedSize = estimateResultSize(combinationCount);

            TargetPreview preview = new TargetPreview(
                    originalCount,
                    hostCount,
                    portCount,
                    (int) Math.min(combinationCount, Integer.MAX_VALUE),
                    (int) reachabilityProbeCount,
                    serviceProbeCount,
                    estimatedSize,
                    warnings
            );

            return new PreviewResponse(preview, errors);

        } catch (IllegalArgumentException e) {
            errors.add(e.getMessage());
            return new PreviewResponse(null, errors);
        }
    }

    /**
     * 计算唯一主机数。
     */
    private int countUniqueHosts(List<ResolvedTarget> targets) {
        return (int) targets.stream()
                .map(ResolvedTarget::ip)
                .distinct()
                .count();
    }

    /**
     * 估算深度探测数。
     */
    private int estimateServiceProbeCount(long combinationCount) {
        double openRate = 0.10;  // 假设 10% 端口开放

        long baseProbes = (long) (combinationCount * openRate);
        return (int) Math.min(Integer.MAX_VALUE, (long) (baseProbes * 1.5));
    }

    /**
     * 估算结果规模。
     */
    private String estimateResultSize(long combinationCount) {
        int avgBytesPerResult = 500;
        double openRate = 0.10;
        long estimatedBytes = (long) (combinationCount * openRate * avgBytesPerResult);

        if (estimatedBytes < 1024) {
            return estimatedBytes + " B";
        } else if (estimatedBytes < 1024 * 1024) {
            return (estimatedBytes / 1024) + " KB";
        } else {
            return (estimatedBytes / (1024 * 1024)) + " MB";
        }
    }
}
