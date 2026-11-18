package org.leo.ai.thread;

import org.leo.core.entity.AiTurnRecord;
import org.leo.dao.mapper.AiConversationMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

/** Persistence boundary for protocol Turn state transitions and dispatch queries. */
@Repository
public class AiProtocolTurnRepository {

    private final AiConversationMapper mapper;

    public AiProtocolTurnRepository(AiConversationMapper mapper) {
        this.mapper = mapper;
    }

    public AiTurnRecord findByClientId(String threadId, String clientUserMessageId) {
        return blank(threadId) || blank(clientUserMessageId)
                ? null : mapper.findTurnByClientMessage(
                        threadId, clientUserMessageId.trim());
    }

    public AiTurnRecord find(String turnId) {
        return blank(turnId) ? null : mapper.findTurnById(turnId);
    }

    public String findNextQueuedTurnId(String threadId) {
        return blank(threadId) ? null : mapper.findNextQueuedTurnId(threadId, System.currentTimeMillis());
    }

    public List<AiTurnRecord> listInProgress(String threadId) {
        return blank(threadId) ? List.of() : mapper.listInProgressTurns(threadId);
    }

    public List<String> listDispatchableThreadIds() {
        long now = System.currentTimeMillis();
        mapper.expireAllUserInputRequests(now);
        return mapper.listDispatchableThreadIds(now);
    }

    public AiTurnRecord claimStart(String turnId, long startedAt) {
        return blank(turnId) ? null : mapper.claimProtocolTurnStart(turnId, startedAt);
    }

    public boolean hasInterruptRequest(String threadId) {
        return !blank(threadId) && mapper.countInterruptRequestedTurns(threadId) > 0;
    }

    public AiTurnRecord complete(String turnId, String protocolStatus,
                                 String errorMessage, long completedAt,
                                 String leaseToken) {
        if (blank(leaseToken)) {
            AiTurnRecord row = new AiTurnRecord();
            row.setTurnId(turnId);
            row.setProtocolStatus(protocolStatus);
            row.setErrorMessage(emptyToNull(errorMessage));
            row.setCompletedAt(completedAt);
            mapper.completeProtocolTurn(row);
        } else if (mapper.completeProtocolTurnFenced(
                turnId, protocolStatus, emptyToNull(errorMessage),
                completedAt, leaseToken) != 1) {
            throw new IllegalStateException(
                    "执行租约已失效，拒绝写入过期协议 Turn 终结: " + turnId);
        }
        return find(turnId);
    }

    public AiTurnRecord requeue(String turnId) {
        if (!blank(turnId)) mapper.requeueProtocolTurn(turnId);
        return find(turnId);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String emptyToNull(String value) {
        return blank(value) ? null : value;
    }
}
