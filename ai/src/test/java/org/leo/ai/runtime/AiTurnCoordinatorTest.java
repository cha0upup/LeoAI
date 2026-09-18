package org.leo.ai.runtime;

import org.junit.jupiter.api.Test;
import org.leo.ai.platform.PlatformAiState;
import org.leo.core.ai.AiTurnRuntime;
import org.leo.core.session.AiThread;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiTurnCoordinatorTest {

    private final AiTurnCoordinator coordinator = new AiTurnCoordinator();

    @Test
    void onlyFirstTerminalSignalCanFinalizeRun() throws Exception {
        RecordingRuntime runtime = new RecordingRuntime();
        assertTrue(coordinator.tryClaim(runtime));
        assertFalse(coordinator.tryClaim(runtime));

        AiTurnCoordinator.Execution execution = coordinator.attach(runtime);
        AtomicInteger actions = new AtomicInteger();

        assertTrue(execution.finish(AiTurnOutcome.COMPLETED, actions::incrementAndGet));
        assertFalse(execution.finish(AiTurnOutcome.FAILED, actions::incrementAndGet));

        assertEquals(1, actions.get());
        assertEquals(AiTurnOutcome.COMPLETED, runtime.outcome);
        assertEquals(1, runtime.clearCount);
        assertFalse(runtime.claimed);
    }

    @Test
    void clearsExecutionEvenWhenTerminalActionFails() {
        RecordingRuntime runtime = new RecordingRuntime();
        assertTrue(coordinator.tryClaim(runtime));
        AiTurnCoordinator.Execution execution = coordinator.attach(runtime);

        assertThrows(IllegalStateException.class, () ->
                execution.finish(AiTurnOutcome.COMPLETED, () -> {
                    throw new IllegalStateException("持久化失败");
                }));

        assertTrue(execution.isFinished());
        assertEquals(AiTurnOutcome.FAILED, runtime.outcome);
        assertEquals(1, runtime.clearCount);
        assertFalse(runtime.claimed);
    }

    @Test
    void marksRunFailedEvenWhenTerminalActionThrowsAnError() {
        RecordingRuntime runtime = new RecordingRuntime();
        assertTrue(coordinator.tryClaim(runtime));
        AiTurnCoordinator.Execution execution = coordinator.attach(runtime);

        assertThrows(AssertionError.class, () ->
                execution.finish(AiTurnOutcome.COMPLETED, () -> {
                    throw new AssertionError("fatal persistence error");
                }));

        assertEquals(AiTurnOutcome.FAILED, runtime.outcome);
        assertEquals(1, runtime.clearCount);
        assertFalse(runtime.claimed);
    }

    @Test
    void treatsNestedInterruptedExceptionAsCancellation() {
        RecordingRuntime runtime = new RecordingRuntime();
        AiTurnCoordinator.Execution execution = coordinator.attach(runtime);

        RuntimeException error = new RuntimeException(new InterruptedException("interrupted"));

        assertTrue(execution.isCancellation(error));
        assertEquals("已停止", execution.cancellationReason());
    }

    @Test
    void usesRuntimeCancellationReasonAndCallback() {
        RecordingRuntime runtime = new RecordingRuntime();
        AiTurnCoordinator.Execution execution = coordinator.attach(runtime);
        AtomicInteger callbacks = new AtomicInteger();
        execution.registerCancellation(callbacks::incrementAndGet);

        runtime.requestStop("用户取消");

        assertTrue(execution.isCancellationRequested());
        assertEquals("用户取消", execution.cancellationReason());
        assertEquals(1, callbacks.get());
    }

    @Test
    void cancellationBeforeCallbackRegistrationIsDeliveredOnce() throws Exception {
        RecordingRuntime runtime = new RecordingRuntime();
        AiTurnCoordinator.Execution execution = coordinator.attach(runtime);
        AtomicInteger callbacks = new AtomicInteger();

        execution.cancel("父任务停止");
        execution.registerCancellation(callbacks::incrementAndGet);
        execution.cancel("重复停止");
        execution.finish(AiTurnOutcome.CANCELLED, () -> {});

        assertEquals(1, callbacks.get());
        assertTrue(execution.isCancellationRequested());
        assertEquals("父任务停止", execution.cancellationReason());
    }

    @Test
    void detachesCancellationCallbackFromBothRuntimeImplementations() throws Exception {
        List<AiTurnRuntime> runtimes = List.of(
                new AiThread("thread-1", "节点会话"),
                new PlatformAiState("platform-1"));

        for (AiTurnRuntime runtime : runtimes) {
            AtomicInteger callbacks = new AtomicInteger();
            assertTrue(coordinator.tryClaim(runtime));
            AiTurnCoordinator.Execution execution = coordinator.attach(runtime);
            execution.registerCancellation(callbacks::incrementAndGet);
            execution.finish(AiTurnOutcome.COMPLETED, () -> {});

            if (runtime instanceof AiThread thread) {
                thread.stop();
                assertFalse(thread.isExecuting());
            } else if (runtime instanceof PlatformAiState state) {
                state.stopGeneration();
                assertFalse(state.isExecuting());
            }
            assertEquals(0, callbacks.get());
        }
    }

    @Test
    void aNewTurnAfterStopStartsWithFreshCancellationState() throws Exception {
        RecordingRuntime runtime = new RecordingRuntime();
        assertTrue(coordinator.tryClaim(runtime));
        AiTurnCoordinator.Execution stopped = coordinator.attach(runtime);
        runtime.requestStop("用户停止");
        stopped.finish(AiTurnOutcome.CANCELLED, () -> {});

        assertTrue(coordinator.tryClaim(runtime));
        AiTurnCoordinator.Execution next = coordinator.attach(runtime);

        assertFalse(next.isCancellationRequested());
        assertEquals("已停止", next.cancellationReason());
        next.finish(AiTurnOutcome.COMPLETED, () -> {});
        assertEquals(AiTurnOutcome.COMPLETED, runtime.outcome);
    }

    private static final class RecordingRuntime implements AiTurnRuntime {

        private boolean claimed;
        private boolean stopRequested;
        private String stopReason;
        private Runnable stopCallback;
        private AiTurnOutcome outcome;
        private int clearCount;

        @Override
        public boolean claimExecution() {
            if (claimed) {
                return false;
            }
            claimed = true;
            stopRequested = false;
            stopReason = null;
            return true;
        }

        @Override
        public void markExecuting(Thread thread) {
            claimed = true;
        }

        @Override
        public void clearExecuting() {
            claimed = false;
            stopRequested = false;
            stopCallback = null;
            clearCount++;
        }

        @Override
        public boolean isStopRequested() {
            return stopRequested;
        }

        @Override
        public String getStopReason() {
            return stopReason;
        }

        @Override
        public void setStopCallback(Runnable callback) {
            stopCallback = callback;
        }

        @Override
        public void markCompleted() {
            outcome = AiTurnOutcome.COMPLETED;
        }

        @Override
        public void markFailed() {
            outcome = AiTurnOutcome.FAILED;
        }

        @Override
        public void markCancelled() {
            outcome = AiTurnOutcome.CANCELLED;
        }

        private void requestStop(String reason) {
            stopRequested = true;
            stopReason = reason;
            if (stopCallback != null) {
                stopCallback.run();
            }
        }
    }
}
