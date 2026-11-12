package org.leo.web.service.ai;

import org.leo.ai.thread.AiConversationStoreService;
import org.leo.core.ai.AiEventStreamRuntime;
import org.leo.core.entity.AiSseEvent;
import org.leo.core.entity.AiThreadRecord;
import org.leo.core.entity.AiTurnRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 为运行中的 AI 线程提供可重放、可重新附着的 SSE 订阅。
 *
 * <p>订阅只读取运行时事件日志，不消费执行队列，也不持有或改变任务执行权。
 * 浏览器刷新、网络切换和多个视图同时观察同一线程时，各订阅拥有独立游标。
 */
@Component
public class AiEventSubscriptionService {

    private static final Logger logger =
            LoggerFactory.getLogger(AiEventSubscriptionService.class);
    private static final int PAGE_SIZE = 200;
    private static final long POLL_MILLIS = 200L;
    private static final long HEARTBEAT_INTERVAL_MILLIS = 5_000L;

    private final AiSseExecutor executor;
    private final AiSseEmitterWriter writer;
    private final AiConversationStoreService eventStore;

    public AiEventSubscriptionService(AiSseExecutor executor,
                                      AiSseEmitterWriter writer,
                                      AiConversationStoreService eventStore) {
        this.executor = executor;
        this.writer = writer;
        this.eventStore = eventStore;
    }

    public SseEmitter subscribe(String source,
                                String threadId,
                                AiEventStreamRuntime runtime,
                                Long requestedAfterSeq) {
        Objects.requireNonNull(runtime, "runtime");
        Objects.requireNonNull(threadId, "threadId");
        SseEmitter emitter = new SseEmitter(0L);
        AtomicBoolean closed = new AtomicBoolean(false);
        emitter.onCompletion(() -> closed.set(true));
        emitter.onTimeout(() -> closed.set(true));
        emitter.onError(error -> closed.set(true));

        long requestedCursor = requestedAfterSeq != null
                ? Math.max(0L, requestedAfterSeq) : 0L;
        long afterSeq = requestedCursor > 0L
                ? requestedCursor
                : Math.max(runtime.getCurrentRunStartSeq(),
                        eventStore.findLatestTurnStartSeq(threadId));
        try {
            executor.submitSubscription(() ->
                    stream(source, threadId, runtime, emitter, afterSeq, closed));
        } catch (RejectedExecutionException error) {
            closed.set(true);
            AiSseEmitterWriter.safeSendError(emitter, "AI 实时订阅繁忙，请稍后重试");
        }
        return emitter;
    }

    void stream(String source,
                String threadId,
                AiEventStreamRuntime runtime,
                SseEmitter emitter,
                long afterSeq,
                AtomicBoolean closed) {
        long cursor = Math.max(0L, afterSeq);
        long lastHeartbeatAt = 0L;
        try {
            while (!closed.get()) {
                List<AiSseEvent> events =
                        eventStore.listEventsAfter(threadId, cursor, PAGE_SIZE);
                for (AiSseEvent event : events) {
                    if (closed.get()) return;
                    writer.sendEvent(emitter, event);
                    cursor = Math.max(cursor, event.seq());
                    lastHeartbeatAt = System.currentTimeMillis();
                }
                // 满页时先追上事件日志，避免每页都查询状态并等待一次轮询。
                if (events.size() == PAGE_SIZE) continue;

                long now = System.currentTimeMillis();
                boolean executing = runtime.isExecuting();
                boolean heartbeatDue = now - lastHeartbeatAt >= HEARTBEAT_INTERVAL_MILLIS;
                // 执行期间只追增量事件；心跳和终态检查时再读取持久化状态。
                if (!executing || heartbeatDue) {
                    long serverLastSeq = Math.max(
                            runtime.getLastSseEventSeq(),
                            eventStore.findLastEventSeq(threadId));
                    List<AiTurnRecord> inProgressTurns =
                            eventStore.listInProgressProtocolTurns(threadId);
                    boolean protocolExecuting = !inProgressTurns.isEmpty();
                    String status = runtime.getRunStatus();
                    if (protocolExecuting) {
                        String dispatchStatus = inProgressTurns.get(0).getDispatchStatus();
                        status = dispatchStatus != null ? dispatchStatus : "running";
                    } else if (!executing) {
                        AiThreadRecord persistedThread = eventStore.findThread(threadId);
                        if (persistedThread != null) status = persistedThread.getRunStatus();
                    }
                    if (!runtime.isExecuting() && !protocolExecuting && cursor >= serverLastSeq
                            && eventStore.hasLatestTurnCompletedEvent(threadId)) {
                        if (isTerminal(status)) {
                            writer.sendStatus(emitter, status);
                        }
                        return;
                    }
                    if (heartbeatDue) {
                        Map<String, Object> heartbeat = new LinkedHashMap<>();
                        heartbeat.put("ts", now);
                        heartbeat.put("status", status);
                        heartbeat.put("lastSeq", serverLastSeq);
                        heartbeat.put("executing", runtime.isExecuting() || protocolExecuting);
                        writer.sendHeartbeat(emitter, heartbeat);
                        lastHeartbeatAt = now;
                    }
                }
                Thread.sleep(POLL_MILLIS);
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        } catch (Exception error) {
            closed.set(true);
            logger.debug("{} AI SSE subscriber disconnected: {}",
                    source != null ? source : "AI", error.getMessage());
        } finally {
            closed.set(true);
            AiSseEmitterWriter.safeComplete(emitter);
        }
    }

    private boolean isTerminal(String status) {
        return "completed".equals(status)
                || "failed".equals(status)
                || "cancelled".equals(status)
                || "waiting_for_user".equals(status);
    }
}
