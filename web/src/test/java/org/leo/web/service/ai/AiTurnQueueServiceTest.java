package org.leo.web.service.ai;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiTurnQueueServiceTest {

    private final AiTurnProtocolService protocol = mock(AiTurnProtocolService.class);
    private final AiSseExecutor executor = mock(AiSseExecutor.class);
    private final AiTurnApplicationService application = mock(AiTurnApplicationService.class);
    private final List<Runnable> submitted = new ArrayList<>();
    private final CompletableFuture<Boolean> completion = new CompletableFuture<>();
    private final AiTurnProtocolService.TurnSnapshot turn = new AiTurnProtocolService.TurnSnapshot(
            "turn-1", "thread-1", "running", "client-1", "user-1", "assistant-1", 1L, 2L, null,
            false, null, AiTurnCommandPayload.SCOPE_PLATFORM, "{}", null);
    private final AiTurnQueueService queue = new AiTurnQueueService(protocol, executor, application);

    @BeforeEach
    void captureScheduledTasksAndClaimTheQueuedTurn() {
        when(executor.submitChat(any())).thenAnswer(invocation -> {
            submitted.add(invocation.getArgument(0));
            return CompletableFuture.completedFuture(null);
        });
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

        completion.complete(true);
        assertEquals(2, submitted.size());
    }

    @Test
    void releasesLocalScheduleWhenTurnIsRequeued() {
        completion.complete(false);
        queue.signal("thread-1");
        submitted.get(0).run();
        queue.signal("thread-1");

        assertEquals(2, submitted.size());
    }

    @Test
    void releasesLocalScheduleWhenTurnFutureFails() {
        queue.signal("thread-1");
        submitted.get(0).run();
        completion.completeExceptionally(new IllegalStateException("boom"));
        queue.signal("thread-1");

        assertEquals(2, submitted.size());
    }
}
