package org.leo.web.application.ai;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.leo.web.infrastructure.concurrent.AiExecutionExecutor;
import org.mockito.invocation.InvocationOnMock;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiTurnQueueServiceTest {

    private final AiTurnProtocolService protocol = mock(AiTurnProtocolService.class);
    private final AiExecutionExecutor executor = mock(AiExecutionExecutor.class);
    private final AiTurnApplicationService application = mock(AiTurnApplicationService.class);
    private final List<Runnable> submitted = new ArrayList<>();
    private final CompletableFuture<Boolean> completion = new CompletableFuture<>();
    private final AiTurnProtocolService.TurnSnapshot turn = new AiTurnProtocolService.TurnSnapshot(
            "turn-1", "thread-1", "running", "client-1", "user-1", "assistant-1", 1L, 2L, null,
            false, null, AiTurnCommandPayload.SCOPE_PLATFORM, "{}", null);
    private final AiTurnQueueService queue = new AiTurnQueueService(protocol, executor, application);

    @BeforeEach
    void captureScheduledTasksAndClaimTheQueuedTurn() {
        when(executor.tryAcquireTurn()).thenReturn(true);
        doAnswer(this::captureTask).when(executor).submitChat(any());
        when(protocol.findNextQueued("thread-1")).thenReturn(turn);
        when(protocol.tryStart("turn-1")).thenReturn(new AiTurnProtocolService.StartClaim(turn, true));
        when(application.execute(turn)).thenReturn(completion);
    }

    @Test
    void waitsForCurrentTurnFutureBeforeSchedulingTheNextDrain() {
        queue.signal("thread-1");
        assertEquals(1, submitted.size());
        submitted.get(0).run();

        assertEquals(1, submitted.size());
        verify(application).execute(turn);
        verify(executor, never()).releaseTurn();

        completion.complete(true);
        assertEquals(2, submitted.size());
        verify(executor).releaseTurn();
    }

    @Test
    void leavesPersistedTurnQueuedWhenAllExecutionSlotsAreBusy() {
        when(executor.tryAcquireTurn()).thenReturn(false);
        queue.signal("thread-1");
        submitted.get(0).run();
        queue.signal("thread-1");

        assertEquals(2, submitted.size());
        verify(protocol, never()).tryStart(any());
        verify(application, never()).execute(any());
        verify(executor, never()).releaseTurn();
    }

    @Test
    void skipsUnclaimedTurnsAndReleasesTheirExecutionSlot() {
        when(protocol.tryStart("turn-1")).thenReturn(new AiTurnProtocolService.StartClaim(turn, false));
        queue.signal("thread-1");
        submitted.get(0).run();

        verify(executor).releaseTurn();
        verify(application, never()).execute(any());
        queue.signal("thread-1");
        assertEquals(2, submitted.size());
    }

    @ParameterizedTest
    @ValueSource(strings = {"lookup", "claim", "execute", "missing-future"})
    void startupFailuresReleaseTheScheduleAndAnyAcquiredSlot(String failure) {
        IllegalStateException error = new IllegalStateException("startup failed");
        switch (failure) {
            case "lookup" -> when(protocol.findNextQueued("thread-1")).thenThrow(error);
            case "claim" -> when(protocol.tryStart("turn-1")).thenThrow(error);
            case "execute" -> when(application.execute(turn)).thenThrow(error);
            case "missing-future" -> when(application.execute(turn)).thenReturn(null);
            default -> throw new IllegalArgumentException(failure);
        }
        queue.signal("thread-1");

        assertThrows(RuntimeException.class, () -> submitted.get(0).run());
        queue.signal("thread-1");

        assertEquals(2, submitted.size());
        verify(executor, times("lookup".equals(failure) ? 0 : 1)).releaseTurn();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void releasesLocalScheduleWhenTurnIsRequeuedOrFails(boolean failed) {
        queue.signal("thread-1");
        submitted.get(0).run();
        if (failed) completion.completeExceptionally(new IllegalStateException("boom"));
        else completion.complete(false);
        queue.signal("thread-1");

        assertEquals(2, submitted.size());
        verify(executor).releaseTurn();
    }

    @ParameterizedTest
    @CsvSource({"false,queued,true", "false,running,true", "true,queued,true", "true,running,false",
            "true,completed,false", "true,failed,false", "true,interrupted,false"})
    void signalsNewReservationsButOnlyResignalsQueuedReuses(boolean reused, String status, boolean signal) {
        var snapshot = mock(AiTurnProtocolService.TurnSnapshot.class);
        when(snapshot.status()).thenReturn(status);
        queue.signalPersistedTurn("thread-1", new AiTurnProtocolService.Reservation(snapshot, reused));
        verify(executor, times(signal ? 1 : 0)).submitChat(any());
    }

    @Test
    void defersPersistedTurnsOnBackpressureWithoutChangingStrictSignalsOrBlockingRecovery() {
        doThrow(new RejectedExecutionException("fixture busy"))
                .doThrow(new RejectedExecutionException("fixture busy"))
                .doAnswer(this::captureTask).when(executor).submitChat(any());
        assertThrows(RejectedExecutionException.class, () -> queue.signal("thread-1"));
        assertDoesNotThrow(() -> queue.signalPersistedTurn("thread-1",
                new AiTurnProtocolService.Reservation(turn, false)));
        when(protocol.listDispatchableThreadIds()).thenReturn(List.of("thread-1"));
        queue.recoverQueuedTurns();
        verify(executor, times(3)).submitChat(any());
    }

    @Test
    void recoveryContinuesAfterBackpressureAndRetriesRejectedThreads() {
        when(protocol.listDispatchableThreadIds()).thenReturn(List.of("thread-1", "thread-2"));
        doThrow(new RejectedExecutionException("fixture busy"))
                .doAnswer(this::captureTask).when(executor).submitChat(any());

        assertDoesNotThrow(queue::recoverQueuedTurns);
        assertEquals(1, submitted.size());
        queue.recoverQueuedTurns();
        assertEquals(2, submitted.size());
        verify(executor, times(3)).submitChat(any());
    }

    @Test
    void rejectedContinuationReleasesTheScheduleForRecovery() {
        queue.signal("thread-1");
        submitted.get(0).run();
        doThrow(new RejectedExecutionException("fixture busy")).when(executor).submitChat(any());

        completion.complete(true);

        doAnswer(this::captureTask).when(executor).submitChat(any());
        queue.signal("thread-1");
        assertEquals(2, submitted.size());
        verify(executor).releaseTurn();
    }

    @Test
    void idleRecheckFailureKeepsANewerScheduleReserved() {
        when(protocol.findNextQueued("thread-1")).thenReturn(null).thenAnswer(invocation -> {
            queue.signal("thread-1");
            throw new IllegalStateException("recheck failed");
        });
        queue.signal("thread-1");

        assertThrows(IllegalStateException.class, () -> submitted.get(0).run());
        queue.signal("thread-1");

        assertEquals(2, submitted.size());
        verify(executor, never()).tryAcquireTurn();
    }

    private CompletableFuture<Void> captureTask(InvocationOnMock invocation) {
        submitted.add(invocation.getArgument(0));
        return CompletableFuture.completedFuture(null);
    }
}
