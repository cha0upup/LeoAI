package org.leo.web.util;

import jakarta.annotation.PreDestroy;
import org.leo.core.concurrent.TaskExecutors;
import org.springframework.stereotype.Component;

import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Shared, bounded execution domains for AI chat work and SSE event delivery.
 *
 * <p>Chat work may wait briefly in a bounded queue. Event pumps use direct hand-off because a
 * queued pump would leave an accepted SSE connection without live events or heartbeats.
 */
@Component
public final class AiSseExecutor implements AutoCloseable {

    private static final int CHAT_QUEUE_CAPACITY = 128;
    private static final int MIN_CHAT_THREADS = 4;
    private static final int MAX_CHAT_THREADS = 16;
    private static final int MIN_DRAIN_THREADS = 8;
    private static final int MAX_DRAIN_THREADS = 32;

    private final ThreadPoolExecutor chatExecutor;
    private final ThreadPoolExecutor drainExecutor;
    private final ThreadPoolExecutor subscriptionExecutor;

    public AiSseExecutor() {
        this(defaultChatThreads(), CHAT_QUEUE_CAPACITY, defaultDrainThreads());
    }

    AiSseExecutor(int chatThreads, int chatQueueCapacity, int drainThreads) {
        if (chatThreads < 1 || chatQueueCapacity < 1 || drainThreads < 1) {
            throw new IllegalArgumentException("AI SSE executor sizing values must be positive");
        }
        this.chatExecutor = TaskExecutors.bounded(chatThreads, chatQueueCapacity, "ai-chat-");
        this.drainExecutor = TaskExecutors.directHandoff(drainThreads, "ai-sse-drain-");

        // 重连订阅与 Turn 执行事件泵隔离，避免大量刷新连接占满 drain 域，
        // 进而阻塞新的 AI Turn 建立首条实时流。
        this.subscriptionExecutor = TaskExecutors.directHandoff(drainThreads, "ai-sse-subscription-");
    }

    public Future<?> submitChat(Runnable task) throws RejectedExecutionException {
        return chatExecutor.submit(task);
    }

    public Future<?> submitDrain(Runnable task) throws RejectedExecutionException {
        return drainExecutor.submit(task);
    }

    public Future<?> submitSubscription(Runnable task)
            throws RejectedExecutionException {
        return subscriptionExecutor.submit(task);
    }

    int activeChatTasks() {
        return chatExecutor.getActiveCount();
    }

    int queuedChatTasks() {
        return chatExecutor.getQueue().size();
    }

    int activeDrainTasks() {
        return drainExecutor.getActiveCount();
    }

    private static int defaultChatThreads() {
        return clamp(Runtime.getRuntime().availableProcessors() * 2,
                MIN_CHAT_THREADS, MAX_CHAT_THREADS);
    }

    private static int defaultDrainThreads() {
        return clamp(Runtime.getRuntime().availableProcessors() * 4,
                MIN_DRAIN_THREADS, MAX_DRAIN_THREADS);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    @Override
    @PreDestroy
    public void close() {
        TaskExecutors.shutdownNow(chatExecutor, drainExecutor, subscriptionExecutor);
    }
}
