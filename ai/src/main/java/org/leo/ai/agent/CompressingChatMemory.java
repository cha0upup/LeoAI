package org.leo.ai.agent;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.memory.ChatMemory;

import java.util.ArrayList;
import java.util.List;

/**
 * 带 checkpoint 压缩能力的 ChatMemory 包装器。
 *
 * <p>底层记忆保存原始消息，Agent 读取时看到“历史摘要 + checkpoint 后消息”。
 * 相同消息边界直接复用摘要；仅当摘要视图再次接近窗口上限时才继续压缩。
 */
class CompressingChatMemory implements ChatMemory {

    private final ChatMemory delegate;
    private final ContextCompressionService compressionService;
    private final int maxTokens;

    private CompressionCheckpoint checkpoint;
    private String failedSourceHash;

    CompressingChatMemory(ChatMemory delegate,
                          ContextCompressionService compressionService,
                          int maxTokens) {
        this.delegate = delegate;
        this.compressionService = compressionService;
        this.maxTokens = maxTokens;
        this.checkpoint = compressionService.restoreCheckpoint(
                String.valueOf(id()), new ArrayList<>(delegate.messages()));
    }

    @Override
    public Object id() {
        return delegate.id();
    }

    @Override
    public synchronized void add(ChatMessage message) {
        delegate.add(message);
        failedSourceHash = null;
        if (checkpoint != null) {
            checkpoint = checkpoint.afterAppend(new ArrayList<>(delegate.messages()), message);
        }
    }

    @Override
    public synchronized List<ChatMessage> messages() {
        List<ChatMessage> current = new ArrayList<>(delegate.messages());
        CompressionCheckpoint.ProjectedView projected = null;
        if (checkpoint != null) {
            projected = checkpoint.project(current);
            if (projected == null) checkpoint = null;
        }

        List<ChatMessage> candidate = projected != null ? projected.messages() : current;
        String sourceHash = CompressionCheckpoint.sourceHash(candidate);
        if (sourceHash.equals(failedSourceHash)) {
            return candidate;
        }

        ContextCompressionService.CompressionResult result = compressionService.compressIfNeeded(
                String.valueOf(id()), candidate, maxTokens);
        if (!result.attempted()) {
            return candidate;
        }
        if (!result.succeeded()) {
            failedSourceHash = sourceHash;
            return candidate;
        }

        int existingBoundary = projected != null ? projected.summarizedSourceCount() : 0;
        int compressedCandidateMessages = result.compressedMessageCount();
        int additionallySummarized = projected != null
                ? Math.max(0, compressedCandidateMessages - 1)
                : compressedCandidateMessages;
        int newBoundary = Math.min(current.size(),
                existingBoundary + additionallySummarized);
        checkpoint = CompressionCheckpoint.create(current, newBoundary, result.summaryMessage());
        failedSourceHash = null;
        compressionService.persistCheckpoint(
                String.valueOf(id()), result.summaryMessage(), current, newBoundary);

        return result.messages();
    }

    @Override
    public synchronized void set(Iterable<ChatMessage> messages) {
        delegate.set(messages);
        resetCheckpoint();
    }

    @Override
    public synchronized void clear() {
        delegate.clear();
        resetCheckpoint();
    }

    private void resetCheckpoint() {
        checkpoint = null;
        failedSourceHash = null;
        compressionService.clearPersistedCheckpoint(String.valueOf(id()));
    }
}
