package org.leo.web.infrastructure.concurrent;

import jakarta.annotation.PreDestroy;
import org.leo.core.concurrent.TaskExecutors;
import org.springframework.stereotype.Component;

import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Separate, bounded execution domains for AI chat work and SSE subscriptions.
 *
 * <p>Chat work may wait briefly in a bounded queue. Turn slots span asynchronous model work
 * without occupying a worker. Subscriptions use direct hand-off for events and heartbeats.
 */
@Component
public final class AiExecutionExecutor implements AutoCloseable {

    private static final int CHAT_QUEUE_CAPACITY = 128;
    private static final int MIN_CHAT_THREADS = 4;
    private static final int MAX_CHAT_THREADS = 16;
    private static final int MIN_STREAM_CAPACITY = 8;
    private static final int MAX_STREAM_CAPACITY = 32;

    private final ThreadPoolExecutor chatExecutor;
    private final ThreadPoolExecutor subscriptionExecutor;
    private final Semaphore turnSlots;

    public AiExecutionExecutor() {
        this(defaultChatThreads(), CHAT_QUEUE_CAPACITY, defaultStreamCapacity());
    }

    AiExecutionExecutor(int chatThreads, int chatQueueCapacity, int streamCapacity) {
        if (chatThreads < 1 || chatQueueCapacity < 1 || streamCapacity < 1) {
            throw new IllegalArgumentException("AI executor sizing values must be positive");
        }
        this.chatExecutor = TaskExecutors.bounded(chatThreads, chatQueueCapacity, "ai-chat-");
        this.subscriptionExecutor = TaskExecutors.directHandoff(streamCapacity, "ai-sse-subscription-");
        this.turnSlots = new Semaphore(streamCapacity);
    }

    public Future<?> submitChat(Runnable task) throws RejectedExecutionException {
        return chatExecutor.submit(task);
    }

    public Future<?> submitSubscription(Runnable task)
            throws RejectedExecutionException {
        return subscriptionExecutor.submit(task);
    }

    public boolean tryAcquireTurn() {
        return !chatExecutor.isShutdown() && turnSlots.tryAcquire();
    }

    public void releaseTurn() {
        turnSlots.release();
    }

    private static int defaultChatThreads() {
        return clamp(Runtime.getRuntime().availableProcessors() * 2,
                MIN_CHAT_THREADS, MAX_CHAT_THREADS);
    }

    private static int defaultStreamCapacity() {
        return clamp(Runtime.getRuntime().availableProcessors() * 4,
                MIN_STREAM_CAPACITY, MAX_STREAM_CAPACITY);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    @Override
    @PreDestroy
    public void close() {
        TaskExecutors.shutdownNow(chatExecutor, subscriptionExecutor);
    }
}
