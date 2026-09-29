package org.leo.ai.concurrent;

import jakarta.annotation.PreDestroy;
import org.leo.core.concurrent.TaskExecutors;
import org.springframework.stereotype.Component;

import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;

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
        this.warmupExecutor = TaskExecutors.bounded(
                warmupThreads, warmupQueueCapacity, "ai-warmup-");
        this.probeExecutor = TaskExecutors.bounded(
                probeThreads, probeQueueCapacity, "ai-probe-");
    }

    public Future<?> submitWarmup(Runnable task) {
        return warmupExecutor.submit(task);
    }

    public <T> Future<T> submitProbe(Callable<T> task) {
        return probeExecutor.submit(task);
    }

    @Override
    @PreDestroy
    public void close() {
        TaskExecutors.shutdownNow(warmupExecutor, probeExecutor);
    }
}
