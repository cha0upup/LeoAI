package org.leo.web.dto.platform.session;

import java.util.List;

/**
 * 会话管理相关 DTO（Record 风格）。
 *
 * <p>保留会话管理及摘要操作请求；摘要和报告结果由对应应用用例定义。
 */
public final class SessionDtos {

    private SessionDtos() {}

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

    // ─── 侦察摘要 ─────────────────────────────────────────────────────────────

    public record SetReconSummaryRequest(
            String sessionId,
            String reconSummary
    ) {}

    public record AppendReconSummaryRequest(String sessionId, String content) {}
}
