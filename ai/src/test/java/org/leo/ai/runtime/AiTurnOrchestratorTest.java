package org.leo.ai.runtime;

import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.memory.ChatMemoryAccess;
import org.junit.jupiter.api.Test;
import org.leo.ai.channel.AiModelFailoverService;
import org.leo.ai.memory.ManagedConversationMemory;
import org.leo.ai.service.AiErrorClassifier;
import org.leo.ai.thread.AiConversationStoreService;
import org.leo.core.entity.AiChatAuditEntry;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiTurnOrchestratorTest {

    @Test
    void commitsAndNotifiesLifecycle() {
        Fixture fixture = fixture(TestExecutionEngine.Mode.COMPLETE);

        fixture.orchestrator.execute(fixture.request(), fixture.lifecycle);

        assertEquals(List.of("started", "event", "beforeCommit", "committed"),
                fixture.lifecycle.callbacks);
        assertEquals("answer", fixture.lifecycle.completed.output());
        assertEquals(AiTurnOutcome.COMPLETED, fixture.runtime.outcome);
        assertEquals(1L, fixture.telemetry.snapshot().get("completedTurns"));
        assertEquals(1L,
                ((java.util.Map<?, ?>) fixture.telemetry.snapshot()
                        .get("outcomes")).get("completed"));
        verify(fixture.store).updateRunTrace(
                eq(fixture.persistedTurn), any(AiTurnTrace.class));
        verify(fixture.store, times(1)).completeTurn(
                eq(fixture.persistedTurn), eq("answer"),
                any(), any(), any(), eq(0));
        verify(fixture.store, never()).discardTurn(
                any(), any(), any(), any(), any(), anyInt(), any(), any(), any());
    }

    @Test
    void asynchronousExecutionCompletesFutureOnlyAfterTerminalCallback() {
        Fixture fixture = fixture(TestExecutionEngine.Mode.DEFER);

        CompletableFuture<AiTurnOrchestrator.TerminalResult> completion =
                fixture.orchestrator.execute(
                        fixture.request(), fixture.lifecycle);

        assertFalse(completion.isDone());
        assertEquals(List.of("started", "event"), fixture.lifecycle.callbacks);
        verify(fixture.store, never()).completeTurn(
                any(), any(), any(), any(), any(), anyInt());

        fixture.engine.completeDeferred();

        assertTrue(completion.isDone());
        assertEquals(AiTurnOutcome.COMPLETED, completion.join().outcome());
        assertEquals(List.of("started", "event", "beforeCommit", "committed"),
                fixture.lifecycle.callbacks);
        verify(fixture.store, times(1)).completeTurn(
                eq(fixture.persistedTurn), eq("answer"),
                any(), any(), any(), eq(0));
    }

    @Test
    void startupHookFailureDiscardsBeforeModelExecution() {
        Fixture fixture = fixture(TestExecutionEngine.Mode.COMPLETE);
        fixture.lifecycle.failOnStart = true;

        fixture.orchestrator.execute(fixture.request(), fixture.lifecycle);

        assertFalse(fixture.engine.executed);
        assertEquals(AiTurnOutcome.FAILED, fixture.runtime.outcome);
        assertNotNull(fixture.lifecycle.failed);
        assertEquals(List.of("started", "beforeDiscard", "discarded"),
                fixture.lifecycle.callbacks);
        verify(fixture.store, times(1)).discardTurn(
                eq(fixture.persistedTurn), eq("failed"), eq("network"),
                any(), any(), eq(0), eq(""), eq(List.of()), eq(null));
        verify(fixture.failover, never()).recordFailure(any(), any());
        verify(fixture.memory).rebuild(fixture.agent, "memory-1");
    }

    @Test
    void commitFailureIsRecoveredIntoOneDiscardedTerminal() {
        Fixture fixture = fixture(TestExecutionEngine.Mode.COMPLETE);
        doThrow(new IllegalStateException("commit unavailable"))
                .when(fixture.store).completeTurn(
                        any(), any(), any(), any(), any(), anyInt());

        fixture.orchestrator.execute(fixture.request(), fixture.lifecycle);

        assertEquals(AiTurnOutcome.FAILED, fixture.runtime.outcome);
        assertNotNull(fixture.lifecycle.terminal);
        assertTrue(fixture.lifecycle.terminal.recovered());
        assertTrue(fixture.lifecycle.terminal.discarded());
        assertEquals("persistence",
                fixture.lifecycle.terminal.failed().classification().category());
        assertEquals(1L,
                ((java.util.Map<?, ?>) fixture.telemetry.snapshot()
                        .get("errorCategories")).get("persistence"));
        verify(fixture.store, times(1)).discardTurn(
                eq(fixture.persistedTurn), eq("failed"), eq("persistence"),
                eq("AI 回复提交失败"), eq("commit unavailable"), eq(0),
                eq(""), eq(List.of()), eq(null));
    }

    @Test
    void postCommitNotificationFailureKeepsRuntimeCompleted() {
        Fixture fixture = fixture(TestExecutionEngine.Mode.COMPLETE);
        fixture.lifecycle.failOnCommitted = true;

        fixture.orchestrator.execute(fixture.request(), fixture.lifecycle);

        assertEquals(AiTurnOutcome.COMPLETED, fixture.runtime.outcome);
        assertNotNull(fixture.lifecycle.terminal);
        assertTrue(fixture.lifecycle.terminal.committed());
        assertFalse(fixture.lifecycle.terminal.recovered());
        verify(fixture.store, times(1)).completeTurn(
                eq(fixture.persistedTurn), eq("answer"),
                any(), any(), any(), eq(0));
        verify(fixture.store, never()).discardTurn(
                any(), any(), any(), any(), any(), anyInt(), any(), any(), any());
    }

    @Test
    void tracePersistenceFailureCannotLeaveTerminalFutureIncomplete() {
        Fixture fixture = fixture(TestExecutionEngine.Mode.COMPLETE);
        doThrow(new IllegalStateException("trace unavailable"))
                .when(fixture.store).updateRunTrace(
                        any(), any(AiTurnTrace.class));

        CompletableFuture<AiTurnOrchestrator.TerminalResult> completion =
                fixture.orchestrator.execute(fixture.request(), fixture.lifecycle);

        assertTrue(completion.isDone());
        assertEquals(AiTurnOutcome.COMPLETED, completion.join().outcome());
        verify(fixture.store, times(1)).completeTurn(
                eq(fixture.persistedTurn), eq("answer"),
                any(), any(), any(), eq(0));
    }

    @Test
    void modelCancellationUsesTheSameDiscardLifecycle() {
        Fixture fixture = fixture(TestExecutionEngine.Mode.CANCEL);
        fixture.runtime.stopRequested = true;
        fixture.runtime.stopReason = "用户停止";

        fixture.orchestrator.execute(fixture.request(), fixture.lifecycle);

        assertEquals(AiTurnOutcome.CANCELLED, fixture.runtime.outcome);
        assertTrue(fixture.lifecycle.failed.cancelled());
        assertEquals("用户停止", fixture.lifecycle.failed.message());
        assertEquals(1L,
                ((java.util.Map<?, ?>) fixture.telemetry.snapshot()
                        .get("outcomes")).get("cancelled"));
        verify(fixture.failover, never()).recordFailure(any(), any());
        verify(fixture.store).discardTurn(
                eq(fixture.persistedTurn), eq("cancelled"), eq("cancelled"),
                eq("用户停止"), eq("用户停止"), eq(0), eq(""), eq(List.of()), eq(null));
    }

    private Fixture fixture(TestExecutionEngine.Mode mode) {
        AiConversationStoreService store = mock(AiConversationStoreService.class);
        AiModelFailoverService failover = mock(AiModelFailoverService.class);
        AiErrorClassifier classifier = mock(AiErrorClassifier.class);
        ManagedConversationMemory memory = mock(ManagedConversationMemory.class);
        ChatMemoryAccess agent = mock(ChatMemoryAccess.class);
        when(classifier.classify(any(Throwable.class))).thenReturn(
                new AiErrorClassifier.Classification(
                        "network", "网络连接失败", "network down", List.of()));
        when(classifier.classifyCategory(eq("persistence"), any())).thenReturn(
                new AiErrorClassifier.Classification(
                        "persistence", "AI 回复提交失败",
                        "commit unavailable", List.of()));
        AiConversationStoreService.PersistedTurn persistedTurn =
                new AiConversationStoreService.PersistedTurn(
                        "turn-1", "run-1", "thread-1", "message-1",
                        "assistant-message-1", System.currentTimeMillis(), null);
        AiTurnTransaction transaction = new AiTurnTransaction(
                store, failover, classifier, new AiTurnArtifacts(), memory);
        TestExecutionEngine engine = new TestExecutionEngine(mode);
        AiTurnTelemetryRegistry telemetry = new AiTurnTelemetryRegistry();
        AiTurnOrchestrator orchestrator =
                new AiTurnOrchestrator(engine, transaction, telemetry);
        RecordingTurnRuntime runtime = new RecordingTurnRuntime();
        runtime.claimed = true;
        AiTurnCoordinator.Execution execution =
                new AiTurnCoordinator().attach(runtime);
        AiTurnOrchestrator.Request request = new AiTurnOrchestrator.Request(
                new AiTurnCommand(
                        "thread-1", "memory-1", execution,
                        () -> mock(TokenStream.class)),
                new AiTurnTransaction.Context(
                        persistedTurn, 7, agent, "memory-1",
                        AiChatAuditEntry.platform(
                                "user-1", "alice", "normal", "hello"),
                        System.currentTimeMillis(),
                        boundTrace(persistedTurn)),
                List.of(),
                () -> null,
                null);
        return new Fixture(
                store, failover, memory, agent, persistedTurn,
                engine, orchestrator, request, runtime, telemetry,
                new RecordingLifecycle());
    }

    private AiTurnTrace boundTrace(
            AiConversationStoreService.PersistedTurn persistedTurn) {
        AiTurnTrace trace = AiTurnTrace.testTrace(
                "trace-1", "test", persistedTurn.threadId(),
                persistedTurn.startedAt());
        trace.bind(persistedTurn.turnId(), persistedTurn.runId());
        return trace;
    }

    private record Fixture(AiConversationStoreService store,
                           AiModelFailoverService failover,
                           ManagedConversationMemory memory,
                           ChatMemoryAccess agent,
                           AiConversationStoreService.PersistedTurn persistedTurn,
                           TestExecutionEngine engine,
                           AiTurnOrchestrator orchestrator,
                           AiTurnOrchestrator.Request request,
                           RecordingTurnRuntime runtime,
                           AiTurnTelemetryRegistry telemetry,
                           RecordingLifecycle lifecycle) {
    }

    private static final class RecordingLifecycle
            implements AiTurnOrchestrator.Lifecycle {
        private final List<String> callbacks = new ArrayList<>();
        private boolean failOnStart;
        private boolean failOnCommitted;
        private AiTurnTransaction.CompletedTurn completed;
        private AiTurnTransaction.FailedTurn failed;
        private AiTurnOrchestrator.Terminal terminal;

        @Override
        public void onStarted() {
            callbacks.add("started");
            if (failOnStart) throw new IllegalStateException("startup unavailable");
        }

        @Override
        public void onEvent(AiTurnEvent event) {
            callbacks.add("event");
        }

        @Override
        public void beforeCommit() {
            callbacks.add("beforeCommit");
        }

        @Override
        public void onCommitted(AiTurnTransaction.CompletedTurn completed) {
            callbacks.add("committed");
            this.completed = completed;
            if (failOnCommitted) {
                throw new IllegalStateException("transport unavailable");
            }
        }

        @Override
        public void beforeDiscard(AiTurnFailure failure) {
            callbacks.add("beforeDiscard");
        }

        @Override
        public void onDiscarded(AiTurnTransaction.FailedTurn failed,
                                AiTurnFailure failure) {
            callbacks.add("discarded");
            this.failed = failed;
        }

        @Override
        public void onTerminal(AiTurnOrchestrator.Terminal terminal) {
            callbacks.add("terminal");
            this.terminal = terminal;
        }
    }

    private static final class TestExecutionEngine extends AiTurnExecutionEngine {
        private final Mode mode;
        private boolean executed;
        private AiTurnCommand deferredCommand;
        private AiTurnExecutionListener deferredListener;

        private TestExecutionEngine(Mode mode) {
            super(new org.leo.ai.agent.AiToolErrorHandler(),
                    new AiToolEventNormalizer(new org.leo.ai.agent.AiToolCatalog()));
            this.mode = mode;
        }

        @Override
        void execute(AiTurnCommand command, AiTurnExecutionListener listener) {
            executed = true;
            listener.onEvent(AiTurnEvent.textDelta("delta"));
            if (mode == Mode.DEFER) {
                deferredCommand = command;
                deferredListener = listener;
                return;
            }
            if (mode == Mode.COMPLETE) {
                try {
                    command.execution().finish(
                            AiTurnOutcome.COMPLETED,
                            () -> listener.onCompleted(
                                    new AiTurnResult("answer", null, 1L)));
                } catch (Exception error) {
                    listener.onTerminalFailure(AiTurnOutcome.COMPLETED, error);
                }
                return;
            }
            AiTurnFailure failure = new AiTurnFailure(
                    new InterruptedException("stopped"),
                    AiTurnOutcome.CANCELLED,
                    command.execution().cancellationReason());
            try {
                command.execution().finish(
                        AiTurnOutcome.CANCELLED,
                        () -> listener.onFailed(failure));
            } catch (Exception error) {
                listener.onTerminalFailure(AiTurnOutcome.CANCELLED, error);
            }
        }

        private void completeDeferred() {
            try {
                deferredCommand.execution().finish(
                        AiTurnOutcome.COMPLETED,
                        () -> deferredListener.onCompleted(
                                new AiTurnResult("answer", null, 1L)));
            } catch (Exception error) {
                deferredListener.onTerminalFailure(
                        AiTurnOutcome.COMPLETED, error);
            }
        }

        private enum Mode {
            COMPLETE,
            CANCEL,
            DEFER
        }
    }
}
