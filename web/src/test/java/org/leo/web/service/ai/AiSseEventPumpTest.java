package org.leo.web.service.ai;

import org.junit.jupiter.api.Test;
import org.leo.core.entity.AiSseEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiSseEventPumpTest {

    @Test
    void deliversQueuedEventsAndStopsCleanly() throws Exception {
        try (AiSseExecutor executor = new AiSseExecutor(1, 1, 1)) {
            AiSseEventPump pump = new AiSseEventPump(executor);
            LinkedBlockingQueue<AiSseEvent> queue = new LinkedBlockingQueue<>();
            List<AiSseEvent> eventLog = new ArrayList<>();
            CountDownLatch delivered = new CountDownLatch(1);
            AtomicReference<AiSseEvent> received = new AtomicReference<>();

            AiSseEventPump.Handle handle = pump.start(
                    "test",
                    queue,
                    eventLog,
                    () -> "running",
                    event -> {
                        received.set(event);
                        delivered.countDown();
                    },
                    heartbeat -> { });

            AiSseEvent event = new AiSseEvent("delta", "hello");
            queue.offer(event);
            assertTrue(delivered.await(1, TimeUnit.SECONDS));

            pump.stop(handle);

            assertEquals(event, received.get());
            assertEquals(List.of(event), eventLog);
            assertTrue(handle.future().isDone());
        }
    }
}
