package org.leo.service.discovery;

import org.leo.service.discovery.NetworkDiscoveryDtos.*;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 扫描预览服务 - 在实际扫描前展示预期规模和潜在问题。
 */
@Service
public class ScanPreviewService {

    private static final int WARN_COMBINATIONS = 4096;
    private static final int WARN_REACHABILITY_PROBES = 10000;
    private static final double ESTIMATED_OPEN_RATE = 0.10;
    private static final int ESTIMATED_BYTES_PER_RESULT = 500;

    private final ScanPlanService planService;

    public ScanPreviewService(ScanPlanService planService) {
        this.planService = planService;
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
            ScanPlanService.ScanPlan plan = planService.plan(scan);
            int hostCount = plan.hosts().size();
            int combinationCount = plan.targets().size();
            int reachabilityProbeCount = plan.reachabilityTargets().size()
                    + (int) plan.reachabilityTargets().stream().filter(target -> !Boolean.TRUE.equals(target.get("explicitPort")))
                    .map(target -> target.get("host")).distinct().count();

            int serviceProbeCount = plan.stages().contains(ScanStage.SERVICE_PROBE)
                    ? estimateServiceProbeCount(combinationCount) : 0;

            // 警告
            if (combinationCount > WARN_COMBINATIONS) {
                warnings.add("组合数较大，扫描可能需要较长时间: " + combinationCount);
            }

            if (serviceProbeCount > 5000) {
                warnings.add("服务识别将产生大量探测请求: " + serviceProbeCount);
            }
            if (reachabilityProbeCount > WARN_REACHABILITY_PROBES) {
                warnings.add("探活请求数较大，扫描可能需要较长时间: " + reachabilityProbeCount);
            }

            long estimatedBytes = plan.stages().contains(ScanStage.PORT_SCAN)
                    ? (long) (combinationCount * ESTIMATED_OPEN_RATE * ESTIMATED_BYTES_PER_RESULT)
                    : (long) hostCount * 100;

            int ruleRequests = plan.fingerprintRules().stream().mapToInt(rule ->
                    ((List<?>) ((java.util.Map<?, ?>) rule.get("rule")).get("requests")).size()).sum();
            long fingerprintUpperBound = ruleRequests == 0 ? 0 : plan.targets().stream().mapToLong(target ->
                    target.get("applications") instanceof List<?> list ? list.size() : 1).sum() * ruleRequests;
            if (ruleRequests > 0) {
                warnings.add("组件识别仅扫描 HTTP/HTTPS 应用；实际请求数以发现结果为准，单次最多 "
                        + NetworkProbeLimits.MAX_FINGERPRINT_PROBES + " 个请求，响应正文最多读取 "
                        + NetworkProbeLimits.NODE_MAX_READ_BYTES + " 字节");
                if (fingerprintUpperBound > NetworkProbeLimits.MAX_FINGERPRINT_PROBES)
                    warnings.add("组件请求上界超过单次限制，实际应用数过多时组件阶段将失败，请缩小范围或减少规则");
            }
            TargetPreview preview = new TargetPreview(
                    plan.originalCount(),
                    hostCount,
                    plan.ports().size(),
                    combinationCount,
                    reachabilityProbeCount,
                    serviceProbeCount,
                    plan.fingerprintRules().size(), ruleRequests, fingerprintUpperBound,
                    NetworkProbeLimits.NODE_MAX_READ_BYTES,
                    formatBytes(estimatedBytes),
                    warnings,
                    plan.stages().stream().map(Enum::name).toList()
            );

            return new PreviewResponse(preview, errors);

        } catch (IllegalArgumentException e) {
            errors.add(e.getMessage());
            return new PreviewResponse(null, errors);
        }
    }

    /**
     * 估算深度探测数。
     */
    private int estimateServiceProbeCount(int combinationCount) {
        long baseProbes = (long) (combinationCount * ESTIMATED_OPEN_RATE);
        return (int) (baseProbes * 1.5);
    }

    private String formatBytes(long estimatedBytes) {
        if (estimatedBytes < 1024) return estimatedBytes + " B";
        if (estimatedBytes < 1024 * 1024) return (estimatedBytes / 1024) + " KB";
        return (estimatedBytes / (1024 * 1024)) + " MB";
    }
}
