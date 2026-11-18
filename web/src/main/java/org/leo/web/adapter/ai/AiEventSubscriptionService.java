package org.leo.web.adapter.ai;

import org.leo.ai.thread.AiConversationStoreService;
import org.leo.ai.thread.AiEventJournalRepository;
import org.leo.ai.thread.AiProtocolTurnRepository;
import org.leo.core.ai.AiEventStreamRuntime;
import org.leo.core.entity.AiSseEvent;
import org.leo.core.entity.AiThreadRecord;
import org.leo.core.entity.AiTurnRecord;
import org.leo.web.application.ai.AiThreadQueryService;
import org.leo.web.infrastructure.concurrent.AiExecutionExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

/**
 * 为运行中的 AI 线程提供可重放、可重新附着的 SSE 订阅。
 *
 * <p>订阅只读取运行时事件日志，不消费执行队列，也不持有或改变任务执行权。
 * 浏览器刷新、网络切换和多个视图同时观察同一线程时，各订阅拥有独立游标。
 * 同一线程的订阅在一个轮询周期内共享日志页与状态查询，不缓存发送结果或执行权。
 */
@Component
public class AiEventSubscriptionService {

    private static final Logger logger =
            LoggerFactory.getLogger(AiEventSubscriptionService.class);
    private static final int PAGE_SIZE = 200;
    private static final long POLL_MILLIS = 200L;
    private static final long HEARTBEAT_INTERVAL_MILLIS = 5_000L;
    private static final int MAX_CACHED_PAGES = 8;
    private static final long CACHE_NANOS = TimeUnit.MILLISECONDS.toNanos(POLL_MILLIS);

    private final AiExecutionExecutor executor;
    private final AiSseEmitterWriter writer;
    private final AiConversationStoreService conversations;
    private final AiEventJournalRepository eventJournal;
    private final AiProtocolTurnRepository turns;
    private final AiThreadQueryService threadQueries;
    private final LongSupplier nanoTime;
    private final ConcurrentHashMap<String, SharedJournal> journals = new ConcurrentHashMap<>();

    @Autowired
    public AiEventSubscriptionService(AiExecutionExecutor executor,
                                      AiSseEmitterWriter writer,
                                      AiConversationStoreService conversations,
                                      AiEventJournalRepository eventJournal,
                                      AiProtocolTurnRepository turns,
                                      AiThreadQueryService threadQueries) {
        this(executor, writer, conversations, eventJournal, turns, threadQueries, System::nanoTime);
    }

    AiEventSubscriptionService(AiExecutionExecutor executor,
                              AiSseEmitterWriter writer,
                              AiConversationStoreService conversations,
                              AiEventJournalRepository eventJournal,
                              AiProtocolTurnRepository turns,
                              AiThreadQueryService threadQueries,
                              LongSupplier nanoTime) {
        this.executor = executor;
        this.writer = writer;
        this.conversations = conversations;
        this.eventJournal = eventJournal;
        this.turns = turns;
        this.threadQueries = threadQueries;
        this.nanoTime = nanoTime;
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

        long afterSeq = threadQueries.replayCursor(threadId, runtime, requestedAfterSeq);
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
        SharedJournal journal = acquireJournal(threadId);
        try {
            while (!closed.get()) {
                List<AiSseEvent> events = journal.eventsAfter(cursor);
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
                    JournalState persisted = journal.state(executing);
                    long serverLastSeq = Math.max(
                            runtime.getLastSseEventSeq(),
                            persisted.lastSeq());
                    boolean protocolExecuting = persisted.protocolExecuting();
                    String status = persisted.status() != null
                            ? persisted.status() : runtime.getRunStatus();
                    if (!runtime.isExecuting() && !protocolExecuting && cursor >= serverLastSeq
                            && persisted.turnCompleted()) {
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
                if (!closed.get()) Thread.sleep(POLL_MILLIS);
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        } catch (Exception error) {
            closed.set(true);
            logger.debug("{} AI SSE subscriber disconnected: {}",
                    source != null ? source : "AI", error.getMessage());
        } finally {
            closed.set(true);
            releaseJournal(threadId, journal);
            AiSseEmitterWriter.safeComplete(emitter);
        }
    }

    private SharedJournal acquireJournal(String threadId) {
        return journals.compute(threadId, (id, current) -> {
            SharedJournal journal = current != null ? current : new SharedJournal(id);
            journal.subscribers++;
            return journal;
        });
    }

    private void releaseJournal(String threadId, SharedJournal journal) {
        journals.computeIfPresent(threadId, (id, current) -> {
            if (current != journal) return current;
            return --current.subscribers == 0 ? null : current;
        });
    }

    /** Short-lived, bounded read sharing only. Socket writes never hold this lock. */
    private final class SharedJournal {
        private final String threadId;
        private final LinkedHashMap<Long, EventPage> pages = new LinkedHashMap<>(16, 0.75f, true);
        private int subscribers;
        private JournalState state;
        private long stateReadAt;

        private SharedJournal(String threadId) {
            this.threadId = threadId;
        }

        synchronized List<AiSseEvent> eventsAfter(long cursor) {
            long now = nanoTime.getAsLong();
            pages.values().removeIf(page -> now - page.readAt() >= CACHE_NANOS);
            EventPage page = pages.get(cursor);
            if (page == null) {
                List<AiSseEvent> events = List.copyOf(
                        eventJournal.listAfter(threadId, cursor, PAGE_SIZE));
                page = new EventPage(nanoTime.getAsLong(), events);
                pages.put(cursor, page);
                if (pages.size() > MAX_CACHED_PAGES) {
                    pages.remove(pages.keySet().iterator().next());
                }
            }
            // A newer journal event can belong to a new queued Turn. Never use
            // an older terminal snapshot to close a subscriber that observed it.
            if (state != null && !page.events().isEmpty()
                    && page.events().get(page.events().size() - 1).seq() > state.lastSeq()) {
                state = null;
            }
            return page.events();
        }

        synchronized JournalState state(boolean executing) {
            long now = nanoTime.getAsLong();
            if (state != null && state.executing() == executing
                    && now - stateReadAt < CACHE_NANOS) {
                return state;
            }
            long lastSeq = eventJournal.lastSequence(threadId);
            List<AiTurnRecord> inProgress = turns.listInProgress(threadId);
            boolean protocolExecuting = !inProgress.isEmpty();
            String status = null;
            if (protocolExecuting) {
                status = inProgress.get(0).getDispatchStatus();
                if (status == null) status = "running";
            } else if (!executing) {
                AiThreadRecord thread = conversations.findThread(threadId);
                if (thread != null) status = thread.getRunStatus();
            }
            boolean completed = !executing && !protocolExecuting
                    && eventJournal.hasLatestTurnCompleted(threadId);
            state = new JournalState(executing, lastSeq, protocolExecuting, status, completed);
            stateReadAt = nanoTime.getAsLong();
            return state;
        }
    }

    private record EventPage(long readAt, List<AiSseEvent> events) { }

    private record JournalState(boolean executing, long lastSeq,
                                boolean protocolExecuting, String status,
                                boolean turnCompleted) { }

    private boolean isTerminal(String status) {
        return "completed".equals(status)
                || "failed".equals(status)
                || "cancelled".equals(status)
                || "waiting_for_user".equals(status);
    }
}
