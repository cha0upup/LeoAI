package org.leo.ai.agent;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.Content;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.TokenCountEstimator;

/**
 * 轻量级 token 估算器，基于字符统计，无需网络调用。
 *
 * <p>估算策略：
 * <ul>
 *   <li>CJK 字符（中日韩）：约 1.5 token/字</li>
 *   <li>ASCII / 拉丁字符：约 0.25 token/字（即 4 字符 ≈ 1 token）</li>
 *   <li>每条消息固定开销 4 token（角色标签等）</li>
 *   <li>工具调用 / 工具结果附加 50 token 开销（JSON schema、函数名等）</li>
 * </ul>
 *
 * <p>略微高估是有意为之：宁可提前淘汰旧消息，不可因低估而超出模型上下文窗口。
 */
public class CharBasedTokenEstimator implements TokenCountEstimator {

    /** 每条消息的固定开销（角色标签、分隔符等）。 */
    private static final int PER_MESSAGE_OVERHEAD = 4;

    /** 工具调用 / 工具结果的额外开销（JSON schema、函数名等）。 */
    private static final int TOOL_OVERHEAD = 50;

    @Override
    public int estimateTokenCountInText(String text) {
        return estimateTextTokens(text);
    }

    @Override
    public int estimateTokenCountInMessage(ChatMessage message) {
        int tokens = PER_MESSAGE_OVERHEAD;

        if (message instanceof SystemMessage sm) {
            tokens += estimateTextTokens(sm.text());
        } else if (message instanceof UserMessage um) {
            tokens += estimateContentTokens(um);
        } else if (message instanceof AiMessage am) {
            if (am.text() != null) {
                tokens += estimateTextTokens(am.text());
            }
            var toolReqs = am.toolExecutionRequests();
            if (toolReqs != null && !toolReqs.isEmpty()) {
                for (var req : toolReqs) {
                    tokens += TOOL_OVERHEAD;
                    tokens += estimateTextTokens(req.name());
                    tokens += estimateTextTokens(req.arguments());
                }
            }
        } else if (message instanceof ToolExecutionResultMessage trm) {
            tokens += TOOL_OVERHEAD;
            tokens += estimateTextTokens(trm.text());
        } else {
            tokens += estimateTextTokens(message.toString());
        }

        return tokens;
    }

    @Override
    public int estimateTokenCountInMessages(Iterable<ChatMessage> messages) {
        int total = 0;
        for (ChatMessage msg : messages) {
            total += estimateTokenCountInMessage(msg);
        }
        return total;
    }

    private int estimateContentTokens(UserMessage userMessage) {
        int tokens = 0;
        for (Content content : userMessage.contents()) {
            if (content instanceof TextContent tc) {
                tokens += estimateTextTokens(tc.text());
            } else if (content instanceof ImageContent) {
                // 图片 token 依赖分辨率，保守估算 1000
                tokens += 1000;
            } else {
                tokens += 100;
            }
        }
        return tokens;
    }

    /**
     * 估算纯文本的 token 数。
     * CJK 字符按 1.5 token/字符，其余按 0.25 token/字符（即 4 字符 ≈ 1 token）。
     */
    static int estimateTextTokens(String text) {
        if (text == null || text.isEmpty()) return 0;

        int cjkChars = 0;
        int otherChars = 0;
        for (int i = 0; i < text.length(); i++) {
            if (isCjk(text.charAt(i))) {
                cjkChars++;
            } else {
                otherChars++;
            }
        }

        double estimate = cjkChars * 1.5 + otherChars * 0.25;
        return Math.max(1, (int) Math.ceil(estimate));
    }

    /**
     * 判断字符是否属于 CJK 范围：
     * 0x2E80-0x9FFF（CJK 部首、统一表意文字、平假名片假名等）、
     * 0xF900-0xFAFF（兼容表意文字）、
     * 0xFE30-0xFE4F（CJK 兼容形式）。
     */
    private static boolean isCjk(char c) {
        return (c >= 0x2E80 && c <= 0x9FFF)
                || (c >= 0xF900 && c <= 0xFAFF)
                || (c >= 0xFE30 && c <= 0xFE4F);
    }
}
