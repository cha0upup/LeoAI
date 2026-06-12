package org.leo.ai.tools.puppetnode;

import org.leo.ai.agent.AiToolContext;
import org.leo.ai.service.ReconSummaryDigestService;
import org.leo.ai.service.ReconSummaryOrganizeService;
import org.leo.core.session.PuppetNodeSession;
import org.leo.core.session.PuppetNodeSessionContainer;
import org.leo.core.util.session.PuppetNodeSessionWorkDirUtil;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.P;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 会话状态写入工具（AI 可直接调用）。
 *
 * <p>sessionId 通过 {@link AiToolContext} ThreadLocal 自动注入，不再作为工具参数暴露，
 * 避免框架内部实现细节泄漏到 AI 上下文中。
 */
@Component
public class SessionTools {

    private static final Logger log = LoggerFactory.getLogger(SessionTools.class);

    /** 摘要超过此长度时，自动触发 AI 整理压缩。 */
    private static final int AUTO_ORGANIZE_THRESHOLD = 5000;

    private final ReconSummaryDigestService reconSummaryDigestService;
    private final ReconSummaryOrganizeService reconSummaryOrganizeService;

    public SessionTools(ReconSummaryDigestService reconSummaryDigestService,
                        ReconSummaryOrganizeService reconSummaryOrganizeService) {
        this.reconSummaryDigestService = reconSummaryDigestService;
        this.reconSummaryOrganizeService = reconSummaryOrganizeService;
    }

    @Tool("""
            将纯文本侦察摘要写入当前 puppet（覆盖已有内容）。
            适合在完成一轮完整侦察后调用，将关键情报保存到 puppet 级侦察摘要，后续会话可自动引用。
            不要主动脱敏凭据、Token、密钥等；如源数据已脱敏则保存原值并注明。
            """)
    public Map<String, Object> setReconSummary(
            @P("【必填】Markdown 格式的完整侦察摘要，应包含目标概览、OS/中间件/Java 版本、已知凭据线索、内网拓扑等关键信息。非空") String reconSummary) {
        Map<String, Object> result = new HashMap<>();
        String sessionId = AiToolContext.getSessionId();
        if (sessionId == null) {
            result.put("success", false);
            result.put("message", "调用 setReconSummary 失败：上下文中无 sessionId。");
            return result;
        }
        if (reconSummary == null || reconSummary.isBlank()) {
            result.put("success", false);
            result.put("message", "调用 setReconSummary 失败：缺少必填参数 reconSummary。");
            return result;
        }
        PuppetNodeSession session = PuppetNodeSessionContainer.getSession(sessionId);
        if (session == null) {
            result.put("success", false);
            result.put("message", "会话不存在: " + sessionId);
            return result;
        }
        String normalized = reconSummary.trim();
        session.setReconSummary(normalized);
        PuppetNodeSessionWorkDirUtil.saveReconSummary(sessionId, normalized);
        triggerDigestIfNeeded(session);
        result.put("success", true);
        result.put("message", "侦察摘要已保存到 puppet，长度: " + normalized.length() + " 字符");
        return result;
    }

    @Tool("""
            向当前 puppet 的侦察摘要末尾追加新发现（增量更新）。
            不要主动脱敏凭据、Token、密钥等。
            示例调用：appendReconSummary(content="## 新发现\\n- 凭据 user:pass\\n- 端口 8080 暴露")。
            """)
    public Map<String, Object> appendReconSummary(
            @P("【必填】要追加的 Markdown 文本，非空。示例：\"## 新发现\\n- xxx\"") String content) {
        Map<String, Object> result = new HashMap<>();
        String sessionId = AiToolContext.getSessionId();
        if (sessionId == null) {
            result.put("success", false);
            result.put("message", "调用 appendReconSummary 失败：上下文中无 sessionId。");
            return result;
        }
        PuppetNodeSession session = PuppetNodeSessionContainer.getSession(sessionId);
        if (session == null) {
            result.put("success", false);
            result.put("message", "会话不存在: " + sessionId);
            return result;
        }
        if (content == null || content.isBlank()) {
            result.put("success", false);
            result.put("message", "调用 appendReconSummary 失败：缺少必填参数 content。请传入 content=\"要追加的 Markdown 文本\"。");
            return result;
        }
        String updated = PuppetNodeSessionWorkDirUtil.appendReconSummary(sessionId, content);
        if (updated == null) {
            session.appendReconSummary(content);
            updated = session.getReconSummary();
            PuppetNodeSessionWorkDirUtil.saveReconSummary(sessionId, updated);
        }
        int summaryLen = updated != null ? updated.length() : 0;
        if (summaryLen > AUTO_ORGANIZE_THRESHOLD) {
            try {
                String organized = reconSummaryOrganizeService.organize(updated);
                session.setReconSummary(organized);
                PuppetNodeSessionWorkDirUtil.saveReconSummary(sessionId, organized);
                log.debug("SessionTools: 摘要已达 {} 字符，自动整理压缩至 {} 字符", summaryLen, organized.length());
                summaryLen = organized.length();
            } catch (Exception e) {
                log.warn("SessionTools: 自动整理失败: {}", e.getMessage());
            }
        }
        triggerDigestIfNeeded(session);
        result.put("success", true);
        result.put("message", "已追加到 puppet 侦察摘要，当前摘要共 " + summaryLen + " 字符");
        return result;
    }

    @Tool("读取当前 puppet 的侦察摘要。任务开始前调用，了解已收集的目标情报。")
    public Map<String, Object> getReconSummary() {
        Map<String, Object> result = new HashMap<>();
        String sessionId = AiToolContext.getSessionId();
        if (sessionId == null) {
            result.put("success", false);
            result.put("message", "上下文中无 sessionId，无法读取侦察摘要");
            return result;
        }
        PuppetNodeSession session = PuppetNodeSessionContainer.getSession(sessionId);
        if (session == null) {
            result.put("success", false);
            result.put("message", "会话不存在: " + sessionId);
            return result;
        }
        String persisted = PuppetNodeSessionWorkDirUtil.loadReconSummary(sessionId);
        if (persisted != null) {
            session.setReconSummary(persisted);
        }
        result.put("success", true);
        result.put("hasReconSummary", session.hasReconSummary());
        result.put("reconSummary", session.getReconSummary());
        return result;
    }

    private void triggerDigestIfNeeded(PuppetNodeSession session) {
        if (session != null
                && session.hasReconSummary()
                && session.getReconSummary().length() >= ReconSummaryDigestService.DIGEST_THRESHOLD) {
            reconSummaryDigestService.generateAndSaveAsync(session);
        }
    }
}
