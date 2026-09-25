package org.leo.ai.concurrent;

import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Bounded execution domains for best-effort warmup and model capability probes. */
@Component
public final class AiBackgroundExecutor implements AutoCloseable {

    private static final int DEFAULT_WARMUP_THREADS = 4;
    private static final int DEFAULT_WARMUP_QUEUE = 128;
    private static final int DEFAULT_PROBE_THREADS = 4;
    private static final int DEFAULT_PROBE_QUEUE = 32;

    private final ThreadPoolExecutor warmupExecutor;
    private final ThreadPoolExecutor probeExecutor;

    public AiBackgroundExecutor() {
        this(DEFAULT_WARMUP_THREADS, DEFAULT_WARMUP_QUEUE,
                DEFAULT_PROBE_THREADS, DEFAULT_PROBE_QUEUE);
    }

    AiBackgroundExecutor(int warmupThreads, int warmupQueueCapacity,
                         int probeThreads, int probeQueueCapacity) {
        this.warmupExecutor = newExecutor(
                warmupThreads, warmupQueueCapacity, "ai-warmup-");
        this.probeExecutor = newExecutor(
                probeThreads, probeQueueCapacity, "ai-probe-");
    }

    public Future<?> submitWarmup(Runnable task) {
        return warmupExecutor.submit(task);
    }

    public <T> Future<T> submitProbe(Callable<T> task) {
        return probeExecutor.submit(task);
    }

    private static ThreadPoolExecutor newExecutor(int threads, int queueCapacity, String prefix) {
        if (threads < 1 || queueCapacity < 1) {
            throw new IllegalArgumentException("AI background executor sizing values must be positive");
        }
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                threads,
                threads,
                60L,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(queueCapacity),
                daemonThreadFactory(prefix),
                new ThreadPoolExecutor.AbortPolicy());
        executor.allowCoreThreadTimeOut(true);
        return executor;
    }

    private static ThreadFactory daemonThreadFactory(String prefix) {
        AtomicInteger sequence = new AtomicInteger();
        return task -> {
            Thread thread = new Thread(task, prefix + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    @Override
    @PreDestroy
    public void close() {
        warmupExecutor.shutdownNow();
        probeExecutor.shutdownNow();
    }
}
