package org.leo.web.service.ai;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;

/** 非阻塞数据库 Turn 调度器；每个线程最多占用一个执行任务。 */
@Service
public class AiTurnQueueService {

    private final AiTurnProtocolService protocol;
    private final AiSseExecutor executor;
    private final AiTurnApplicationService application;
    private final Set<String> scheduledThreads = ConcurrentHashMap.newKeySet();

    public AiTurnQueueService(AiTurnProtocolService protocol,
                              AiSseExecutor executor,
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
        try {
            executor.submitChat(() -> drain(threadId));
        } catch (RejectedExecutionException error) {
            scheduledThreads.remove(threadId);
            throw error;
        }
    }

    @Scheduled(fixedDelayString = "${leo.ai.turn-dispatch-ms:2000}",
            initialDelayString = "${leo.ai.turn-dispatch-ms:2000}")
    public void recoverQueuedTurns() {
        for (String threadId : protocol.listDispatchableThreadIds()) {
            signal(threadId);
        }
    }

    private void drain(String threadId) {
        AiTurnProtocolService.TurnSnapshot next =
                protocol.findNextQueued(threadId);
        if (next == null) {
            releaseAndResignal(threadId);
            return;
        }
        AiTurnProtocolService.StartClaim claim =
                protocol.tryStart(next.id());
        if (!claim.claimed()) {
            releaseAndResignal(threadId);
            return;
        }
        try {
            application.execute(claim.turn()).whenComplete((terminal, error) -> {
                if (error != null || !Boolean.TRUE.equals(terminal)) {
                    scheduledThreads.remove(threadId);
                    return;
                }
                try {
                    executor.submitChat(() -> drain(threadId));
                } catch (RejectedExecutionException rejected) {
                    scheduledThreads.remove(threadId);
                    throw rejected;
                }
            });
        } catch (RuntimeException error) {
            scheduledThreads.remove(threadId);
            throw error;
        }
    }

    private void releaseAndResignal(String threadId) {
        scheduledThreads.remove(threadId);
        if (protocol.findNextQueued(threadId) != null) signal(threadId);
    }
}
