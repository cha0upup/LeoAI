package org.leo.web.service;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.leo.ai.runtime.AiTurnCoordinator;
import org.leo.ai.runtime.AiTurnEvent;
import org.leo.ai.runtime.AiTurnFailure;
import org.leo.ai.runtime.AiTurnOrchestrator;
import org.leo.ai.runtime.AiTurnOutcome;
import org.leo.ai.runtime.AiTurnTransaction;
import org.leo.ai.runtime.AiTurnTelemetryRegistry;
import org.leo.ai.runtime.AiTurnTrace;
import org.leo.ai.service.AiErrorClassifier;
import org.leo.core.entity.AiChatAuditEntry;
import org.leo.core.entity.AiSseEvent;
import org.leo.core.session.AiThread;
import org.leo.web.util.AiSseEventPump;
import org.leo.web.util.AiSseTransport;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiSseTurnPresenterTest {

    @Test
    void presentsSuccessfulStreamingTurnThroughOneLifecycle() throws Exception {
        Fixture fixture = fixture();

        fixture.presentation.onStarted();
        fixture.presentation.onEvent(AiTurnEvent.textDelta("answer"));
        fixture.presentation.beforeCommit();
        fixture.presentation.onCommitted(completed("answer"));

        assertEquals(2, fixture.runtimeRefreshes.get());
        assertEquals(1, fixture.beforeCompleted.get());
        assertEquals(List.of("node"),
                fixture.presentation.eventLog().stream()
                        .map(AiSseEvent::name).toList());
        List<String> names = fixture.persistedEvents
                .stream().map(AiSseEvent::name).toList();
        assertTrue(names.containsAll(
                List.of("status", "delta", "node", "turn")));
        verify(fixture.eventPump).stop(fixture.handle);
        verify(fixture.emitter).complete();
    }

    @Test
    void committedTerminalRecoveryReplaysTheFinalTurnPayload() {
        Fixture fixture = fixture();
        AiTurnTransaction.CompletedTurn completed = completed("saved");

        Logger presenterLogger = (Logger) LoggerFactory.getLogger(
                AiSseTurnPresenter.class);
        ListAppender<ILoggingEvent> capturedLogs = new ListAppender<>();
        boolean previousAdditive = presenterLogger.isAdditive();
        capturedLogs.start();
        presenterLogger.addAppender(capturedLogs);
        presenterLogger.setAdditive(false);
        try {
            fixture.presentation.onTerminal(new AiTurnOrchestrator.Terminal(
                    AiTurnOutcome.COMPLETED,
                    new IllegalStateException("runtime refresh failed"),
                    false,
                    completed,
                    null,
                    null));
        } finally {
            presenterLogger.setAdditive(previousAdditive);
            presenterLogger.detachAppender(capturedLogs);
            capturedLogs.stop();
        }

        List<AiSseEvent> events = fixture.persistedEvents;
        assertEquals(List.of("trace", "status", "turn"),
                events.stream().map(AiSseEvent::name).toList());
        assertEquals("saved",
                ((Map<?, ?>) events.get(2).data()).get("content"));
        assertTrue(capturedLogs.list.stream().anyMatch(event ->
                event.getFormattedMessage().contains("Turn 终态展示补偿")
                        && event.getThrowableProxy() != null));
        verify(fixture.emitter).complete();
    }

    @Test
    void presentsCancellationThroughTheSameFailureLifecycle() throws Exception {
        Fixture fixture = fixture();
        AiTurnTransaction.FailedTurn failed =
                new AiTurnTransaction.FailedTurn(
                        AiTurnOutcome.CANCELLED,
                        "cancelled",
                        "用户停止",
                        null);

        fixture.presentation.onDiscarded(
                failed,
                new AiTurnFailure(
                        new InterruptedException("stopped"),
                        AiTurnOutcome.CANCELLED,
                        "用户停止"));

        assertEquals("cancelled",
                fixture.persistedEvents.stream()
                        .filter(event -> "status".equals(event.name()))
                        .findFirst()
                        .orElseThrow()
                        .data());
        assertEquals(1, fixture.runtimeRefreshes.get());
        verify(fixture.emitter).complete();
    }

    @Test
    void transportOpenFailureFinishesTheClaimedRuntime() {
        AiSseTransport transport = mock(AiSseTransport.class);
        AiThread thread = new AiThread("thread-1", "test");
        assertTrue(thread.claimExecution());
        AiTurnCoordinator.Execution execution =
                new AiTurnCoordinator().attach(thread);
        SseEmitter emitter = mock(SseEmitter.class);
        AtomicInteger refreshes = new AtomicInteger();
        when(transport.open(
                anyString(), same(thread), same(emitter), any()))
                .thenThrow(new IllegalStateException("transport unavailable"));
        AiSseTurnPresenter presenter = new AiSseTurnPresenter(
                transport, new AiErrorClassifier(),
                new AiTurnTelemetryRegistry());

        AiSseTurnPresenter.Session presentation = presenter.open(
                context(thread, execution, emitter, refreshes, new AtomicInteger()));

        assertNull(presentation);
        assertEquals("failed", thread.getRunStatus());
        assertEquals(1, refreshes.get());
        verify(emitter).complete();
    }

    private Fixture fixture() {
        AiSseEventPump eventPump = mock(AiSseEventPump.class);
        SseEmitter emitter = mock(SseEmitter.class);
        AiThread thread = new AiThread("thread-1", "test");
        List<AiSseEvent> persistedEvents =
                new java.util.concurrent.CopyOnWriteArrayList<>();
        thread.configureEventJournal(0L, persistedEvents::add);
        assertTrue(thread.claimExecution());
        AiTurnCoordinator.Execution execution =
                new AiTurnCoordinator().attach(thread);
        AiSseEventPump.Handle handle = new AiSseEventPump.Handle(
                new AtomicBoolean(false), mock(Future.class));
        when(eventPump.start(
                anyString(), same(thread.getAiSseEventQueue()), any(),
                any(), any(), any())).thenReturn(handle);
        AiSseTransport transport = new AiSseTransport(
                eventPump, new org.leo.web.util.AiSseEmitterWriter());
        AtomicInteger refreshes = new AtomicInteger();
        AtomicInteger beforeCompleted = new AtomicInteger();
        AiSseTurnPresenter presenter = new AiSseTurnPresenter(
                transport, new AiErrorClassifier(),
                new AiTurnTelemetryRegistry());
        AiSseTurnPresenter.Session presentation = presenter.open(
                context(thread, execution, emitter, refreshes, beforeCompleted));
        assertNotNull(presentation);
        return new Fixture(
                eventPump, emitter, thread, handle,
                refreshes, beforeCompleted, persistedEvents, presentation);
    }

    private AiSseTurnPresenter.Context context(
            AiThread thread,
            AiTurnCoordinator.Execution execution,
            SseEmitter emitter,
            AtomicInteger refreshes,
            AtomicInteger beforeCompleted) {
        return new AiSseTurnPresenter.Context(
                "test", thread, execution, emitter,
                AiChatAuditEntry.platform(
                        "user-1", "alice", "normal", "hello"),
                System.currentTimeMillis(),
                AiTurnTrace.start(
                        "test", thread.getThreadId(),
                        System.currentTimeMillis()),
                refreshes::incrementAndGet,
                null,
                beforeCompleted::incrementAndGet,
                null);
    }

    private AiTurnTransaction.CompletedTurn completed(String output) {
        return new AiTurnTransaction.CompletedTurn(
                output, Map.of("toolCount", 0), Map.of());
    }

    private record Fixture(AiSseEventPump eventPump,
                           SseEmitter emitter,
                           AiThread thread,
                           AiSseEventPump.Handle handle,
                           AtomicInteger runtimeRefreshes,
                           AtomicInteger beforeCompleted,
                           List<AiSseEvent> persistedEvents,
                           AiSseTurnPresenter.Session presentation) {
    }
}
