package org.leo.ai.agent;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.TokenCountEstimator;
import dev.langchain4j.model.chat.ChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 上下文压缩服务：在对话历史接近上下文窗口上限时，自动将旧消息压缩为摘要，
 * 以少量 token 代价保留关键信息，避免消息被硬淘汰（TokenWindowChatMemory eviction）。
 *
 * <p>触发条件：当前积累消息的估算 token 数超过窗口上限的 {@link #COMPRESSION_THRESHOLD_RATIO}。
 * <p>压缩策略：取最早的消息段（约占当前总消息的 50%），调用非流式 LLM 生成摘要，
 * 将原消息替换为一条摘要 SystemMessage。
 * <p>并发控制：同一 memoryId 同一时刻只允许一次压缩，避免并发压缩导致消息丢失。
 *
 * <p>典型场景：1M 上下文模型 + 长对话侦察任务，在 ~800K token 时触发压缩，
 * 将前 ~400K token 的历史压缩为 ~2K token 的摘要，使对话可以继续推进到接近 1M 上限。
 */
public class ContextCompressionService {

    private static final Logger log = LoggerFactory.getLogger(ContextCompressionService.class);

    /** 触发压缩的阈值比例：当前 token 数超过上下文窗口的 80% 时触发。 */
    static final double COMPRESSION_THRESHOLD_RATIO = 0.80;

    /** 压缩后保留的最小 token 预算：压缩不会把上下文削到低于这个值。 */
    static final int MIN_REMAINING_TOKENS = 32_000;

    /** 上下文窗口小于此值不启用压缩（小窗口压缩性价比太低）。 */
    static final int MIN_WINDOW_FOR_COMPRESSION = 100_000;

    /** 单条压缩的最大消息数，防止 LLM 调用过重。 */
    static final int MAX_MESSAGES_PER_COMPRESSION = 20;

    /** 压缩保护锁：同 memoryId 同一时刻只能执行一次压缩。 */
    private final ConcurrentMap<String, Object> compressionLocks = new ConcurrentHashMap<>();

    private final ChatModel chatModel;
    private final TokenCountEstimator tokenEstimator;

    public ContextCompressionService(ChatModel chatModel, TokenCountEstimator tokenEstimator) {
        this.chatModel = chatModel;
        this.tokenEstimator = tokenEstimator;
    }

    /**
     * 检查是否需要压缩，需要时执行压缩并返回压缩后的消息列表。
     *
     * @param memoryId      对话标识
     * @param messages      当前完整消息列表（由 ChatMemory.messages() 返回）
     * @param maxTokens     上下文窗口上限（token 数）
     * @return 压缩后的消息列表；如果未触发压缩则返回原列表
     */
    public List<ChatMessage> compressIfNeeded(String memoryId, List<ChatMessage> messages, int maxTokens) {
        if (messages == null || messages.size() < 8) return messages;
        if (maxTokens < MIN_WINDOW_FOR_COMPRESSION) return messages;

        int currentTokens = tokenEstimator.estimateTokenCountInMessages(messages);
        int threshold = (int) (maxTokens * COMPRESSION_THRESHOLD_RATIO);
        if (currentTokens < threshold) return messages;

        // 只压缩到不低于 MIN_REMAINING_TOKENS
        int maxRemovableTokens = currentTokens - MIN_REMAINING_TOKENS;
        if (maxRemovableTokens <= 0) return messages;

        // ── 并发控制：锁定 memoryId ──
        Object lock = compressionLocks.computeIfAbsent(memoryId, k -> new Object());
        synchronized (lock) {
            try {
                // 二次检查（其他线程可能已完成压缩）
                List<ChatMessage> latest = messages;
                int latestTokens = tokenEstimator.estimateTokenCountInMessages(latest);
                if (latestTokens < threshold) return latest;
                return doCompress(latest, maxRemovableTokens, currentTokens);
            } finally {
                compressionLocks.remove(memoryId);
            }
        }
    }

    /**
     * 执行实际的压缩操作。
     */
    private List<ChatMessage> doCompress(List<ChatMessage> messages, int maxRemovableTokens, int currentTokens) {
        // ── 选择要压缩的消息段 ──
        // 从最早的消息开始，累计 token 数不超过 maxRemovableTokens，最多 MAX_MESSAGES_PER_COMPRESSION 条
        int accumulated = 0;
        int endIdx = Math.min(MAX_MESSAGES_PER_COMPRESSION, messages.size());
        for (int i = 0; i < endIdx; i++) {
            int msgTokens = tokenEstimator.estimateTokenCountInMessage(messages.get(i));
            if (accumulated + msgTokens > maxRemovableTokens) {
                endIdx = i;
                break;
            }
            accumulated += msgTokens;
        }
        if (endIdx == 0) return messages;

        List<ChatMessage> toCompress = new ArrayList<>(messages.subList(0, endIdx));
        List<ChatMessage> remaining = new ArrayList<>(messages.subList(endIdx, messages.size()));

        // ── 调用 LLM 生成摘要 ──
        String summary;
        try {
            summary = summarize(toCompress);
        } catch (Exception e) {
            log.warn("上下文压缩 LLM 调用失败: {}，跳过本次压缩", e.getMessage());
            return messages;
        }
        if (summary == null || summary.isBlank()) return messages;

        // ── 构建压缩后的消息列表 ──
        int savedTokens = currentTokens - tokenEstimator.estimateTokenCountInMessages(remaining)
                - tokenEstimator.estimateTokenCountInText(summary);
        log.info("上下文压缩完成: memoryId 从 {}→{} token, 压缩 {} 条消息→{} 字符摘要, 节省约 {}K token",
                extractMemoryIdForLog(messages), savedTokens,
                toCompress.size(), summary.length(), savedTokens / 1000);

        List<ChatMessage> result = new ArrayList<>();
        result.add(new SystemMessage("[历史摘要]\n" + summary));
        result.addAll(remaining);
        return result;
    }

    /**
     * 调用非流式 LLM 将消息段总结为精炼摘要。
     */
    private String summarize(List<ChatMessage> messages) {
        StringBuilder input = new StringBuilder("请将以下对话历史压缩为精炼的技术摘要，保留以下关键信息：\n");
        input.append("- puppet 目标的操作系统、中间件、核心服务版本\n");
        input.append("- 已发现的凭据（数据库密码、JNDI、环境变量密钥等）\n");
        input.append("- 已确认的文件路径和目录结构\n");
        input.append("- 已执行的关键操作和结果\n");
        input.append("- 失败尝试和错误原因\n\n");
        input.append("只输出摘要正文，不要加解释性前缀。\n\n---\n\n");

        int msgCount = 0;
        for (ChatMessage msg : messages) {
            if (msgCount >= MAX_MESSAGES_PER_COMPRESSION) break;
            String text = messageToText(msg);
            if (!text.isBlank()) {
                input.append(text).append("\n");
                msgCount++;
            }
        }

        String userInput = input.toString();
        if (userInput.length() > 16_000) {
            userInput = userInput.substring(0, 16_000) + "\n\n[输入截断]";
        }

        try {
            var response = chatModel.chat(
                    dev.langchain4j.model.chat.request.ChatRequest.builder()
                            .messages(new SystemMessage(
                                    "你是一名后渗透侦察信息压缩专家。请将以下对话历史提炼为最关键的技术信息摘要。"),
                                    UserMessage.from(userInput))
                            .build());
            var aiMessage = response.aiMessage();
            return aiMessage != null ? aiMessage.text() : "";
        } catch (Exception e) {
            throw new RuntimeException("上下文压缩 LLM 调用失败", e);
        }
    }

    /** 将单条消息转为可读文本，用于拼装压缩输入。 */
    private String messageToText(ChatMessage msg) {
        if (msg instanceof SystemMessage sm) return sm.text();
        if (msg instanceof UserMessage um) {
            String text = "";
            for (var c : um.contents()) {
                if (c instanceof dev.langchain4j.data.message.TextContent tc) text += tc.text();
            }
            return text;
        }
        if (msg instanceof dev.langchain4j.data.message.AiMessage am) {
            if (am.text() != null && !am.text().isBlank()) return am.text();
        }
        if (msg instanceof dev.langchain4j.data.message.ToolExecutionResultMessage trm) {
            String text = trm.text();
            if (text != null && text.length() > 500) text = text.substring(0, 500) + "...";
            return "工具结果(" + trm.toolName() + "): " + text;
        }
        return msg.toString();
    }

    /** 从消息列表中提取 memoryId 用于日志（取第一条用户消息截断）。 */
    private String extractMemoryIdForLog(List<ChatMessage> messages) {
        for (ChatMessage msg : messages) {
            if (msg instanceof UserMessage um) {
                for (var c : um.contents()) {
                    if (c instanceof dev.langchain4j.data.message.TextContent tc) {
                        String t = tc.text();
                        return t.length() > 40 ? t.substring(0, 40) + "..." : t;
                    }
                }
            }
        }
        return "unknown";
    }
}
