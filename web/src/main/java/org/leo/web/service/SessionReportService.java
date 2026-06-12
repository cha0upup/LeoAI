package org.leo.web.service;

import org.leo.core.entity.Puppet;
import org.leo.core.session.PuppetNodeSession;
import org.leo.core.util.session.PuppetNodeSessionWorkDirUtil;
import org.leo.service.PuppetService;
import org.leo.web.dto.platform.session.SessionDtos.ReportResponse;
import org.leo.web.exception.ApiException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * 会话侦察报告生成服务。
 *
 * <p>汇聚 puppet 级持久化目录中的 basic-info.json、recon-summary.md、
 * recon-summary.json，拼装为结构化 Markdown 字符串返回给调用方。
 */
@Service
public class SessionReportService {

    private static final DateTimeFormatter REPORT_TIME_FMT = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault());

    private final PuppetService puppetService;

    @Autowired
    public SessionReportService(PuppetService puppetService) {
        this.puppetService = puppetService;
    }

    /**
     * 生成离线侦察报告（Markdown 格式）。
     *
     * @param session 目标会话（不能为 null）
     * @return 含报告内容和建议文件名的 DTO
     * @throws ApiException 若无法确定 puppetId
     */
    public ReportResponse generate(PuppetNodeSession session) {
        String userId   = session.getCreateByUser();
        String puppetId = resolvePuppetId(session);

        Puppet puppet     = puppetService.findPuppetById(puppetId);
        String puppetName = puppet != null ? puppet.getPuppetName() : puppetId;
        String connLink   = puppet != null && puppet.getConnLink() != null ? puppet.getConnLink() : "-";

        String reportTime = REPORT_TIME_FMT.format(Instant.now());

        StringBuilder sb = new StringBuilder();
        appendHeader(sb, puppetName, connLink, reportTime);
        appendBasicInfoSection(sb, PuppetNodeSessionWorkDirUtil.loadBasicInfo(userId, puppetId));
        appendReconSummarySection(sb, PuppetNodeSessionWorkDirUtil.loadReconSummary(userId, puppetId));

        String filename = "recon-report-" + puppetName.replaceAll("[^\\w\\-]", "_") + ".md";
        return new ReportResponse(session.getSessionId(), sb.toString(), filename);
    }

    // ── 私有辅助 ──────────────────────────────────────────────────────────────

    private String resolvePuppetId(PuppetNodeSession session) {
        if (session.isCacheMode()) {
            String id = session.getPuppetId();
            if (id != null && !id.isBlank()) return id.trim();
        } else if (session.getJavaPuppetNode() != null
                && session.getJavaPuppetNode().getPuppet() != null) {
            return session.getJavaPuppetNode().getPuppet().getPuppetId();
        }
        throw ApiException.badRequest("无法确定 puppetId，无法生成报告");
    }

    private void appendHeader(StringBuilder sb, String puppetName, String connLink, String reportTime) {
        sb.append("# 侦察报告 — ").append(puppetName).append("\n\n");
        sb.append("> 生成时间：").append(reportTime)
          .append("  \n> 连接地址：`").append(connLink).append("`\n\n");
        sb.append("---\n\n");
    }

    private void appendBasicInfoSection(StringBuilder sb, Map<String, Object> basicInfo) {
        sb.append("## 主机基础信息\n\n");
        if (basicInfo != null) {
            appendInfoGroup(sb, "操作系统",   castMap(basicInfo.get("OSInfo")),
                    "OSName", "OSVersion", "OSArch", "HostName");
            appendInfoGroup(sb, "当前用户",   castMap(basicInfo.get("UserInfo")),
                    "UserName", "UserHome", "UserDir");
            appendInfoGroup(sb, "Java 运行时", castMap(basicInfo.get("JavaRuntimeInfo")),
                    "JavaVersion", "JavaVendor", "JavaHome");
            appendInfoGroup(sb, "中间件",     castMap(basicInfo.get("MiddlewareInfo")),
                    "MiddlewareType", "MiddlewareVersion", "MiddlewareHome");
            appendInfoGroup(sb, "进程",       castMap(basicInfo.get("ProcessInfo")),
                    "ProcessId", "ProcessName");
        } else {
            sb.append("_暂无缓存数据_\n");
        }
        sb.append("\n");
    }

    private void appendReconSummarySection(StringBuilder sb, String reconSummary) {
        sb.append("## 侦察摘要\n\n");
        if (reconSummary != null && !reconSummary.isBlank()) {
            sb.append(reconSummary.trim()).append("\n");
        } else {
            sb.append("_暂无侦察摘要_\n");
        }
        sb.append("\n");
    }

    private void appendInfoGroup(StringBuilder sb, String title,
                                 Map<String, Object> group, String... keys) {
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

    @SuppressWarnings("unchecked")
    private Map<String, Object> castMap(Object obj) {
        return obj instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
    }
}
