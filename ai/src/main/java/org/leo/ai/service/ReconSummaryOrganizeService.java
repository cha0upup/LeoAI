package org.leo.ai.service;

import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 侦察摘要 AI 整理服务。
 *
 * <p>接受原始侦察摘要文本，调用一次 LLM 推理，返回结构更清晰、去重去冗余的
 * Markdown 整理版本，不丢失任何有效情报。
 */
@Service
public class ReconSummaryOrganizeService {

    private static final Logger log = LoggerFactory.getLogger(ReconSummaryOrganizeService.class);

    private static final String SYSTEM_PROMPT = """
            你是一名专业的渗透测试工程师，擅长整理和归纳侦察情报。

            用户将提供一段侦察摘要原文，内容可能来自多次 AI 对话的自动追加，存在重复、
            格式不一致、信息分散等问题。

            你的任务：对该摘要进行整理，输出结构清晰的 Markdown 文档。

            整理规则：
            1. 不得删除任何有价值的情报，包括 IP、端口、路径、凭据线索、漏洞信息等。
            2. 合并重复或高度相似的条目，去除冗余描述。
            3. 按以下推荐章节组织内容（若原文无对应内容则跳过该章节）：
               - ## 目标概览（OS、架构、中间件、Java 版本等基础信息）
               - ## 网络与存活主机（IP 段、开放端口、内网存活）
               - ## 服务与应用（HTTP 服务、数据库、缓存、API 等）
               - ## 已发现凭据线索（JDBC、Redis、SSH 密钥路径、明文密码等）
               - ## 漏洞与风险点（CVE、错误配置、可利用点）
               - ## 已执行操作与结果（已上传文件、已建立隧道、已执行命令等）
               - ## 其他情报（不属于以上分类的补充信息）
            4. 保持 Markdown 格式，使用无序列表和代码块。
            5. 只输出整理后的 Markdown 正文，不要包含任何前言、解释或代码块包裹。
            """;

    private final ChatModel chatModel;

    @Autowired
    public ReconSummaryOrganizeService(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    /**
     * 对侦察摘要进行 AI 整理，返回整理后的 Markdown 文本。
     *
     * @param rawSummary 原始侦察摘要
     * @return 整理后的摘要文本
     * @throws IllegalArgumentException 若原始摘要为空
     * @throws RuntimeException         若 AI 调用失败
     */
    public String organize(String rawSummary) {
        if (rawSummary == null || rawSummary.isBlank()) {
            throw new IllegalArgumentException("侦察摘要内容为空，无需整理");
        }

        try {
            ChatResponse response = chatModel.chat(ChatRequest.builder()
                    .messages(List.of(
                            new SystemMessage(SYSTEM_PROMPT),
                            new UserMessage(rawSummary.trim())
                    ))
                    .build());
            String result = response.aiMessage().text();
            if (result == null || result.isBlank()) {
                throw new RuntimeException("AI 返回内容为空");
            }
            return result.trim();
        } catch (RuntimeException e) {
            log.error("ReconSummaryOrganizeService AI 调用失败: {}", e.getMessage(), e);
            throw new RuntimeException("AI 整理失败: " + e.getMessage(), e);
        }
    }
}
