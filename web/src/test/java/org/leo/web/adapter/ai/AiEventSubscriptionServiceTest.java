package org.leo.web.adapter.ai;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.leo.ai.thread.AiConversationStoreService;
import org.leo.ai.thread.AiEventJournalRepository;
import org.leo.ai.thread.AiMessageRepository;
import org.leo.ai.thread.AiProtocolTurnRepository;
import org.leo.core.ai.AiRunStatus;
import org.leo.core.entity.AiSseEvent;
import org.leo.core.entity.AiThreadRecord;
import org.leo.core.entity.AiTurnRecord;
import org.leo.core.session.AiThread;
import org.leo.web.application.ai.AiThreadQueryService;
import org.leo.web.application.ai.AiTurnProtocolService;
import org.leo.web.infrastructure.concurrent.AiExecutionExecutor;
import org.mockito.ArgumentCaptor;
import org.mockito.stubbing.Answer;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
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
    private final AiExecutionExecutor executor = mock(AiExecutionExecutor.class);
    private final AiSseEmitterWriter writer = mock(AiSseEmitterWriter.class);
    private final AiConversationStoreService conversations = mock(AiConversationStoreService.class);
    private final AiEventJournalRepository eventStore = mock(AiEventJournalRepository.class);
    private final AiProtocolTurnRepository turns = mock(AiProtocolTurnRepository.class);
    private final AiThreadQueryService threadQueries = new AiThreadQueryService(
            conversations, mock(AiMessageRepository.class), eventStore, mock(AiTurnProtocolService.class));
    private final AiEventSubscriptionService service = new AiEventSubscriptionService(
            executor, writer, conversations, eventStore, turns, threadQueries);

    @ParameterizedTest
    @CsvSource({"200,false", "401,false", "2,true"})
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
        when(eventStore.listAfter(eq("thread-1"), anyLong(), eq(200)))
                .thenAnswer(invocation -> events.stream()
                        .filter(event -> event.seq() > (long) invocation.getArgument(1))
                        .limit(running ? 1 : 200).toList());
        when(eventStore.lastSequence("thread-1")).thenReturn(thread.getLastSseEventSeq());
        when(conversations.findThread("thread-1"))
                .thenReturn(threadRecord(AiRunStatus.COMPLETED));
        when(eventStore.hasLatestTurnCompleted("thread-1")).thenReturn(true);

        service.stream("test", "thread-1", thread, emitter,
                first.seq(), new AtomicBoolean(false));

        ArgumentCaptor<AiSseEvent> sent = ArgumentCaptor.forClass(AiSseEvent.class);
        verify(writer, times(eventCount)).sendEvent(eq(emitter), sent.capture());
        assertEquals(events, sent.getAllValues());
        verify(eventStore).lastSequence("thread-1");
        verify(conversations).findThread("thread-1");
        verify(turns).listInProgress("thread-1");
        verify(eventStore).hasLatestTurnCompleted("thread-1");
        verify(writer).sendStatus(emitter, AiRunStatus.COMPLETED);
        verify(emitter).complete();
    }

    @Test
    void subscriberDisconnectDoesNotCancelTheRunningTask() throws Exception {
        AiThread thread = new AiThread("thread-2", "test");
        assertTrue(thread.claimExecution());
        AiSseEvent live = thread.recordSseEvent("delta", "live");
        SseEmitter emitter = mock(SseEmitter.class);
        when(eventStore.listAfter("thread-2", 0L, 200))
                .thenReturn(List.of(live));
        doThrow(new IOException("client disconnected"))
                .when(writer).sendEvent(eq(emitter), any(AiSseEvent.class));

        service.stream("test", "thread-2", thread, emitter,
                0L, new AtomicBoolean(false));

        assertTrue(thread.isExecuting());
        assertEquals(AiRunStatus.RUNNING, thread.getRunStatus());
        verify(emitter).complete();
    }

    @ParameterizedTest
    @CsvSource(value = {"null,2,2", "1,3,1"}, nullValues = "null")
    void subscriptionUsesTheReplayCursor(Long requestedCursor, long persistedStart, long expectedCursor) throws Exception {
        AiThread thread = new AiThread("thread-3", "test");
        AiSseEvent previousTurn = thread.recordSseEvent("turn", "previous");
        assertTrue(thread.claimExecution());
        AiSseEvent opening = thread.recordSseEvent("turn", "opening");
        AiSseEvent currentTurn = thread.recordSseEvent("turn", "current");
        thread.markCompleted();
        thread.clearExecuting();
        when(eventStore.latestTurnStartSequence("thread-3")).thenReturn(persistedStart);
        when(eventStore.listAfter(eq("thread-3"), anyLong(), eq(200)))
                .thenAnswer(call -> List.of(previousTurn, opening, currentTurn).stream()
                        .filter(event -> event.seq() > call.<Long>getArgument(1)).toList());
        when(eventStore.lastSequence("thread-3")).thenReturn(currentTurn.seq());
        when(conversations.findThread("thread-3"))
                .thenReturn(threadRecord(AiRunStatus.COMPLETED));
        when(eventStore.hasLatestTurnCompleted("thread-3")).thenReturn(true);

        SseEmitter emitter = service.subscribe("test", "thread-3", thread, requestedCursor);
        var task = ArgumentCaptor.forClass(Runnable.class);
        verify(executor).submitSubscription(task.capture());
        task.getValue().run();

        verify(eventStore).listAfter("thread-3", expectedCursor, 200);
        verify(eventStore, times(requestedCursor == null ? 1 : 0)).latestTurnStartSequence("thread-3");
        verify(writer, never()).sendEvent(emitter, previousTurn);
        verify(writer, times(requestedCursor == null ? 0 : 1)).sendEvent(emitter, opening);
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
        when(eventStore.listAfter("thread-4", 0L, 200))
                .thenReturn(List.of(), List.of(completed));
        when(eventStore.lastSequence("thread-4"))
                .thenReturn(0L, completed.seq());
        when(conversations.findThread("thread-4"))
                .thenReturn(threadRecord(AiRunStatus.COMPLETED));
        when(eventStore.hasLatestTurnCompleted("thread-4"))
                .thenReturn(false, true);

        service.stream("test", "thread-4", thread, emitter,
                0L, new AtomicBoolean(false));

        var order = inOrder(eventStore, writer);
        order.verify(eventStore).hasLatestTurnCompleted("thread-4");
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
        when(eventStore.listAfter("thread-queued", 0L, 200))
                .thenReturn(List.of(), List.of(completed));
        when(eventStore.lastSequence("thread-queued"))
                .thenReturn(0L, 1L);
        when(turns.listInProgress("thread-queued"))
                .thenReturn(List.of(queued), List.of());
        when(conversations.findThread("thread-queued"))
                .thenReturn(threadRecord(AiRunStatus.IDLE));
        when(eventStore.hasLatestTurnCompleted("thread-queued"))
                .thenReturn(true);

        service.stream("test", "thread-queued", thread, emitter,
                0L, new AtomicBoolean(false));

        verify(writer).sendEvent(emitter, completed);
        verify(writer).sendHeartbeat(eq(emitter), argThat(heartbeat ->
                "queued".equals(heartbeat.get("status")) && Boolean.TRUE.equals(heartbeat.get("executing"))));
        verify(conversations).findThread("thread-queued");
        verify(eventStore).hasLatestTurnCompleted("thread-queued");
        verify(writer, never()).sendStatus(emitter, AiRunStatus.IDLE);
        verify(emitter).complete();
    }

    @Test
    void sharesJournalReadsAcrossConcurrentSubscribersAndReleasesTheCache() throws Exception {
        AiEventSubscriptionService shared = new AiEventSubscriptionService(
                executor, writer, conversations, eventStore, turns, threadQueries, () -> 0L);
        AiThread thread = new AiThread("shared", "test");
        assertTrue(thread.claimExecution());

        runOverlappingSubscribers(shared, thread, IntStream.range(0, 8)
                .mapToObj(index -> 0L).toList(), null);

        verify(eventStore).listAfter("shared", 0L, 200);
        verify(eventStore).lastSequence("shared");
        verify(turns).listInProgress("shared");
        assertTrue(thread.isExecuting());

        // The last disconnect must discard cached empty pages, even if the clock
        // has not advanced and a later subscriber reuses the same cursor.
        thread.markCompleted();
        thread.clearExecuting();
        AiSseEvent event = thread.recordSseEvent("delta", "after reconnect");
        when(eventStore.listAfter("shared", 0L, 200)).thenReturn(List.of(event));
        when(eventStore.lastSequence("shared")).thenReturn(event.seq());
        when(conversations.findThread("shared")).thenReturn(threadRecord(AiRunStatus.COMPLETED));
        when(eventStore.hasLatestTurnCompleted("shared")).thenReturn(true);
        SseEmitter reconnected = mock(SseEmitter.class);

        shared.stream("test", "shared", thread, reconnected, 0L, new AtomicBoolean(false));

        verify(eventStore, times(2)).listAfter("shared", 0L, 200);
        verify(writer).sendEvent(reconnected, event);
        verify(writer).sendStatus(reconnected, AiRunStatus.COMPLETED);
        verify(reconnected).complete();
    }

    @Test
    void overlappingSubscribersKeepIndependentReplayCursors() throws Exception {
        AiEventSubscriptionService shared = new AiEventSubscriptionService(
                executor, writer, conversations, eventStore, turns, threadQueries, () -> 0L);
        AiThread thread = new AiThread("independent", "test");
        assertTrue(thread.claimExecution());
        List<AiSseEvent> events = IntStream.range(0, 3)
                .mapToObj(index -> thread.recordSseEvent("delta", "event-" + index)).toList();
        when(eventStore.listAfter(eq("independent"), anyLong(), eq(200)))
                .thenAnswer(invocation -> events.stream()
                        .filter(event -> event.seq() > (long) invocation.getArgument(1)).toList());

        List<SseEmitter> emitters = runOverlappingSubscribers(
                shared, thread, List.of(0L, 1L), events.get(2));

        for (AiSseEvent event : events) verify(writer).sendEvent(emitters.get(0), event);
        verify(writer, never()).sendEvent(emitters.get(1), events.get(0));
        verify(writer).sendEvent(emitters.get(1), events.get(1));
        verify(writer).sendEvent(emitters.get(1), events.get(2));
        verify(eventStore).listAfter("independent", 0L, 200);
        verify(eventStore).listAfter("independent", 1L, 200);
        verify(turns, never()).listInProgress("independent");
    }

    @Test
    void refreshesCachedEmptyPagesToObserveEventsFromAnotherRuntime() throws Exception {
        AtomicLong clock = new AtomicLong();
        AiEventSubscriptionService shared = new AiEventSubscriptionService(
                executor, writer, conversations, eventStore, turns, threadQueries, clock::get);
        AiThread thread = new AiThread("refresh", "test");
        assertTrue(thread.claimExecution());
        SseEmitter first = mock(SseEmitter.class);
        SseEmitter second = mock(SseEmitter.class);
        AtomicBoolean firstClosed = new AtomicBoolean(false);
        AtomicBoolean secondClosed = new AtomicBoolean(false);
        CountDownLatch firstRead = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        doAnswer(invocation -> {
            firstRead.countDown();
            assertTrue(releaseFirst.await(2, TimeUnit.SECONDS));
            firstClosed.set(true);
            return null;
        }).when(writer).sendHeartbeat(eq(first), any());
        doAnswer(invocation -> {
            secondClosed.set(true);
            return null;
        }).when(writer).sendEvent(eq(second), any(AiSseEvent.class));
        AiSseEvent remoteEvent = new AiSseEvent(1L, 1L, "delta", "remote", null, null, null, null);
        when(eventStore.listAfter("refresh", 0L, 200))
                .thenReturn(List.of(), List.of(remoteEvent));
        var workers = Executors.newSingleThreadExecutor();
        try {
            Future<?> running = workers.submit(() -> shared.stream(
                    "test", "refresh", thread, first, 0L, firstClosed));
            assertTrue(firstRead.await(2, TimeUnit.SECONDS));
            clock.set(TimeUnit.MILLISECONDS.toNanos(200));

            shared.stream("test", "refresh", thread, second, 0L, secondClosed);

            releaseFirst.countDown();
            running.get(2, TimeUnit.SECONDS);
        } finally {
            releaseFirst.countDown();
            workers.shutdownNow();
        }

        assertEquals(0L, thread.getLastSseEventSeq());
        verify(eventStore, times(2)).listAfter("refresh", 0L, 200);
        verify(turns).listInProgress("refresh");
        verify(writer).sendEvent(second, remoteEvent);
        assertTrue(thread.isExecuting());
    }

    @Test
    void journalReadFailureDoesNotPoisonTheNextSubscription() throws Exception {
        AiEventSubscriptionService shared = new AiEventSubscriptionService(
                executor, writer, conversations, eventStore, turns, threadQueries, () -> 0L);
        AiThread thread = new AiThread("read-failure", "test");
        thread.markCompleted();
        AiSseEvent event = thread.recordSseEvent("delta", "persisted");
        when(eventStore.listAfter("read-failure", 0L, 200))
                .thenThrow(new IllegalStateException("database busy"))
                .thenReturn(List.of(event));
        when(eventStore.lastSequence("read-failure")).thenReturn(event.seq());
        when(conversations.findThread("read-failure")).thenReturn(threadRecord(AiRunStatus.COMPLETED));
        when(eventStore.hasLatestTurnCompleted("read-failure")).thenReturn(true);
        SseEmitter failed = mock(SseEmitter.class);
        SseEmitter reconnected = mock(SseEmitter.class);

        shared.stream("test", "read-failure", thread, failed, 0L, new AtomicBoolean(false));
        shared.stream("test", "read-failure", thread, reconnected, 0L, new AtomicBoolean(false));

        verify(failed).complete();
        verify(writer).sendEvent(reconnected, event);
        verify(writer).sendStatus(reconnected, AiRunStatus.COMPLETED);
        verify(reconnected).complete();
    }

    @Test
    void newerEventsInvalidateACachedTerminalStateBeforeItsExpiry() throws Exception {
        AiEventSubscriptionService shared = new AiEventSubscriptionService(
                executor, writer, conversations, eventStore, turns, threadQueries, () -> 0L);
        AiThread thread = new AiThread("new-turn", "test");
        thread.markCompleted();
        AiSseEvent oldEvent = new AiSseEvent(1L, 1L, "turn/completed", "old", null, null, null, null);
        AiSseEvent queuedEvent = new AiSseEvent(2L, 2L, "turn/queued", "new", null, null, null, null);
        AiTurnRecord queued = new AiTurnRecord();
        queued.setDispatchStatus("queued");
        when(eventStore.listAfter("new-turn", 0L, 200)).thenReturn(List.of(oldEvent));
        when(eventStore.listAfter("new-turn", 1L, 200)).thenReturn(List.of(queuedEvent));
        when(eventStore.lastSequence("new-turn")).thenReturn(1L, 2L);
        when(turns.listInProgress("new-turn"))
                .thenReturn(List.of(), List.of(queued));
        when(conversations.findThread("new-turn")).thenReturn(threadRecord(AiRunStatus.COMPLETED));
        when(eventStore.hasLatestTurnCompleted("new-turn")).thenReturn(true);
        SseEmitter first = mock(SseEmitter.class);
        SseEmitter second = mock(SseEmitter.class);
        AtomicBoolean secondClosed = new AtomicBoolean(false);
        CountDownLatch terminalRead = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        doAnswer(invocation -> {
            terminalRead.countDown();
            assertTrue(releaseFirst.await(2, TimeUnit.SECONDS));
            return null;
        }).when(writer).sendStatus(first, AiRunStatus.COMPLETED);
        doAnswer(invocation -> {
            secondClosed.set(true);
            return null;
        }).when(writer).sendEvent(second, queuedEvent);
        var workers = Executors.newSingleThreadExecutor();
        try {
            Future<?> running = workers.submit(() -> shared.stream(
                    "test", "new-turn", thread, first, 0L, new AtomicBoolean(false)));
            assertTrue(terminalRead.await(2, TimeUnit.SECONDS));

            shared.stream("test", "new-turn", thread, second, 1L, secondClosed);

            releaseFirst.countDown();
            running.get(2, TimeUnit.SECONDS);
        } finally {
            releaseFirst.countDown();
            workers.shutdownNow();
        }

        verify(turns, times(2)).listInProgress("new-turn");
        verify(writer).sendEvent(second, queuedEvent);
        verify(writer, never()).sendStatus(eq(second), any());
        verify(second).complete();
    }

    private List<SseEmitter> runOverlappingSubscribers(AiEventSubscriptionService shared,
                                                      AiThread thread, List<Long> cursors,
                                                      AiSseEvent lastEvent)
            throws Exception {
        Map<SseEmitter, AtomicBoolean> closed = new LinkedHashMap<>();
        for (int index = 0; index < cursors.size(); index++) {
            closed.put(mock(SseEmitter.class), new AtomicBoolean(false));
        }
        CountDownLatch readers = new CountDownLatch(cursors.size());
        Answer<Void> lastWrite = invocation -> {
            readers.countDown();
            assertTrue(readers.await(2, TimeUnit.SECONDS));
            closed.get(invocation.getArgument(0)).set(true);
            return null;
        };
        if (lastEvent == null) {
            doAnswer(lastWrite).when(writer).sendHeartbeat(any(SseEmitter.class), any());
        } else {
            doAnswer(lastWrite).when(writer).sendEvent(any(SseEmitter.class), eq(lastEvent));
        }
        List<SseEmitter> emitters = new ArrayList<>(closed.keySet());
        List<Future<?>> results = new ArrayList<>();
        var workers = Executors.newFixedThreadPool(cursors.size());
        try {
            for (int index = 0; index < cursors.size(); index++) {
                SseEmitter emitter = emitters.get(index);
                long cursor = cursors.get(index);
                results.add(workers.submit(() -> shared.stream("test", thread.getThreadId(),
                        thread, emitter, cursor, closed.get(emitter))));
            }
            for (Future<?> result : results) result.get(3, TimeUnit.SECONDS);
        } finally {
            closed.values().forEach(flag -> flag.set(true));
            workers.shutdownNow();
        }
        for (SseEmitter emitter : emitters) verify(emitter).complete();
        return emitters;
    }

    private static AiThreadRecord threadRecord(String status) {
        AiThreadRecord row = new AiThreadRecord();
        row.setRunStatus(status);
        return row;
    }
}
