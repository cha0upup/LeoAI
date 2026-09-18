package org.leo.core.ai;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AiRuntimeCancellationTest {

    @Test
    void notifiesChildrenOnceWithoutReplacingModelCancellation() {
        AiRuntimeState runtime = new AiRuntimeState();
        AtomicInteger child = new AtomicInteger();
        AtomicInteger detached = new AtomicInteger();
        AtomicInteger model = new AtomicInteger();
        runtime.onStop(reason -> child.incrementAndGet());
        runtime.onStop(reason -> detached.incrementAndGet()).close();
        runtime.setStopCallback(model::incrementAndGet);

        runtime.stop("用户取消");
        runtime.stop("再次取消");

        assertEquals(1, child.get());
        assertEquals(0, detached.get());
        assertEquals(2, model.get());
    }

    @Test
    void lateRegistrationStillReceivesCancellationAfterRuntimeRelease() {
        AiRuntimeState runtime = new AiRuntimeState();
        runtime.claimExecution();
        runtime.stop("停止");
        runtime.clearExecuting();
        AtomicInteger calls = new AtomicInteger();

        runtime.onStop(reason -> calls.incrementAndGet());

        assertEquals(1, calls.get());
    }

    @Test
    void completingCancellationWhileNotifyingChildrenDoesNotDropOtherListeners() {
        AiRuntimeState runtime = new AiRuntimeState();
        AtomicInteger calls = new AtomicInteger();
        for (int i = 0; i < 3; i++) {
            runtime.onStop(reason -> {
                calls.incrementAndGet();
                runtime.clearExecuting();
            });
        }

        runtime.stop("停止所有子任务");

        assertEquals(3, calls.get());
    }

    @Test
    void concurrentRegistrationAndStopNeverLoseOrDuplicateCancellation() throws Exception {
        var executor = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < 100; i++) {
                AiRuntimeState runtime = new AiRuntimeState();
                AtomicInteger calls = new AtomicInteger();
                CountDownLatch start = new CountDownLatch(1);
                var register = executor.submit(() -> {
                    start.await();
                    return runtime.onStop(reason -> calls.incrementAndGet());
                });
                var stop = executor.submit(() -> {
                    start.await();
                    runtime.stop("停止");
                    return null;
                });
                start.countDown();
                var registration = register.get(5, TimeUnit.SECONDS);
                stop.get(5, TimeUnit.SECONDS);
                registration.close();
                assertEquals(1, calls.get());
            }
        } finally {
            executor.shutdownNow();
        }
    }
}
