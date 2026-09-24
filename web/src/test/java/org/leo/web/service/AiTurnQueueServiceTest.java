package org.leo.web.service;

import org.junit.jupiter.api.Test;
import org.leo.web.util.AiSseExecutor;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiTurnQueueServiceTest {

    @Test
    void waitsForCurrentTurnFutureBeforeSchedulingTheNextDrain() {
        AiTurnProtocolService protocol = mock(AiTurnProtocolService.class);
        AiSseExecutor executor = mock(AiSseExecutor.class);
        AiTurnApplicationService application = mock(AiTurnApplicationService.class);
        List<Runnable> submitted = captureTasks(executor);
        AiTurnProtocolService.TurnSnapshot turn = turn("turn-1");
        CompletableFuture<Boolean> completion = new CompletableFuture<>();
        when(protocol.findNextQueued("thread-1")).thenReturn(turn);
        when(protocol.tryStart("turn-1"))
                .thenReturn(new AiTurnProtocolService.StartClaim(turn, true));
        when(application.execute(turn)).thenReturn(completion);
        AiTurnQueueService queue = new AiTurnQueueService(protocol, executor, application);

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
        AiTurnProtocolService protocol = mock(AiTurnProtocolService.class);
        AiSseExecutor executor = mock(AiSseExecutor.class);
        AiTurnApplicationService application = mock(AiTurnApplicationService.class);
        List<Runnable> submitted = captureTasks(executor);
        AiTurnProtocolService.TurnSnapshot turn = turn("turn-1");
        when(protocol.findNextQueued("thread-1")).thenReturn(turn);
        when(protocol.tryStart("turn-1"))
                .thenReturn(new AiTurnProtocolService.StartClaim(turn, true));
        when(application.execute(turn))
                .thenReturn(CompletableFuture.completedFuture(false));
        AiTurnQueueService queue = new AiTurnQueueService(protocol, executor, application);

        queue.signal("thread-1");
        submitted.get(0).run();
        queue.signal("thread-1");

        assertEquals(2, submitted.size());
    }

    @Test
    void releasesLocalScheduleWhenTurnFutureFails() {
        AiTurnProtocolService protocol = mock(AiTurnProtocolService.class);
        AiSseExecutor executor = mock(AiSseExecutor.class);
        AiTurnApplicationService application = mock(AiTurnApplicationService.class);
        List<Runnable> submitted = captureTasks(executor);
        AiTurnProtocolService.TurnSnapshot turn = turn("turn-1");
        CompletableFuture<Boolean> completion = new CompletableFuture<>();
        when(protocol.findNextQueued("thread-1")).thenReturn(turn);
        when(protocol.tryStart("turn-1"))
                .thenReturn(new AiTurnProtocolService.StartClaim(turn, true));
        when(application.execute(turn)).thenReturn(completion);
        AiTurnQueueService queue = new AiTurnQueueService(protocol, executor, application);

        queue.signal("thread-1");
        submitted.get(0).run();
        completion.completeExceptionally(new IllegalStateException("boom"));
        queue.signal("thread-1");

        assertEquals(2, submitted.size());
    }

    private static List<Runnable> captureTasks(AiSseExecutor executor) {
        List<Runnable> submitted = new ArrayList<>();
        when(executor.submitChat(any())).thenAnswer(invocation -> {
            submitted.add(invocation.getArgument(0));
            return CompletableFuture.completedFuture(null);
        });
        return submitted;
    }

    private static AiTurnProtocolService.TurnSnapshot turn(String id) {
        return new AiTurnProtocolService.TurnSnapshot(
                id, "thread-1", "running", "client-1",
                "user-1", "assistant-1", 1L, 2L, null,
                false, null, AiTurnCommandPayload.SCOPE_PLATFORM, "{}", null);
    }
}
