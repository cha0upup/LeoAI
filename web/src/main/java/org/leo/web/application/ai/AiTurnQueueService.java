package org.leo.web.application.ai;

import org.leo.web.infrastructure.concurrent.AiExecutionExecutor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;

/** 非阻塞数据库 Turn 调度器；每个线程最多占用一个执行任务。 */
@Service
public class AiTurnQueueService {

    private final AiTurnProtocolService protocol;
    private final AiExecutionExecutor executor;
    private final AiTurnApplicationService application;
    private final Set<String> scheduledThreads = ConcurrentHashMap.newKeySet();

    public AiTurnQueueService(AiTurnProtocolService protocol,
                              AiExecutionExecutor executor,
                              AiTurnApplicationService application) {
        this.protocol = protocol;
        this.executor = executor;
        this.application = application;
    }

    public void signal(String threadId) {
        if (threadId == null || threadId.isBlank()
                || !scheduledThreads.add(threadId)) {
            return;
        }
        submitDrain(threadId);
    }

    private void submitDrain(String threadId) {
        try {
            executor.submitChat(() -> drain(threadId));
        } catch (RejectedExecutionException error) {
            scheduledThreads.remove(threadId);
            throw error;
        }
    }

    /** Wake a persisted turn without turning executor backpressure into an HTTP failure. */
    public void signalPersistedTurn(String threadId, AiTurnProtocolService.Reservation reservation) {
        if (reservation.reused() && !AiTurnProtocolService.STATUS_QUEUED.equals(reservation.turn().status())) {
            return;
        }
        trySignal(threadId);
    }

    @Scheduled(fixedDelayString = "${leo.ai.turn-dispatch-ms:2000}",
            initialDelayString = "${leo.ai.turn-dispatch-ms:2000}")
    public void recoverQueuedTurns() {
        for (String threadId : protocol.listDispatchableThreadIds()) {
            trySignal(threadId);
        }
    }

    private void trySignal(String threadId) {
        try {
            signal(threadId);
        } catch (RejectedExecutionException ignored) {
            // The command is already durable; the periodic scheduler will retry it.
        }
    }

    private void drain(String threadId) {
        String nextTurnId;
        try {
            nextTurnId = protocol.findNextQueuedTurnId(threadId);
        } catch (RuntimeException error) {
            scheduledThreads.remove(threadId);
            throw error;
        }
        if (nextTurnId == null) {
            releaseAndResignal(threadId);
            return;
        }
        if (!executor.tryAcquireTurn()) {
            scheduledThreads.remove(threadId);
            return;
        }
        CompletableFuture<Boolean> execution;
        try {
            AiTurnProtocolService.StartClaim claim = protocol.tryStart(nextTurnId);
            execution = claim.claimed()
                    ? Objects.requireNonNull(application.execute(claim.turn()), "execution")
                    : CompletableFuture.completedFuture(true);
        } catch (RuntimeException error) {
            executor.releaseTurn();
            scheduledThreads.remove(threadId);
            throw error;
        }
        execution.whenComplete((terminal, error) -> {
            executor.releaseTurn();
            if (error != null || !Boolean.TRUE.equals(terminal)) {
                scheduledThreads.remove(threadId);
                return;
            }
            submitDrain(threadId);
        });
    }

    private void releaseAndResignal(String threadId) {
        scheduledThreads.remove(threadId);
        if (protocol.findNextQueuedTurnId(threadId) != null) signal(threadId);
    }
}
