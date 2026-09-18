package org.leo.web.service;

import org.junit.jupiter.api.Test;
import org.leo.ai.runtime.AiTurnCoordinator;
import org.leo.ai.runtime.AiTurnEvent;
import org.leo.ai.runtime.AiTurnFailure;
import org.leo.ai.runtime.AiTurnOutcome;
import org.leo.ai.runtime.AiTurnTransaction;
import org.leo.ai.runtime.AiTurnTelemetryRegistry;
import org.leo.ai.runtime.AiTurnTrace;
import org.leo.ai.service.AiErrorClassifier;
import org.leo.ai.thread.AiConversationStoreService;
import org.leo.core.entity.AiChatAuditEntry;
import org.leo.core.entity.AiSseEvent;
import org.leo.core.session.AiThread;
import org.leo.core.session.PuppetNodeSession;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class PuppetNodeAiDelegationPresenterTest {

    @Test
    void recordsDelegatedTimelineAndBuildsSynchronousResponse() throws Exception {
        Fixture fixture = fixture();

        fixture.presentation.onStarted();
        fixture.presentation.onEvent(AiTurnEvent.textDelta("answer"));
        fixture.presentation.beforeCommit();
        fixture.thread.markCompleted();
        fixture.presentation.onCommitted(completed("answer"));

        Map<String, Object> response = fixture.presentation.await();
        assertEquals("session-1", response.get("sessionId"));
        assertEquals("thread-1", response.get("threadId"));
        assertEquals("completed", response.get("status"));
        assertEquals("answer", response.get("summary"));
        assertEquals(List.of("trace", "status", "delta", "node", "status"),
                fixture.events.stream().map(AiSseEvent::name).toList());
        assertTrue(fixture.events.stream().allMatch(
                event -> "invocation-1".equals(
                        event.subagentInvocationId())));
        verify(fixture.store, times(2))
                .updateRuntime("session-1", fixture.thread, null);
    }

    @Test
    void waitingForUserIsPreservedInEventsAndResponse() throws Exception {
        Fixture fixture = fixture();
        fixture.thread.markWaitingForUserInput();
        fixture.thread.markCompleted();

        fixture.presentation.onCommitted(completed(""));

        assertEquals("waiting_for_user", fixture.presentation.await().get("status"));
        assertEquals("waiting_for_user", fixture.events.get(fixture.events.size() - 1).data());
    }

    @Test
    void preparationFailureProducesStableStatusAndException() {
        Fixture fixture = fixture();
        assertTrue(fixture.thread.claimExecution());
        AiTurnCoordinator.Execution execution =
                new AiTurnCoordinator().attach(fixture.thread);

        IllegalStateException failure = fixture.presentation.executionFailure(
                execution, new IllegalStateException("prepare failed"));

        assertEquals("prepare failed", failure.getMessage());
        assertEquals("failed", fixture.thread.getRunStatus());
        assertEquals("failed",
                fixture.events.get(fixture.events.size() - 1).data());
        verify(fixture.store).updateRuntime(
                "session-1", fixture.thread, null);
    }

    @Test
    void discardedDelegationCompletesTheAwaiterExceptionally() {
        Fixture fixture = fixture();
        AiTurnTransaction.FailedTurn failed =
                new AiTurnTransaction.FailedTurn(
                        AiTurnOutcome.FAILED,
                        "failed",
                        "model failed",
                        new AiErrorClassifier().classify("model failed"));

        fixture.presentation.onDiscarded(
                failed,
                new AiTurnFailure(
                        new IllegalStateException("model failed"),
                        AiTurnOutcome.FAILED,
                        null));

        Exception error = assertThrows(
                Exception.class, fixture.presentation::await);
        assertTrue(error.getCause() instanceof IllegalStateException);
        assertEquals("failed",
                fixture.events.get(fixture.events.size() - 1).data());
    }

    private Fixture fixture() {
        AiConversationStoreService store =
                mock(AiConversationStoreService.class);
        PuppetNodeSession session = mock(PuppetNodeSession.class);
        org.mockito.Mockito.when(session.getSessionId()).thenReturn("session-1");
        AiThread thread = new AiThread("thread-1", "test");
        List<AiSseEvent> events = new ArrayList<>();
        PuppetNodeAiDelegationPresenter presenter =
                new PuppetNodeAiDelegationPresenter(
                        store, new AiErrorClassifier(),
                        new AiTurnTelemetryRegistry());
        PuppetNodeAiDelegationPresenter.Session presentation =
                presenter.open(new PuppetNodeAiDelegationPresenter.Context(
                        session,
                        thread,
                                AiChatAuditEntry.platform(
                                        "user-1", "alice", "normal", "hello"),
                        System.currentTimeMillis(),
                        AiTurnTrace.start(
                                "delegation", thread.getThreadId(),
                                System.currentTimeMillis()),
                        "invocation-1",
                        events::add));
        return new Fixture(store, thread, events, presentation);
    }

    private AiTurnTransaction.CompletedTurn completed(String output) {
        return new AiTurnTransaction.CompletedTurn(
                output, 0, Map.of("toolCount", 0),
                Map.of(), List.of());
    }

    private record Fixture(AiConversationStoreService store,
                           AiThread thread,
                           List<AiSseEvent> events,
                           PuppetNodeAiDelegationPresenter.Session presentation) {
    }
}
