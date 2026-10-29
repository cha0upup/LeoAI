package org.leo.ai.thread;

import org.leo.ai.runtime.AiTurnRecoveryContext;
import org.leo.core.entity.AiOrphanedRunRecord;
import org.leo.core.entity.AiSseEvent;
import org.leo.core.entity.AiThreadLeaseRecord;
import org.leo.dao.mapper.AiConversationMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Persistence boundary for cross-instance execution fencing and recovery. */
@Repository
public class AiExecutionLeaseRepository {

    private final AiConversationMapper mapper;
    private final AiEventJournalRepository eventJournal;

    public AiExecutionLeaseRepository(AiConversationMapper mapper,
                                      AiEventJournalRepository eventJournal) {
        this.mapper = mapper;
        this.eventJournal = eventJournal;
    }

    public boolean acquire(AiThreadLeaseRecord lease) {
        return lease != null && mapper.acquireThreadLease(lease) == 1;
    }

    public boolean renew(AiThreadLeaseRecord lease) {
        return lease != null && mapper.renewThreadLease(lease) == 1;
    }

    public boolean release(AiThreadLeaseRecord lease) {
        return lease != null && mapper.releaseThreadLease(lease) == 1;
    }

    public List<AiThreadLeaseRecord> listExpired(long now) {
        return mapper.listExpiredThreadLeases(now);
    }

    public List<String> listStuckThreads(long now) {
        return mapper.listThreadsWithStuckRunningTurns(now);
    }

    public boolean claimExpired(AiThreadLeaseRecord expired, AiThreadLeaseRecord recovery) {
        if (expired == null || recovery == null) return false;
        return mapper.claimExpiredThreadLease(
                expired.getThreadId(), expired.getLeaseToken(),
                recovery.getOwnerId(), recovery.getLeaseToken(),
                recovery.getAcquiredAt(), recovery.getHeartbeatAt(),
                recovery.getExpiresAt()) == 1;
    }

    /** Atomically closes running runs left behind by a lost execution instance. */
    @Transactional
    public List<AiSseEvent> recoverOrphanedRuns(String threadId, long finishedAt) {
        if (blank(threadId)) return List.of();
        String message = "执行实例心跳超时，任务已自动收口";
        List<AiSseEvent> completedEvents = new ArrayList<>();
        for (AiOrphanedRunRecord run : mapper.listRunningRuns(threadId)) {
            if (mapper.failOrphanedRun(run.getRunId(), finishedAt, message) != 1) continue;
            persistRecoveryContext(run);
            mapper.discardRunMessages(run.getRunId());
            mapper.discardOrphanedTurn(run.getTurnId(), finishedAt);
            mapper.failOrphanedThread(threadId, finishedAt);
            if (eventJournal.hasTurnCompleted(threadId, run.getTurnId())) continue;

            Map<String, Object> error = new LinkedHashMap<>();
            error.put("message", message);
            error.put("category", "orphaned");
            Map<String, Object> turn = new LinkedHashMap<>();
            turn.put("id", run.getTurnId());
            turn.put("threadId", threadId);
            turn.put("status", "failed");
            turn.put("error", error);
            AiSseEvent event = new AiSseEvent(
                    eventJournal.lastSequence(threadId) + 1L,
                    finishedAt,
                    "turn/completed",
                    Map.of("turn", turn), null, run.getTurnId(),
                    run.getAssistantMessageId(), run.getRunId());
            eventJournal.append(threadId, event, null);
            completedEvents.add(event);
        }
        return completedEvents;
    }

    private void persistRecoveryContext(AiOrphanedRunRecord run) {
        if (run == null || blank(run.getAssistantMessageId())) return;
        List<AiSseEvent> events = eventJournal.listByRun(run.getRunId());
        String recoveryContext = AiTurnRecoveryContext.build(events, null);
        if (recoveryContext.isBlank()) return;
        mapper.updateMessageRecovery(run.getAssistantMessageId(),
                AiTurnRecoveryContext.visibleOutput(events), recoveryContext);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
