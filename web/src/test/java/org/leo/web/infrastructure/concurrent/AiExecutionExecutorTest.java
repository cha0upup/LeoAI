package org.leo.web.infrastructure.concurrent;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiExecutionExecutorTest {

    @Test
    void boundsActiveTurnsWithoutUsingSubscriptionWorkers() throws Exception {
        try (AiExecutionExecutor executor = new AiExecutionExecutor(1, 1, 1)) {
            assertTrue(executor.tryAcquireTurn());
            assertFalse(executor.tryAcquireTurn());
            executor.submitSubscription(() -> { }).get(1, TimeUnit.SECONDS);
            executor.releaseTurn();
            assertTrue(executor.tryAcquireTurn());
            executor.releaseTurn();
        }
    }

    @Test
    void boundsChatConcurrencyAndQueue() throws Exception {
        try (AiExecutionExecutor executor = new AiExecutionExecutor(1, 1, 1)) {
            CountDownLatch started = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            executor.submitChat(() -> await(started, release));
            assertTrue(started.await(1, TimeUnit.SECONDS));

            executor.submitChat(() -> { });

            assertEquals(1, executor.activeChatTasks());
            assertEquals(1, executor.queuedChatTasks());
            assertThrows(RejectedExecutionException.class,
                    () -> executor.submitChat(() -> { }));
            release.countDown();
        }
    }

    @Test
    void subscriptionsUseDirectHandoffAndDaemonThreads() throws Exception {
        try (AiExecutionExecutor executor = new AiExecutionExecutor(1, 1, 1)) {
            CountDownLatch started = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            AtomicBoolean daemon = new AtomicBoolean(false);
            AtomicReference<String> threadName = new AtomicReference<>();

            executor.submitSubscription(() -> {
                daemon.set(Thread.currentThread().isDaemon());
                threadName.set(Thread.currentThread().getName());
                await(started, release);
            });
            assertTrue(started.await(1, TimeUnit.SECONDS));

            assertThrows(RejectedExecutionException.class,
                    () -> executor.submitSubscription(() -> { }));
            assertTrue(daemon.get());
            assertTrue(threadName.get().startsWith("ai-sse-subscription-"));
            release.countDown();
        }
    }

    @Test
    void subscriptionsDoNotConsumeChatExecutionCapacity()
            throws Exception {
        try (AiExecutionExecutor executor = new AiExecutionExecutor(1, 1, 1)) {
            CountDownLatch chatStarted = new CountDownLatch(1);
            CountDownLatch subscriptionStarted = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            executor.submitChat(() -> await(chatStarted, release));
            assertTrue(chatStarted.await(1, TimeUnit.SECONDS));

            executor.submitSubscription(() ->
                    await(subscriptionStarted, release));

            assertTrue(subscriptionStarted.await(1, TimeUnit.SECONDS));
            release.countDown();
        }
    }

    @Test
    void closeCancelsQueuedChatAndRejectsNewStreams() throws Exception {
        AiExecutionExecutor executor = new AiExecutionExecutor(1, 1, 1);
        try {
            CountDownLatch started = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            executor.submitChat(() -> await(started, release));
            assertTrue(started.await(5, TimeUnit.SECONDS));
            Future<?> queued = executor.submitChat(() -> { });

            executor.close();

            assertTrue(queued.isCancelled());
            assertFalse(executor.tryAcquireTurn());
            assertThrows(RejectedExecutionException.class, () -> executor.submitChat(() -> { }));
            assertThrows(RejectedExecutionException.class, () -> executor.submitSubscription(() -> { }));
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
