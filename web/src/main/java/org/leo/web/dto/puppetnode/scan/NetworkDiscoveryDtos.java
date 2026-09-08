package org.leo.web.dto.puppetnode.scan;

import java.util.List;

/**
 * 网络资产发现相关 DTO（统一扫描协议）。
 *
 * <p>请求只携带原始目标和扫描策略，目标展开、端口选择及执行阶段由服务端统一决定。
 */
public final class NetworkDiscoveryDtos {

    private NetworkDiscoveryDtos() {}

    /**
     * 扫描配置。
     */
    public record ScanConfig(
            String name,
            TargetInput targets,
            PortPolicy portPolicy,
            ExecutionConfig execution,
            FingerprintConfig fingerprint
    ) {}

    /**
     * 目标输入（原始格式，由服务端解析）。
     */
    public record TargetInput(
            List<String> items,        // IP/CIDR/URL/host:port/domain/IPv6/range
            List<String> exclude       // 排除列表
    ) {}

    /**
     * 端口策略。
     */
    public record PortPolicy(
            String profile,            // quick, standard, extended, custom
            List<String> ranges,       // ["1-1024", "8000-9000"]
            List<Integer> include,     // 额外包含端口
            List<Integer> exclude      // 排除端口
    ) {}

    /**
     * 执行配置。
     */
    public record ExecutionConfig(
            Integer workers,           // 并发线程数
            Integer timeoutMs          // 连接和识别共用超时
    ) {}

    /**
     * 指纹配置。
     */
    public record FingerprintConfig(
            List<String> tags,         // 按标签筛选
            List<String> ids           // 指定规则ID
    ) {}

    // ─── 目标解析 ─────────────────────────────────────────────────────────────

    /**
     * 解析后的目标（服务端输出）。
     */
    public record ResolvedTarget(
            String targetId,           // 原始目标的稳定标识
            String host,               // 原始域名或地址
            String ip,                 // 实际解析地址
            Integer port,              // 目标端口
            String protocol,           // tcp/http/https/udp
            String source,             // 来源：手工、CIDR、文件等
            String rawTarget           // 原始输入，用于保留 URL 主机、路径和协议
    ) {}

    /**
     * 目标解析预览（用于前端展示）。
     */
    public record TargetPreview(
            Integer originalCount,     // 原始目标数
            Integer hostCount,         // 展开主机数
            Integer portCount,         // 端口数
            Integer combinationCount,  // host × port 组合数
            Integer reachabilityProbeCount, // 探活请求数
            Integer serviceProbeCount, // 预计服务识别请求数
            String estimatedSize,      // 预计结果规模
            List<String> warnings      // 警告信息
    ) {}

    /**
     * 预览响应。
     */
    public record PreviewResponse(
            TargetPreview preview,
            List<String> errors
    ) {}

}
