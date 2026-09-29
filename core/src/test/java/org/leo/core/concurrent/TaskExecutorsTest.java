package org.leo.core.concurrent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class TaskExecutorsTest {

    @Test
    void boundedExecutorRejectsOverflowAndNamesDaemonWorkers() throws Exception {
        ThreadPoolExecutor executor = TaskExecutors.bounded(1, 1, "test-worker-");
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            Future<Thread> running = executor.submit(() -> {
                started.countDown();
                release.await();
                return Thread.currentThread();
            });
            assertTrue(started.await(5, TimeUnit.SECONDS));
            executor.submit(() -> { });
            assertThrows(RejectedExecutionException.class, () -> executor.submit(() -> { }));
            release.countDown();
            Thread worker = running.get(5, TimeUnit.SECONDS);
            assertTrue(worker.isDaemon());
            assertEquals("test-worker-1", worker.getName());
            assertTrue(executor.allowsCoreThreadTimeOut());
            assertEquals(60L, executor.getKeepAliveTime(TimeUnit.SECONDS));
        } finally {
            release.countDown();
            TaskExecutors.shutdownNow(executor);
        }
    }

    @Test
    void directHandoffDoesNotQueueLiveStreams() throws Exception {
        ThreadPoolExecutor executor = TaskExecutors.directHandoff(1, "test-stream-");
        CountDownLatch started = new CountDownLatch(1);
        try {
            executor.submit(() -> {
                started.countDown();
                new CountDownLatch(1).await();
                return null;
            });
            assertTrue(started.await(5, TimeUnit.SECONDS));
            assertThrows(RejectedExecutionException.class, () -> executor.submit(() -> { }));
            assertTrue(executor.getQueue().isEmpty());
        } finally {
            TaskExecutors.shutdownNow(executor);
        }
    }

    @Test
    void shutdownCancelsQueuedFuturesAndInterruptsRunningWork() throws Exception {
        ThreadPoolExecutor executor = TaskExecutors.bounded(1, 3, "test-shutdown-");
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        try {
            executor.submit(() -> {
                started.countDown();
                try {
                    new CountDownLatch(1).await();
                } catch (InterruptedException e) {
                    interrupted.countDown();
                    Thread.currentThread().interrupt();
                }
            });
            assertTrue(started.await(5, TimeUnit.SECONDS));
            Future<?> queuedRunnable = executor.submit(() -> fail("Queued task must not run"));
            Future<Integer> queuedCallable = executor.submit(() -> 42);
            // execute() also accepts plain Runnables; cleanup must not assume every task is a Future.
            executor.execute(() -> fail("Queued runnable must not run"));
            ThreadPoolExecutor other = TaskExecutors.bounded(1, 1, "test-other-");
            try {
                TaskExecutors.shutdownNow(executor, other);
                assertTrue(other.isShutdown());
            } finally {
                TaskExecutors.shutdownNow(other);
            }
            assertTrue(queuedRunnable.isCancelled());
            assertTrue(queuedCallable.isCancelled());
            assertThrows(CancellationException.class, () -> queuedCallable.get(1, TimeUnit.SECONDS));
            assertTrue(interrupted.await(5, TimeUnit.SECONDS));
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
            assertThrows(RejectedExecutionException.class, () -> executor.submit(() -> { }));
            assertDoesNotThrow(() -> TaskExecutors.shutdownNow(executor));
        } finally {
            TaskExecutors.shutdownNow(executor);
        }
    }

    @ParameterizedTest
    @CsvSource({"0, 1", "-1, 1", "1, 0", "1, -1"})
    void rejectsInvalidSizes(int threads, int queueCapacity) {
        assertThrows(IllegalArgumentException.class,
                () -> TaskExecutors.bounded(threads, queueCapacity, "test-"));
    }
}
