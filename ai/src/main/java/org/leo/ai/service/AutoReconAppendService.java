package org.leo.ai.service;

import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.leo.core.session.PuppetNodeSession;
import org.leo.core.session.PuppetNodeSessionContainer;
import org.leo.core.util.session.PuppetNodeSessionWorkDirUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.Semaphore;

/**
 * 自动分析工具结果并提取侦察情报，追加到会话 reconSummary。
 *
 * <p>在 AI 工具执行完成后异步触发，不阻塞正常对话流程。
 *
 * <p>通过 {@value #MAX_CONCURRENT_ANALYSIS} 的信号量限制并发 LLM 分析调用数，
 * 防止并行工具结果同时触发大量 API 调用。
 */
@Service
public class AutoReconAppendService {

    private static final Logger log = LoggerFactory.getLogger(AutoReconAppendService.class);

    /** 工具输出超过此长度才有分析价值（过滤纯错误/空输出）。 */
    private static final int MIN_CONTENT_LEN = 80;

    /** 单次追加内容上限，防止大量输出撑大摘要。 */
    private static final int MAX_APPEND_LEN = 600;

    /** 摘要超过此长度时，自动触发 AI 整理压缩。 */
    private static final int AUTO_ORGANIZE_THRESHOLD = 5000;

    /** 全局并发分析调用上限，超过的信号量 acquire 会直接返回不阻塞。 */
    private static final int MAX_CONCURRENT_ANALYSIS = 3;

    private final Semaphore analysisSemaphore = new Semaphore(MAX_CONCURRENT_ANALYSIS);

    private static final String SYSTEM_PROMPT = """
            你是渗透测试情报提取员。
            用户将提供：
            1. 当前已有的侦察摘要（可能为空）
            2. 一段新的命令或文件操作输出结果

            你的任务：从新输出中提取有侦察价值的情报，但只输出摘要中尚未记录的新信息。

            有价值的情报包括：
            - IP 地址、端口、域名、URL
            - 操作系统版本、内核版本、中间件版本
            - 数据库连接串、Redis/Memcached 地址
            - 用户名、密码、API Key、Token、证书路径
            - CVE 漏洞编号或已知漏洞描述
            - 内网存活主机或网段
            - 重要配置文件路径和内容摘要

            规则：
            - 若新输出中的情报已在摘要中记录（相同或高度相似），跳过该条目
            - 只输出真正新增的情报，以 Markdown 无序列表格式（以 - 开头）
            - 总长度不超过 500 字符，不加任何标题或前言
            - 如果没有新的有价值情报，只输出一个词：SKIP
            """;

    private final ChatModel chatModel;
    private final ReconSummaryDigestService reconSummaryDigestService;
    private final ReconSummaryOrganizeService reconSummaryOrganizeService;

    @Autowired
    public AutoReconAppendService(ChatModel chatModel,
                                  ReconSummaryDigestService reconSummaryDigestService,
                                  ReconSummaryOrganizeService reconSummaryOrganizeService) {
        this.chatModel = chatModel;
        this.reconSummaryDigestService = reconSummaryDigestService;
        this.reconSummaryOrganizeService = reconSummaryOrganizeService;
    }

    /**
     * 异步分析工具输出，若有情报价值则追加到 reconSummary。
     *
     * @param sessionId   会话 ID
     * @param toolName    触发分析的工具名称（用于注释）
     * @param toolOutput  工具原始输出文本
     */
    @Async
    public void analyzeAndAppend(String sessionId, String toolName, String toolOutput) {
        if (toolOutput == null || toolOutput.trim().length() < MIN_CONTENT_LEN) return;

        PuppetNodeSession session = PuppetNodeSessionContainer.getSession(sessionId);
        if (session == null) return;

        if (!analysisSemaphore.tryAcquire()) {
            log.debug("AutoReconAppend: 并发分析已达上限，跳过本次追加，session={}", sessionId);
            return;
        }
        try {
            String truncated = toolOutput.length() > 3000
                    ? toolOutput.substring(0, 3000) + "\n...(输出已截断)" : toolOutput;

            // 优先用精简版摘要做去重上下文（仅在 digest 未过期时使用），降低 token 消耗；
            // digest 过期或不存在时截取完整摘要前 1500 字符
            String currentSummary = session.getReconSummary();
            String contextSummary;
            if (session.hasFreshReconSummaryDigest()) {
                contextSummary = session.getReconSummaryDigest().trim();
            } else if (currentSummary != null && !currentSummary.isBlank()) {
                contextSummary = currentSummary.length() > 1500
                        ? currentSummary.substring(0, 1500) + "\n...(摘要已截断)" : currentSummary.trim();
            } else {
                contextSummary = "（暂无）";
            }
            String summaryContext = "## 当前已有侦察摘要\n" + contextSummary + "\n\n## 新的工具输出\n" + truncated;

            ChatResponse response = chatModel.chat(ChatRequest.builder()
                    .messages(List.of(
                            new SystemMessage(SYSTEM_PROMPT),
                            new UserMessage(summaryContext)
                    ))
                    .build());
            String result = response.aiMessage().text();
            if (result == null || result.isBlank() || result.trim().equalsIgnoreCase("SKIP")) {
                return;
            }

            String toAppend = result.trim();
            if (toAppend.length() > MAX_APPEND_LEN) {
                toAppend = toAppend.substring(0, MAX_APPEND_LEN);
            }

            String updated = PuppetNodeSessionWorkDirUtil.appendReconSummary(sessionId, toAppend);
            if (updated == null) {
                session.appendReconSummary(toAppend);
                updated = session.getReconSummary();
                PuppetNodeSessionWorkDirUtil.saveReconSummary(sessionId, updated);
            }

            int summaryLen = updated != null ? updated.length() : 0;

            // 摘要过大时自动整理压缩（去重、归类），避免无限膨胀
            if (summaryLen > AUTO_ORGANIZE_THRESHOLD) {
                try {
                    String organized = reconSummaryOrganizeService.organize(updated);
                    session.setReconSummary(organized);
                    PuppetNodeSessionWorkDirUtil.saveReconSummary(sessionId, organized);
                    log.debug("AutoReconAppend: 摘要已达 {} 字符，自动整理压缩至 {} 字符，session={}",
                            summaryLen, organized.length(), sessionId);
                    summaryLen = organized.length();
                } catch (Exception oe) {
                    log.warn("AutoReconAppend: 自动整理失败，session={}: {}", sessionId, oe.getMessage());
                }
            }

            if (summaryLen >= ReconSummaryDigestService.DIGEST_THRESHOLD) {
                reconSummaryDigestService.generateAndSaveAsync(session);
            }
            log.debug("AutoReconAppend: [{}] 追加 {} 字符到 session={}", toolName, toAppend.length(), sessionId);
        } catch (Exception e) {
            log.debug("AutoReconAppend 分析失败，session={}: {}", sessionId, e.getMessage());
        } finally {
            analysisSemaphore.release();
        }
    }
}
