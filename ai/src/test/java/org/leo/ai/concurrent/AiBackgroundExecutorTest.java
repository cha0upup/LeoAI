package org.leo.ai.concurrent;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiBackgroundExecutorTest {

    @Test
    void boundsWarmupConcurrencyAndQueue() throws Exception {
        try (AiBackgroundExecutor executor = new AiBackgroundExecutor(1, 1, 1, 1)) {
            CountDownLatch started = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            executor.submitWarmup(() -> await(started, release));
            assertTrue(started.await(1, TimeUnit.SECONDS));

            executor.submitWarmup(() -> { });

            assertThrows(RejectedExecutionException.class,
                    () -> executor.submitWarmup(() -> { }));
            release.countDown();
        }
    }

    @Test
    void boundsProbeQueueAndUsesNamedDaemonThreads() throws Exception {
        try (AiBackgroundExecutor executor = new AiBackgroundExecutor(1, 1, 1, 1)) {
            CountDownLatch started = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            AtomicBoolean daemon = new AtomicBoolean(false);
            AtomicReference<String> threadName = new AtomicReference<>();

            executor.submitProbe(() -> {
                daemon.set(Thread.currentThread().isDaemon());
                threadName.set(Thread.currentThread().getName());
                await(started, release);
                return true;
            });
            assertTrue(started.await(1, TimeUnit.SECONDS));
            executor.submitProbe(() -> true);

            assertThrows(RejectedExecutionException.class,
                    () -> executor.submitProbe(() -> true));
            assertTrue(daemon.get());
            assertTrue(threadName.get().startsWith("ai-probe-"));
            release.countDown();
        }
    }

    @Test
    void closeCancelsQueuedWarmupAndProbeWork() throws Exception {
        AiBackgroundExecutor executor = new AiBackgroundExecutor(1, 1, 1, 1);
        try {
            CountDownLatch started = new CountDownLatch(2);
            CountDownLatch release = new CountDownLatch(1);
            executor.submitWarmup(() -> await(started, release));
            executor.submitProbe(() -> {
                await(started, release);
                return true;
            });
            assertTrue(started.await(5, TimeUnit.SECONDS));
            Future<?> warmup = executor.submitWarmup(() -> { });
            Future<Boolean> probe = executor.submitProbe(() -> true);

            executor.close();

            assertTrue(warmup.isCancelled());
            assertTrue(probe.isCancelled());
        } finally {
            executor.close();
        }
    }

    private static void await(CountDownLatch started, CountDownLatch release) {
        started.countDown();
        try {
            release.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
