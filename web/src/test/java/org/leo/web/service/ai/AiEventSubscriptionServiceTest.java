package org.leo.web.service.ai;

import org.junit.jupiter.api.Test;
import org.leo.ai.thread.AiConversationStoreService;
import org.leo.core.entity.AiSseEvent;
import org.leo.core.entity.AiThreadRecord;
import org.leo.core.ai.AiRunStatus;
import org.leo.core.session.AiThread;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiEventSubscriptionServiceTest {
    private final AiSseExecutor executor = mock(AiSseExecutor.class);
    private final AiSseEmitterWriter writer = mock(AiSseEmitterWriter.class);
    private final AiConversationStoreService eventStore = mock(AiConversationStoreService.class);
    private final AiEventSubscriptionService service = new AiEventSubscriptionService(executor, writer, eventStore);

    @Test
    void replaysOnlyEventsAfterSubscriberCursorAndCompletesAtTerminalState()
            throws Exception {
        AiThread thread = new AiThread("thread-1", "test");
        AiSseEvent first = thread.recordSseEvent("delta", "old");
        AiSseEvent second = thread.recordSseEvent("turn", "new");
        thread.markCompleted();
        SseEmitter emitter = mock(SseEmitter.class);
        when(eventStore.listEventsAfter("thread-1", first.seq(), 200))
                .thenReturn(List.of(second));
        when(eventStore.findLastEventSeq("thread-1")).thenReturn(second.seq());
        when(eventStore.findThread("thread-1"))
                .thenReturn(threadRecord(AiRunStatus.COMPLETED));
        when(eventStore.hasLatestTurnCompletedEvent("thread-1")).thenReturn(true);

        service.stream("test", "thread-1", thread, emitter,
                first.seq(), new AtomicBoolean(false));

        verify(writer, never()).sendEvent(emitter, first);
        verify(writer).sendEvent(emitter, second);
        verify(writer).sendStatus(emitter, AiRunStatus.COMPLETED);
        verify(emitter).complete();
    }

    @Test
    void subscriberDisconnectDoesNotCancelTheRunningTask() throws Exception {
        AiThread thread = new AiThread("thread-2", "test");
        assertTrue(thread.claimExecution());
        AiSseEvent live = thread.recordSseEvent("delta", "live");
        SseEmitter emitter = mock(SseEmitter.class);
        when(eventStore.listEventsAfter("thread-2", 0L, 200))
                .thenReturn(List.of(live));
        doThrow(new IOException("client disconnected"))
                .when(writer).sendEvent(eq(emitter), any(AiSseEvent.class));

        service.stream("test", "thread-2", thread, emitter,
                0L, new AtomicBoolean(false));

        assertTrue(thread.isExecuting());
        assertEquals(AiRunStatus.RUNNING, thread.getRunStatus());
        verify(emitter).complete();
    }

    @Test
    void freshSubscriberStartsAtTheCurrentRunBoundary() throws Exception {
        AiThread thread = new AiThread("thread-3", "test");
        AiSseEvent previousTurn = thread.recordSseEvent("turn", "previous");
        assertTrue(thread.claimExecution());
        AiSseEvent currentTurn = thread.recordSseEvent("turn", "current");
        thread.markCompleted();
        thread.clearExecuting();
        SseEmitter emitter = mock(SseEmitter.class);
        when(eventStore.listEventsAfter(
                "thread-3", thread.getCurrentRunStartSeq(), 200))
                .thenReturn(List.of(currentTurn));
        when(eventStore.findLastEventSeq("thread-3")).thenReturn(currentTurn.seq());
        when(eventStore.findThread("thread-3"))
                .thenReturn(threadRecord(AiRunStatus.COMPLETED));
        when(eventStore.hasLatestTurnCompletedEvent("thread-3")).thenReturn(true);

        service.stream("test", "thread-3", thread, emitter,
                thread.getCurrentRunStartSeq(), new AtomicBoolean(false));

        verify(writer, never()).sendEvent(emitter, previousTurn);
        verify(writer).sendEvent(emitter, currentTurn);
    }

    @Test
    void waitsForProtocolCompletionEventBeforeClosingTerminalStream() throws Exception {
        AiThread thread = new AiThread("thread-4", "test");
        thread.bindActiveTurnId("turn-4");
        thread.markCompleted();
        SseEmitter emitter = mock(SseEmitter.class);
        List<AiSseEvent> persisted = new CopyOnWriteArrayList<>();
        when(eventStore.listEventsAfter(eq("thread-4"), anyLong(), eq(200)))
                .thenAnswer(invocation -> persisted.stream()
                        .filter(event -> event.seq() > (long) invocation.getArgument(1))
                        .toList());
        when(eventStore.findLastEventSeq("thread-4"))
                .thenAnswer(invocation -> thread.getLastSseEventSeq());
        when(eventStore.findThread("thread-4"))
                .thenReturn(threadRecord(AiRunStatus.COMPLETED));
        when(eventStore.hasLatestTurnCompletedEvent("thread-4"))
                .thenAnswer(invocation -> persisted.stream().anyMatch(
                        event -> "turn/completed".equals(event.name())));

        CompletableFuture<Void> stream = CompletableFuture.runAsync(() ->
                service.stream("test", "thread-4", thread, emitter,
                        0L, new AtomicBoolean(false)));
        Thread.sleep(250L);
        verify(writer, never()).sendStatus(emitter, AiRunStatus.COMPLETED);

        persisted.add(thread.recordSseEvent(
                "turn/completed",
                Map.of("turn", Map.of("id", "turn-4", "status", "completed"))));
        stream.get(2, TimeUnit.SECONDS);

        verify(writer).sendStatus(emitter, AiRunStatus.COMPLETED);
        verify(emitter).complete();
    }

    @Test
    void closesAfterQueuedTurnCancellationEvenWhenRuntimeStayedIdle()
            throws Exception {
        AiThread thread = new AiThread("thread-queued", "test");
        AiSseEvent completed = new AiSseEvent(
                1L, 1L, "turn/completed",
                Map.of("turn", Map.of(
                        "id", "turn-queued",
                        "status", "interrupted")),
                null, "turn-queued", "assistant-queued", null);
        SseEmitter emitter = mock(SseEmitter.class);
        when(eventStore.listEventsAfter("thread-queued", 0L, 200))
                .thenReturn(List.of(completed));
        when(eventStore.findLastEventSeq("thread-queued"))
                .thenReturn(1L);
        when(eventStore.findThread("thread-queued"))
                .thenReturn(threadRecord(AiRunStatus.IDLE));
        when(eventStore.hasLatestTurnCompletedEvent("thread-queued"))
                .thenReturn(true);

        service.stream("test", "thread-queued", thread, emitter,
                0L, new AtomicBoolean(false));

        verify(writer).sendEvent(emitter, completed);
        verify(writer, never()).sendStatus(emitter, AiRunStatus.IDLE);
        verify(emitter).complete();
    }

    private static AiThreadRecord threadRecord(String status) {
        AiThreadRecord row = new AiThreadRecord();
        row.setRunStatus(status);
        return row;
    }
}
