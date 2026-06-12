package org.leo.ai.service;

import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.leo.core.session.PuppetNodeSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 侦察摘要精简版（Digest）生成服务。
 *
 * <p>当完整摘要超过 {@value #DIGEST_THRESHOLD} 字符时，调用 LLM 将其压缩为
 * 最多 500 字符的要点列表，写入 {@link PuppetNodeSession#setReconSummaryDigest(String)}。
 * PuppetNodeSystemPromptProvider 优先注入 digest 版本，节省 token 预算。
 */
@Service
public class ReconSummaryDigestService {

    private static final Logger log = LoggerFactory.getLogger(ReconSummaryDigestService.class);

    /** 完整摘要超过此字符数时，才有生成 digest 的意义。 */
    public static final int DIGEST_THRESHOLD = 1000;

    private static final String SYSTEM_PROMPT = """
            你是侦察情报分析员，负责将渗透测试侦察摘要压缩为极简要点。

            规则：
            1. 压缩后总长度不超过 500 字符（含换行）。
            2. 每条要点独占一行，以"·"开头，不使用 Markdown 标题或代码块。
            3. 必须保留的内容：IP 地址、开放端口/服务、中间件版本、凭据线索（连接串、用户名、密码/Token/Key 原始值，如摘要中存在）、CVE 编号、已确认的可利用点。
            4. 不要主动脱敏、改写或截断单个凭据值；如果来源本身是脱敏值，按原样保留。
            5. 删除冗余描述和重复信息。
            6. 只输出压缩后的纯文本，不添加任何前言或解释。
            """;

    private final ChatModel chatModel;

    @Autowired
    public ReconSummaryDigestService(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    /**
     * 同步生成 digest 并写入 session。
     */
    public String generateAndSave(PuppetNodeSession session) {
        String summary = session.getReconSummary();
        if (summary == null || summary.isBlank()) {
            throw new IllegalStateException("摘要为空，无法生成精简版");
        }
        String digest = callAi(summary);
        session.setReconSummaryDigest(digest);
        return digest;
    }

    /**
     * 异步生成 digest（不阻塞调用线程）。
     */
    @Async
    public void generateAndSaveAsync(PuppetNodeSession session) {
        if (session == null) return;
        String summary = session.getReconSummary();
        if (summary == null || summary.length() < DIGEST_THRESHOLD) return;
        try {
            String digest = callAi(summary);
            session.setReconSummaryDigest(digest);
            log.debug("ReconSummaryDigest 异步生成完成，sessionId={}, digestLen={}",
                    session.getSessionId(), digest.length());
        } catch (Exception e) {
            log.warn("ReconSummaryDigest 异步生成失败，sessionId={}: {}", session.getSessionId(), e.getMessage());
        }
    }

    /**
     * 获取指定 session 的 digest（用于 system prompt 注入）。
     * <p>
     * 优先返回 AI 生成的精简 digest（~500 字符）。
     * 如果 digest 尚未生成，回退到原始摘要但限制最大长度，
     * 避免超长摘要撑爆 system prompt token 预算。
     */
    public String getDigest(String sessionId) {
        org.leo.core.session.PuppetNodeSession session =
                org.leo.core.session.PuppetNodeSessionContainer.getSession(sessionId);
        if (session == null) return null;
        if (session.hasFreshReconSummaryDigest()) {
            return session.getReconSummaryDigest();
        }
        String summary = session.getReconSummary();
        if (summary == null || summary.isBlank()) return null;
        // 原始摘要超过阈值时，截取前段并标注截断，避免 system prompt 过长
        if (summary.length() > DIGEST_THRESHOLD) {
            return summary.substring(0, DIGEST_THRESHOLD) + "\n\n... [侦察摘要过长，已截断。完整精简版正在生成中]";
        }
        return summary;
    }

    private String callAi(String summary) {
        ChatResponse response = chatModel.chat(ChatRequest.builder()
                .messages(List.of(
                        new SystemMessage(SYSTEM_PROMPT),
                        new UserMessage(summary.trim())
                ))
                .build());
        var aiMessage = response.aiMessage();
        String result = aiMessage.text();
        // reasoning 模型（MiMo 等）有时会把最终输出留在 thinking 字段里，
        // 尤其当 finish_reason=LENGTH 截断、思考阶段还没切到正文输出时。
        if (result == null || result.isBlank()) {
            result = aiMessage.thinking();
        }
        if (result == null || result.isBlank()) {
            var metadata = response.metadata();
            var finishReason = metadata != null ? metadata.finishReason() : null;
            // 把整个 aiMessage / metadata / token usage / finishReason 全打出来，
            // 用来判断到底是 thinking 吃光了 token、网关过滤、还是别的原因。
            log.warn("ReconSummaryDigest AI 返回空文本 inputLen={} aiMessage={} metadata={} tokenUsage={} finishReason={}",
                    summary.length(),
                    aiMessage,
                    metadata,
                    metadata != null ? metadata.tokenUsage() : null,
                    finishReason);
            String hint = finishReason != null
                    ? "（finishReason=" + finishReason + "，请在该模型的 Profile 上调大 maxOutputTokens）"
                    : "";
            throw new RuntimeException("AI 返回为空" + hint);
        }
        return result.trim().length() > 600 ? result.trim().substring(0, 600) : result.trim();
    }
}
