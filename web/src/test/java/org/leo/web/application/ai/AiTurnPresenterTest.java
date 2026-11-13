package org.leo.web.application.ai;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.leo.ai.runtime.AiTurnCoordinator;
import org.leo.ai.runtime.AiTurnEvent;
import org.leo.ai.runtime.AiTurnFailure;
import org.leo.ai.runtime.AiTurnOrchestrator;
import org.leo.ai.runtime.AiTurnOutcome;
import org.leo.ai.runtime.AiTurnTelemetryRegistry;
import org.leo.ai.runtime.AiTurnTrace;
import org.leo.ai.runtime.AiTurnTransaction;
import org.leo.ai.service.AiErrorClassifier;
import org.leo.ai.service.AiPlanCoordinator;
import org.leo.core.entity.AiChatAuditEntry;
import org.leo.core.entity.AiPlan;
import org.leo.core.entity.AiSseEvent;
import org.leo.core.session.AiThread;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiTurnPresenterTest {

    @Test
    void publishesPlanSnapshotsWithTheSameSchemaForReplayAndLifecycleUpdates() {
        AiThread thread = new AiThread("thread-1", "test");
        AiPlan plan = new AiPlan("title", "goal", List.of());
        AiPlanCoordinator.emitPlanSnapshot(thread, plan, true);
        AiSseEvent created = thread.getAiSseEventQueue().poll();
        assertNotNull(created);
        assertEquals("node", created.name());
        assertEquals(Map.of("kind", "plan", "planId", plan.getPlanId(), "title", "title",
                "goal", "goal", "status", plan.getStatus().name(), "steps", plan.getSteps()), created.data());

        plan.complete("done");
        AiPlanCoordinator.emitPlanSnapshot(thread, plan, false);
        AiSseEvent updated = thread.getAiSseEventQueue().poll();
        assertNotNull(updated);
        assertEquals("patch", updated.name());
        Map<?, ?> payload = (Map<?, ?>) updated.data();
        assertEquals(plan.getPlanId(), payload.get("planId"));
        assertEquals("COMPLETED", payload.get("status"));
        assertEquals("done", payload.get("finalSummary"));
    }

    @Test
    void presentsSuccessfulTurnAndDrainsTheCompletionHookEvents() throws Exception {
        Fixture fixture = fixture();

        fixture.presentation.onStarted();
        fixture.presentation.onEvent(AiTurnEvent.textDelta("answer"));
        fixture.presentation.beforeCommit();
        fixture.presentation.onCommitted(completed("answer"));

        assertEquals(2, fixture.runtimeRefreshes.get());
        assertEquals(1, fixture.beforeCompleted.get());
        assertEquals(List.of("node", "patch"),
                fixture.presentation.eventLog().stream().map(AiSseEvent::name).toList());
        assertTrue(fixture.persistedEvents.stream().map(AiSseEvent::name).toList()
                .containsAll(List.of("status", "delta", "node", "patch", "turn")));
        assertTrue(fixture.thread.getAiSseEventQueue().isEmpty());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void archivesQueuedEventsAtCallbacksAndBothTerminalBoundaries(boolean discarded) {
        Fixture fixture = fixture();
        AiSseEvent plan = fixture.thread.offerSseEvent("node", Map.of("kind", "plan"));
        fixture.presentation.onEvent(AiTurnEvent.toolStarted(
                Map.of("kind", "tool", "subagentInvocationId", "child-1")));
        assertTrue(fixture.thread.getAiSseEventQueue().isEmpty());
        assertEquals("child-1", fixture.persistedEvents.get(2).subagentInvocationId());
        AiSseEvent queued = fixture.thread.offerSseEvent("warn", "tool warning");

        if (discarded) fixture.presentation.beforeDiscard(null);
        else fixture.presentation.beforeCommit();

        assertTrue(fixture.thread.getAiSseEventQueue().isEmpty());
        assertTrue(fixture.presentation.eventLog().containsAll(List.of(plan, queued)));
        assertEquals(1, fixture.persistedEvents.stream().filter(plan::equals).count());
        assertEquals(1, fixture.persistedEvents.stream().filter(queued::equals).count());
    }

    @Test
    void committedTerminalRecoveryReplaysTheFinalTurnPayload() {
        Fixture fixture = fixture();
        AiTurnTransaction.CompletedTurn completed = completed("saved");
        Logger presenterLogger = (Logger) LoggerFactory.getLogger(AiTurnPresenter.class);
        ListAppender<ILoggingEvent> capturedLogs = new ListAppender<>();
        boolean previousAdditive = presenterLogger.isAdditive();
        capturedLogs.start();
        presenterLogger.addAppender(capturedLogs);
        presenterLogger.setAdditive(false);
        try {
            fixture.presentation.onTerminal(new AiTurnOrchestrator.Terminal(
                    AiTurnOutcome.COMPLETED,
                    new IllegalStateException("runtime refresh failed"),
                    false, completed, null, null));
        } finally {
            presenterLogger.setAdditive(previousAdditive);
            presenterLogger.detachAppender(capturedLogs);
            capturedLogs.stop();
        }

        List<AiSseEvent> events = fixture.persistedEvents;
        assertEquals(List.of("trace", "status", "turn"), events.stream().map(AiSseEvent::name).toList());
        assertEquals("saved", ((Map<?, ?>) events.get(2).data()).get("content"));
        assertTrue(capturedLogs.list.stream().anyMatch(event ->
                event.getFormattedMessage().contains("Turn 终态展示补偿") && event.getThrowableProxy() != null));
    }

    @Test
    void presentsCancellationThroughTheSameFailureLifecycle() throws Exception {
        Fixture fixture = fixture();
        AiTurnTransaction.FailedTurn failed = new AiTurnTransaction.FailedTurn(
                AiTurnOutcome.CANCELLED, "cancelled", "用户停止", null);

        fixture.presentation.onDiscarded(failed, new AiTurnFailure(
                new InterruptedException("stopped"), AiTurnOutcome.CANCELLED, "用户停止"));

        assertEquals("cancelled", fixture.persistedEvents.stream()
                .filter(event -> "status".equals(event.name())).findFirst().orElseThrow().data());
        assertEquals(1, fixture.runtimeRefreshes.get());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void preparationFailureReleasesExecutionAndFinishesOnlyOnce(boolean cancelled) {
        Fixture fixture = fixture();
        Throwable error = cancelled ? new InterruptedException("stopped") : new IllegalStateException("setup failed");
        fixture.thread.offerWarnMessage("queued warning");

        fixture.presentation.finishPreparationFailure(error);
        fixture.presentation.finishPreparationFailure(error);

        assertFalse(fixture.thread.isExecuting());
        assertEquals(cancelled ? "cancelled" : "failed", fixture.thread.getRunStatus());
        assertEquals(1, fixture.runtimeRefreshes.get());
        assertTrue(fixture.thread.getAiSseEventQueue().isEmpty());
        assertEquals(1, fixture.persistedEvents.stream().filter(event -> "status".equals(event.name())).count());
    }

    private Fixture fixture() {
        AiThread thread = new AiThread("thread-1", "test");
        List<AiSseEvent> persistedEvents = new ArrayList<>();
        thread.configureEventJournal(0L, persistedEvents::add);
        assertTrue(thread.claimExecution());
        AtomicInteger refreshes = new AtomicInteger();
        AtomicInteger beforeCompleted = new AtomicInteger();
        AiTurnPresenter presenter = new AiTurnPresenter(new AiErrorClassifier(), new AiTurnTelemetryRegistry());
        AiTurnPresenter.Session presentation = presenter.open(new AiTurnPresenter.Context(
                "test", thread, new AiTurnCoordinator().attach(thread),
                AiChatAuditEntry.platform("user-1", "alice", "normal", "hello"), System.currentTimeMillis(),
                AiTurnTrace.start("test", thread.getThreadId(), System.currentTimeMillis()),
                refreshes::incrementAndGet, null, () -> {
                    beforeCompleted.incrementAndGet();
                    thread.offerSseEvent("patch", Map.of("kind", "plan", "status", "COMPLETED"));
                }, null));
        return new Fixture(thread, refreshes, beforeCompleted, persistedEvents, presentation);
    }

    private AiTurnTransaction.CompletedTurn completed(String output) {
        return new AiTurnTransaction.CompletedTurn(output, Map.of("toolCount", 0), Map.of());
    }

    private record Fixture(AiThread thread,
                           AtomicInteger runtimeRefreshes,
                           AtomicInteger beforeCompleted,
                           List<AiSseEvent> persistedEvents,
                           AiTurnPresenter.Session presentation) {
    }
}
