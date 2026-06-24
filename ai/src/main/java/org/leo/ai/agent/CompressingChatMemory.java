package org.leo.ai.agent;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.model.TokenCountEstimator;

import java.util.ArrayList;
import java.util.List;

/**
 * 带压缩能力的 ChatMemory 包装器。
 *
 * <p>包装一个 {@link dev.langchain4j.memory.chat.MessageWindowChatMemory}，
 * 在每次 {@link #messages()} 调用时检查是否需要压缩旧消息为摘要，
 * 由 {@link ContextCompressionService#compressIfNeeded} 执行实际压缩。
 *
 * <p>压缩后的消息列表返回给 LangChain4j Agent，Agent 看到的是"摘要 + 最近 N 条消息"的视图。
 * 注意：压缩不会修改底层 ChatMemory 的存储（底层仍按 MessageWindow 淘汰），
 * 但通过 messages() 透明注入压缩视图，避免在 beforeToolExecution 等钩子中直接操作 ChatMemory。
 */
class CompressingChatMemory implements ChatMemory {

    private final Object memoryId;
    private final ChatMemory delegate;
    private final TokenCountEstimator tokenEstimator;
    private final ContextCompressionService compressionService;
    private final int maxTokens;

    CompressingChatMemory(Object memoryId, ChatMemory delegate,
                          TokenCountEstimator tokenEstimator,
                          ContextCompressionService compressionService,
                          int maxTokens) {
        this.memoryId = memoryId;
        this.delegate = delegate;
        this.tokenEstimator = tokenEstimator;
        this.compressionService = compressionService;
        this.maxTokens = maxTokens;
    }

    @Override
    public Object id() {
        return memoryId;
    }

    @Override
    public void add(ChatMessage message) {
        delegate.add(message);
    }

    @Override
    public List<ChatMessage> messages() {
        List<ChatMessage> current = new ArrayList<>(delegate.messages());
        if (current.size() < 8) return current;

        // 每次读取时触发压缩检查
        List<ChatMessage> compressed = compressionService.compressIfNeeded(
                String.valueOf(memoryId), current, maxTokens);

        // 如果压缩发生了且消息列表有变化（摘要替换了旧消息），返回压缩后列表
        if (compressed != current && compressed.size() < current.size()) {
            return compressed;
        }
        return current;
    }

    @Override
    public void clear() {
        delegate.clear();
    }
}
