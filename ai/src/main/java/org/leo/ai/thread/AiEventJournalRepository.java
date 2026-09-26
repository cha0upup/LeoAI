package org.leo.ai.thread;

import com.alibaba.fastjson.JSON;
import org.leo.core.ai.AiEventStreamRuntime;
import org.leo.core.entity.AiEventRecord;
import org.leo.core.entity.AiSseEvent;
import org.leo.dao.mapper.AiConversationMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/** Persistence boundary for replayable AI event streams. */
@Repository
public class AiEventJournalRepository {

    private final AiConversationMapper mapper;

    public AiEventJournalRepository(AiConversationMapper mapper) {
        this.mapper = mapper;
    }

    public void attach(String threadId, AiEventStreamRuntime runtime) {
        if (blank(threadId) || runtime == null) return;
        long lastSeq = mapper.findLastEventSeq(threadId);
        runtime.configureEventJournal(lastSeq,
                event -> append(threadId, event, runtime.getActiveLeaseToken()));
    }

    public void append(String threadId, AiSseEvent event, String leaseToken) {
        if (blank(threadId) || event == null || event.seq() <= 0L) return;
        String eventId = UUID.randomUUID().toString();
        int inserted = mapper.insertEvent(
                eventId,
                emptyToNull(event.runId()),
                threadId,
                emptyToNull(event.turnId()),
                emptyToNull(event.itemId()),
                emptyToNull(event.subagentInvocationId()),
                event.seq(),
                event.timestamp(),
                event.name(),
                JSON.toJSONString(event.data()),
                emptyToNull(leaseToken),
                System.currentTimeMillis());
        if (!blank(leaseToken) && inserted != 1) {
            throw fenced("事件日志", eventId);
        }
    }

    public List<AiSseEvent> listAfter(String threadId, long afterSeq, int limit) {
        if (blank(threadId)) return List.of();
        int safeLimit = Math.max(1, Math.min(limit > 0 ? limit : 200, 2000));
        return mapper.listEventsAfter(threadId, Math.max(0L, afterSeq), safeLimit)
                .stream().map(this::toSseEvent).toList();
    }

    public List<AiSseEvent> listByRun(String runId) {
        if (blank(runId)) return List.of();
        return mapper.listEventsByRun(runId).stream().map(this::toSseEvent).toList();
    }

    public long lastSequence(String threadId) {
        return blank(threadId) ? 0L : mapper.findLastEventSeq(threadId);
    }

    public boolean hasTurnCompleted(String threadId, String turnId) {
        return blank(turnId) || mapper.countTurnCompletedEvents(threadId, turnId) > 0;
    }

    public long latestTurnStartSequence(String threadId) {
        return blank(threadId) ? 0L : mapper.findLatestTurnStartSeq(threadId);
    }

    public boolean hasLatestTurnCompleted(String threadId) {
        return blank(threadId) || mapper.hasLatestTurnCompletedEvent(threadId) > 0;
    }

    private AiSseEvent toSseEvent(AiEventRecord row) {
        Object data = row.getDataJson() != null ? JSON.parse(row.getDataJson()) : null;
        return new AiSseEvent(
                row.getEventSeq() != null ? row.getEventSeq() : 0L,
                row.getTimestamp() != null ? row.getTimestamp() : 0L,
                row.getName(), data, row.getSubagentInvocationId(),
                row.getTurnId(), row.getItemId(), row.getRunId());
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String emptyToNull(String value) {
        return blank(value) ? null : value;
    }

    private static IllegalStateException fenced(String target, String id) {
        return new IllegalStateException("执行租约已失效，拒绝写入过期" + target + ": " + id);
    }
}
