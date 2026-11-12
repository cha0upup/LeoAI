package org.leo.web.service.ai;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.leo.ai.thread.AiConversationStoreService;
import org.leo.core.ai.AiRunStatus;
import org.leo.core.entity.AiSseEvent;
import org.leo.core.entity.AiThreadRecord;
import org.leo.core.entity.AiTurnRecord;
import org.leo.core.session.AiThread;
import org.mockito.ArgumentCaptor;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Timeout(5)
class AiEventSubscriptionServiceTest {
    private final AiSseExecutor executor = mock(AiSseExecutor.class);
    private final AiSseEmitterWriter writer = mock(AiSseEmitterWriter.class);
    private final AiConversationStoreService eventStore = mock(AiConversationStoreService.class);
    private final AiEventSubscriptionService service = new AiEventSubscriptionService(executor, writer, eventStore);

    @ParameterizedTest
    @CsvSource({"1,false", "200,false", "201,false", "401,false", "2,true"})
    void replaysInOrderWithoutRepeatedStateQueries(int eventCount, boolean running)
            throws Exception {
        AiThread thread = new AiThread("thread-1", "test");
        AiSseEvent first = thread.recordSseEvent("delta", "old");
        if (running) assertTrue(thread.claimExecution());
        List<AiSseEvent> events = IntStream.range(0, eventCount)
                .mapToObj(index -> thread.recordSseEvent("delta", "new-" + index)).toList();
        SseEmitter emitter = mock(SseEmitter.class);
        if (running) {
            doAnswer(invocation -> {
                thread.markCompleted();
                thread.clearExecuting();
                return null;
            }).when(writer).sendEvent(emitter, events.get(eventCount - 1));
        } else {
            thread.markCompleted();
        }
        when(eventStore.listEventsAfter(eq("thread-1"), anyLong(), eq(200)))
                .thenAnswer(invocation -> events.stream()
                        .filter(event -> event.seq() > (long) invocation.getArgument(1))
                        .limit(running ? 1 : 200).toList());
        when(eventStore.findLastEventSeq("thread-1")).thenReturn(thread.getLastSseEventSeq());
        when(eventStore.findThread("thread-1"))
                .thenReturn(threadRecord(AiRunStatus.COMPLETED));
        when(eventStore.hasLatestTurnCompletedEvent("thread-1")).thenReturn(true);

        service.stream("test", "thread-1", thread, emitter,
                first.seq(), new AtomicBoolean(false));

        ArgumentCaptor<AiSseEvent> sent = ArgumentCaptor.forClass(AiSseEvent.class);
        verify(writer, times(eventCount)).sendEvent(eq(emitter), sent.capture());
        assertEquals(events, sent.getAllValues());
        verify(eventStore).findLastEventSeq("thread-1");
        verify(eventStore).findThread("thread-1");
        verify(eventStore).listInProgressProtocolTurns("thread-1");
        verify(eventStore).hasLatestTurnCompletedEvent("thread-1");
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
        AiSseEvent completed = new AiSseEvent(1L, 1L,
                "turn/completed", Map.of("turn", Map.of("id", "turn-4", "status", "completed")),
                null, "turn-4", null, null);
        SseEmitter emitter = mock(SseEmitter.class);
        when(eventStore.listEventsAfter("thread-4", 0L, 200))
                .thenReturn(List.of(), List.of(completed));
        when(eventStore.findLastEventSeq("thread-4"))
                .thenReturn(0L, completed.seq());
        when(eventStore.findThread("thread-4"))
                .thenReturn(threadRecord(AiRunStatus.COMPLETED));
        when(eventStore.hasLatestTurnCompletedEvent("thread-4"))
                .thenReturn(false, true);

        service.stream("test", "thread-4", thread, emitter,
                0L, new AtomicBoolean(false));

        var order = inOrder(eventStore, writer);
        order.verify(eventStore).hasLatestTurnCompletedEvent("thread-4");
        order.verify(writer).sendEvent(emitter, completed);
        order.verify(writer).sendStatus(emitter, AiRunStatus.COMPLETED);
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
        AiTurnRecord queued = new AiTurnRecord();
        queued.setDispatchStatus("queued");
        when(eventStore.listEventsAfter("thread-queued", 0L, 200))
                .thenReturn(List.of(), List.of(completed));
        when(eventStore.findLastEventSeq("thread-queued"))
                .thenReturn(0L, 1L);
        when(eventStore.listInProgressProtocolTurns("thread-queued"))
                .thenReturn(List.of(queued), List.of());
        when(eventStore.findThread("thread-queued"))
                .thenReturn(threadRecord(AiRunStatus.IDLE));
        when(eventStore.hasLatestTurnCompletedEvent("thread-queued"))
                .thenReturn(true);

        service.stream("test", "thread-queued", thread, emitter,
                0L, new AtomicBoolean(false));

        verify(writer).sendEvent(emitter, completed);
        verify(writer).sendHeartbeat(eq(emitter), argThat(heartbeat ->
                "queued".equals(heartbeat.get("status")) && Boolean.TRUE.equals(heartbeat.get("executing"))));
        verify(eventStore).findThread("thread-queued");
        verify(eventStore).hasLatestTurnCompletedEvent("thread-queued");
        verify(writer, never()).sendStatus(emitter, AiRunStatus.IDLE);
        verify(emitter).complete();
    }

    private static AiThreadRecord threadRecord(String status) {
        AiThreadRecord row = new AiThreadRecord();
        row.setRunStatus(status);
        return row;
    }
}
