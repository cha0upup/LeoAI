package org.leo.web.dto.platform.session;

import java.util.List;

/**
 * 会话管理相关 DTO（Record 风格）。
 *
 * <p>涵盖：会话管理、HostId 管理、侦察摘要、报告生成四个子模块的请求/响应类型。
 */
public final class SessionDtos {

    private SessionDtos() {}

    // ─── 通用请求 ─────────────────────────────────────────────────────────────

    /** 仅含 sessionId 的通用请求。 */
    public record SessionRequest(String sessionId) {}

    // ─── 会话管理 ─────────────────────────────────────────────────────────────

    public record SessionInfo(
            String sessionId,
            String projectId,
            String agentId,
            String agentName,
            String connLink,
            String parentAgentId,
            Long   updateTime,
            Long   lastActiveTime,
            boolean cacheMode,
            List<String> capabilities,
            String currentHostId
    ) {}

    public record ConnLinkItem(
            String agentId,
            String agentName,
            String connLink,
            String parentAgentId,
            String protocol
    ) {}

    public record ConnLinkChainResponse(
            String sessionId,
            List<ConnLinkItem> connLinkChain
    ) {}

    // ─── HostId 管理 ──────────────────────────────────────────────────────────

    public record CurrentHostIdResponse(String sessionId, String currentHostId) {}

    // ─── 侦察摘要 ─────────────────────────────────────────────────────────────

    public record ReconSummaryResponse(
            String sessionId,
            String reconSummary,
            boolean hasReconSummary
    ) {}

    public record SetReconSummaryRequest(
            String sessionId,
            String reconSummary
    ) {}

    public record AppendReconSummaryRequest(String sessionId, String content) {}

    public record ReconSummaryMutateResponse(
            String sessionId,
            String reconSummary,
            String msg
    ) {}

    public record OrganizeResponse(
            String sessionId,
            String reconSummary,
            String msg
    ) {}

    public record DigestResponse(
            String sessionId,
            String digest,
            boolean dirty,
            boolean fresh,
            int summaryLength,
            int threshold
    ) {}

    public record DigestGenerateResponse(String sessionId, String digest, String msg) {}

    public record AutoAppendToggleResponse(String sessionId, boolean autoAppendRecon, String msg) {}

    // ─── 报告生成 ─────────────────────────────────────────────────────────────

    public record ReportResponse(String sessionId, String report, String filename) {}
}
