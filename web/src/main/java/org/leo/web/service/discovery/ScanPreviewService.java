package org.leo.web.service.discovery;

import org.leo.web.dto.puppetnode.scan.NetworkDiscoveryDtos.*;
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
            int originalCount = plan.originalCount();
            int hostCount = plan.hosts().size();
            int portCount = plan.ports().size();
            long combinationCount = plan.targets().size();
            long reachabilityProbeCount = plan.reachabilityTargets().size();

            int serviceProbeCount = estimateServiceProbeCount(combinationCount);

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
