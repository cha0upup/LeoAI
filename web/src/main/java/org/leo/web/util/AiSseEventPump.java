package org.leo.web.util;

import org.leo.core.entity.AiSseEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/** Runs the shared event/heartbeat loop used by platform and Node AI streams. */
@Component
public final class AiSseEventPump {

    private static final Logger logger = LoggerFactory.getLogger(AiSseEventPump.class);
    private static final long POLL_MILLIS = 200L;
    private static final long HEARTBEAT_INTERVAL_MILLIS = 5_000L;
    private static final long STOP_TIMEOUT_SECONDS = 1L;

    private final AiSseExecutor executor;

    public AiSseEventPump(AiSseExecutor executor) {
        this.executor = executor;
    }

    public Handle start(String source,
                        BlockingQueue<AiSseEvent> queue,
                        List<AiSseEvent> eventLog,
                        Supplier<String> statusSupplier,
                        EventSender eventSender,
                        HeartbeatSender heartbeatSender) {
        AtomicBoolean stopped = new AtomicBoolean(false);
        Future<?> future = executor.submitDrain(() -> runLoop(
                source, queue, eventLog, statusSupplier, eventSender, heartbeatSender, stopped));
        return new Handle(stopped, future);
    }

    public void stop(Handle handle) {
        if (handle == null) return;
        handle.stopped().set(true);
        try {
            handle.future().get(STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            handle.future().cancel(true);
        } catch (TimeoutException e) {
            handle.future().cancel(true);
        } catch (Exception ignored) {
            // The caller still performs a final synchronous queue flush.
        }
    }

    private void runLoop(String source,
                         BlockingQueue<AiSseEvent> queue,
                         List<AiSseEvent> eventLog,
                         Supplier<String> statusSupplier,
                         EventSender eventSender,
                         HeartbeatSender heartbeatSender,
                         AtomicBoolean stopped) {
        long lastSentAt = System.currentTimeMillis();
        try {
            while (!stopped.get() || !queue.isEmpty()) {
                AiSseEvent event = queue.poll(POLL_MILLIS, TimeUnit.MILLISECONDS);
                if (event != null) {
                    eventLog.add(event);
                    eventSender.send(event);
                    lastSentAt = System.currentTimeMillis();
                    continue;
                }
                if (!stopped.get()
                        && System.currentTimeMillis() - lastSentAt >= HEARTBEAT_INTERVAL_MILLIS) {
                    Map<String, Object> heartbeat = new LinkedHashMap<>();
                    heartbeat.put("ts", System.currentTimeMillis());
                    heartbeat.put("status", statusSupplier.get());
                    heartbeatSender.send(heartbeat);
                    lastSentAt = System.currentTimeMillis();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            stopped.set(true);
            logger.debug("{} SSE event pump stopped: {}", source, e.getMessage());
        }
    }

    @FunctionalInterface
    public interface EventSender {
        void send(AiSseEvent event) throws Exception;
    }

    @FunctionalInterface
    public interface HeartbeatSender {
        void send(Map<String, Object> heartbeat) throws Exception;
    }

    public record Handle(AtomicBoolean stopped, Future<?> future) {
    }
}
