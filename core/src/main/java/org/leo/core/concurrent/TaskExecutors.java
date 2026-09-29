package org.leo.core.concurrent;

import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Shared lifecycle and sizing policy for isolated, bounded application executors. */
public final class TaskExecutors {

    private TaskExecutors() {
    }

    public static ThreadPoolExecutor bounded(int threads, int queueCapacity, String prefix) {
        if (threads < 1 || queueCapacity < 1) {
            throw new IllegalArgumentException("executor sizing values must be positive");
        }
        return create(threads, new ArrayBlockingQueue<>(queueCapacity), prefix);
    }

    /** Rejects excess work immediately instead of leaving a live stream waiting in a queue. */
    public static ThreadPoolExecutor directHandoff(int threads, String prefix) {
        return create(threads, new SynchronousQueue<>(), prefix);
    }

    /** Interrupts running work and completes the cancellation of tasks that never started. */
    public static void shutdownNow(ExecutorService... executors) {
        for (ExecutorService executor : executors) {
            for (Runnable task : executor.shutdownNow()) {
                if (task instanceof Future<?> future) {
                    future.cancel(false);
                }
            }
        }
    }

    private static ThreadPoolExecutor create(int threads, BlockingQueue<Runnable> queue, String prefix) {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                threads, threads, 60L, TimeUnit.SECONDS, queue,
                daemonThreadFactory(prefix), new ThreadPoolExecutor.AbortPolicy());
        executor.allowCoreThreadTimeOut(true);
        return executor;
    }

    private static ThreadFactory daemonThreadFactory(String prefix) {
        Objects.requireNonNull(prefix, "thread name prefix");
        AtomicInteger sequence = new AtomicInteger();
        return task -> {
            Thread thread = new Thread(task, prefix + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }
}
