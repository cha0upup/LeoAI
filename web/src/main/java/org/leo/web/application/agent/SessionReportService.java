package org.leo.web.application.agent;

import org.leo.core.entity.Agent;
import org.leo.core.infrastructure.persistence.session.AgentHostCacheRepository;
import org.leo.core.infrastructure.persistence.session.AgentReconRepository;
import org.leo.core.session.AgentNodeSession;
import org.leo.service.agentnode.AgentService;
import org.leo.web.application.UseCaseException;
import org.leo.web.application.UseCaseException.Kind;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * 会话侦察报告生成服务。
 *
 * <p>汇聚当前用户、Agent、HostId 下持久化的主机基础信息与侦察摘要，返回 Markdown 报告。
 */
@Service
public class SessionReportService {

    public record Report(String sessionId, String report, String filename) {}

    private static final DateTimeFormatter REPORT_TIME_FMT = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault());

    private final AgentService agentService;
    private final AgentHostCacheRepository hostCacheRepository;
    private final AgentReconRepository reconRepository;

    public SessionReportService(AgentService agentService, AgentHostCacheRepository hostCacheRepository,
                                AgentReconRepository reconRepository) {
        this.agentService = agentService;
        this.hostCacheRepository = hostCacheRepository;
        this.reconRepository = reconRepository;
    }

    /**
     * 生成离线侦察报告（Markdown 格式）。
     *
     * @param session 目标会话（不能为 null）
     * @return 含报告内容和建议文件名的结果
     * @throws UseCaseException 若无法确定 agentId
     */
    public Report generate(AgentNodeSession session) {
        String userId = session.getCreateByUser();
        String agentId = session.resolveAgentId();
        if (agentId == null) {
            throw new UseCaseException(Kind.INVALID_INPUT, "无法确定 agentId，无法生成报告");
        }
        agentId = agentId.trim();
        String hostId = session.getCurrentHostId();

        Agent agent = agentService.findAgentById(agentId);
        String agentName = agent != null ? agent.getAgentName() : agentId;
        String connLink = agent != null && agent.getConnLink() != null ? agent.getConnLink() : "-";

        String reportTime = REPORT_TIME_FMT.format(Instant.now());

        StringBuilder sb = new StringBuilder();
        appendHeader(sb, agentName, connLink, reportTime);
        appendBasicInfoSection(sb, hostCacheRepository.loadBasicInfo(userId, agentId, hostId));
        appendReconSummarySection(sb, reconRepository.load(userId, agentId, hostId));

        String filename = "recon-report-" + agentName.replaceAll("[^\\w\\-]", "_") + ".md";
        return new Report(session.getSessionId(), sb.toString(), filename);
    }

    // ── 私有辅助 ──────────────────────────────────────────────────────────────

    private static void appendHeader(StringBuilder sb, String agentName, String connLink, String reportTime) {
        sb.append("# 侦察报告 — ").append(agentName).append("\n\n");
        sb.append("> 生成时间：").append(reportTime)
          .append("  \n> 连接地址：`").append(connLink).append("`\n\n");
        sb.append("---\n\n");
    }

    private static void appendBasicInfoSection(StringBuilder sb, Map<String, Object> basicInfo) {
        sb.append("## 主机基础信息\n\n");
        if (basicInfo != null) {
            appendInfoGroup(sb, "操作系统", asMap(basicInfo.get("OSInfo")),
                    "OSName", "OSVersion", "OSArch", "HostName");
            appendInfoGroup(sb, "当前用户", asMap(basicInfo.get("UserInfo")),
                    "UserName", "UserHome", "UserDir");
            appendInfoGroup(sb, "Java 运行时", asMap(basicInfo.get("JavaRuntimeInfo")),
                    "JavaVersion", "JavaVendor", "JavaHome");
            appendInfoGroup(sb, "中间件", asMap(basicInfo.get("MiddlewareInfo")),
                    "MiddlewareType", "MiddlewareVersion", "MiddlewareHome");
            appendInfoGroup(sb, "进程", asMap(basicInfo.get("ProcessInfo")),
                    "ProcessId", "ProcessName");
        } else {
            sb.append("_暂无缓存数据_\n");
        }
        sb.append("\n");
    }

    private static void appendReconSummarySection(StringBuilder sb, String reconSummary) {
        sb.append("## 侦察摘要\n\n");
        if (reconSummary != null && !reconSummary.isBlank()) {
            sb.append(reconSummary.trim()).append("\n");
        } else {
            sb.append("_暂无侦察摘要_\n");
        }
        sb.append("\n");
    }

    private static void appendInfoGroup(StringBuilder sb, String title,
                                        Map<?, ?> group, String... keys) {
        if (group == null || group.isEmpty()) return;
        sb.append("**").append(title).append("**\n\n");
        for (String key : keys) {
            Object val = group.get(key);
            if (val != null && !val.toString().isBlank()) {
                sb.append("- ").append(key).append(": ").append(val).append("\n");
            }
        }
        sb.append("\n");
    }

    private static Map<?, ?> asMap(Object obj) {
        return obj instanceof Map<?, ?> map ? map : null;
    }
}
