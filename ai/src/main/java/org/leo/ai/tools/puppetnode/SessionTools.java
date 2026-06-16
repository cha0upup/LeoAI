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

    @Tool(name = "manage_recon_summary", value = """
            管理当前 puppet 的侦察摘要（统一入口）。action 必须是以下之一：
            - get: 读取当前摘要全文（content 参数留空）。任务开始前调用，了解已收集情报。
            - set: 用 content 完全覆盖现有摘要（仅在完成一轮完整侦察后调用，会丢弃旧内容）。
            - append: 把 content 追加到摘要末尾（推荐方式，用于增量记录新发现）。

            重要规则：
            - 不要主动脱敏凭据 / Token / 密钥 / 证书路径，按原文保存
            - content 应为 Markdown 格式，append 示例: "## 新发现\\n- 凭据 user:pass\\n- 端口 8080 暴露"
            - 当摘要超过阈值时，append 会自动触发 AI 整理压缩
            """)
    public Map<String, Object> manageReconSummary(
            @P("操作类型: get | set | append") String action,
            @P("Markdown 文本。set/append 必填非空；get 时传空字符串") String content) {
        String act = action == null ? "" : action.trim().toLowerCase();
        return switch (act) {
            case "get"    -> doGetReconSummary();
            case "set"    -> doSetReconSummary(content);
            case "append" -> doAppendReconSummary(content);
            default       -> throw new IllegalArgumentException(
                    "无效的 action: " + action + "，可选值: get | set | append");
        };
    }

    // ── 内部实现（按 action 分发） ────────────────────────────────────────────

    private Map<String, Object> doSetReconSummary(String reconSummary) {
        if (reconSummary == null || reconSummary.isBlank()) {
            throw new IllegalArgumentException("set 失败：缺少必填参数 content（侦察摘要内容）");
        }
        PuppetNodeSession session = currentSessionOrThrow();
        String normalized = reconSummary.trim();
        session.setReconSummary(normalized);
        PuppetNodeSessionWorkDirUtil.saveReconSummary(session.getSessionId(), normalized);
        triggerDigestIfNeeded(session);
        return Map.of(
                "success", true,
                "message", "侦察摘要已保存，长度: " + normalized.length() + " 字符"
        );
    }

    private Map<String, Object> doAppendReconSummary(String content) {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("append 失败：缺少必填参数 content（要追加的 Markdown 文本）");
        }
        PuppetNodeSession session = currentSessionOrThrow();
        String sessionId = session.getSessionId();
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
                // 整理失败属于旁路逻辑，不应影响 append 主路径，记日志后继续
                log.warn("SessionTools: 自动整理失败: {}", e.getMessage());
            }
        }
        triggerDigestIfNeeded(session);
        return Map.of(
                "success", true,
                "message", "已追加到侦察摘要，当前共 " + summaryLen + " 字符"
        );
    }

    private Map<String, Object> doGetReconSummary() {
        PuppetNodeSession session = currentSessionOrThrow();
        String persisted = PuppetNodeSessionWorkDirUtil.loadReconSummary(session.getSessionId());
        if (persisted != null) {
            session.setReconSummary(persisted);
        }
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("hasReconSummary", session.hasReconSummary());
        result.put("reconSummary", session.getReconSummary());
        return result;
    }

    /**
     * 解析当前 ThreadLocal 上下文中的 puppet 会话；找不到则抛出 {@link IllegalStateException}，
     * langchain4j 默认错误处理器会把异常 message 作为工具结果回传给 LLM。
     */
    private PuppetNodeSession currentSessionOrThrow() {
        String sessionId = AiToolContext.getSessionId();
        if (sessionId == null) {
            throw new IllegalStateException("上下文中无 sessionId（ThreadLocal 未注入），无法定位 puppet 会话");
        }
        PuppetNodeSession session = PuppetNodeSessionContainer.getSession(sessionId);
        if (session == null) {
            throw new IllegalStateException("会话不存在: " + sessionId);
        }
        return session;
    }

    private void triggerDigestIfNeeded(PuppetNodeSession session) {
        if (session != null
                && session.hasReconSummary()
                && session.getReconSummary().length() >= ReconSummaryDigestService.DIGEST_THRESHOLD) {
            reconSummaryDigestService.generateAndSaveAsync(session);
        }
    }
}
