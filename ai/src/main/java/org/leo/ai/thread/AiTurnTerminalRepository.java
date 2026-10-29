package org.leo.ai.thread;

import org.leo.core.ai.AiRunStatus;
import org.leo.core.entity.AiMessageRecord;
import org.leo.core.entity.AiRunRecord;
import org.leo.dao.mapper.AiConversationMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

/** Persistence boundary for terminal Turn and Run state transitions. */
@Repository
public class AiTurnTerminalRepository {

    private final AiConversationMapper mapper;

    public AiTurnTerminalRepository(AiConversationMapper mapper) {
        this.mapper = mapper;
    }

    public void complete(AiConversationStoreService.PersistedTurn turn,
                         String output, List<Object> nodes,
                         Map<String, Object> review, Object planSnapshot,
                         int toolCallCount) {
        if (turn == null) return;
        updateAssistantMessage(turn, AiConversationStoreService.MESSAGE_COMMITTED,
                output, null, nodes, review, planSnapshot);
        updateTurnMessageStatus(turn, AiConversationStoreService.MESSAGE_COMMITTED);
        finishTurn(turn, AiConversationStoreService.MESSAGE_COMMITTED);
        finishRun(turn.runId(), AiRunStatus.COMPLETED, turn.startedAt(), output,
                null, null, null, toolCallCount, turn.leaseToken());
    }

    public void discard(AiConversationStoreService.PersistedTurn turn,
                        String runStatus, String errorCategory,
                        String errorMessage, String rawErrorMessage,
                        int toolCallCount, String partialOutput,
                        List<Object> partialNodes, Object planSnapshot, String recoveryContext) {
        if (turn == null) return;
        updateAssistantMessage(turn, AiConversationStoreService.MESSAGE_DISCARDED,
                partialOutput, recoveryContext, partialNodes, null, planSnapshot);
        updateTurnMessageStatus(turn, AiConversationStoreService.MESSAGE_DISCARDED);
        finishTurn(turn, AiConversationStoreService.MESSAGE_DISCARDED);
        finishRun(turn.runId(), runStatus, turn.startedAt(), null, errorCategory,
                errorMessage, rawErrorMessage, toolCallCount, turn.leaseToken());
    }

    private void updateAssistantMessage(AiConversationStoreService.PersistedTurn turn,
                                         String status, String content, String recoveryContext,
                                         List<Object> nodes,
                                         Map<String, Object> review,
                                         Object planSnapshot) {
        AiMessageRecord assistant = new AiMessageRecord();
        assistant.setMessageId(turn.assistantMessageId());
        assistant.setStatus(status);
        assistant.setContent(content != null ? content : "");
        assistant.setRecoveryContext(emptyToNull(recoveryContext));
        assistant.setNodesJson(AiMessageRepository.toJsonOrNull(nodes));
        assistant.setReviewJson(AiMessageRepository.toJsonOrNull(review));
        assistant.setPlanJson(AiMessageRepository.toJsonOrNull(planSnapshot));
        if (blank(turn.leaseToken())) {
            mapper.updateMessage(assistant);
            return;
        }
        requireFencedWrite(mapper.updateMessageFenced(
                        assistant.getMessageId(), assistant.getStatus(),
                        assistant.getContent(), assistant.getRecoveryContext(), assistant.getNodesJson(),
                        assistant.getReviewJson(), assistant.getPlanJson(),
                        turn.leaseToken(), System.currentTimeMillis()),
                "Assistant 消息", assistant.getMessageId());
    }

    private void updateTurnMessageStatus(AiConversationStoreService.PersistedTurn turn,
                                         String status) {
        if (blank(turn.leaseToken())) {
            mapper.updateTurnMessageStatus(turn.threadId(), turn.turnId(), status);
            return;
        }
        requireFencedRows(mapper.updateTurnMessageStatusFenced(
                        turn.threadId(), turn.turnId(), status,
                        turn.leaseToken(), System.currentTimeMillis()),
                "Turn 消息状态", turn.turnId());
    }

    private void finishTurn(AiConversationStoreService.PersistedTurn turn, String status) {
        int updated = mapper.finishTurn(
                turn.turnId(), status, System.currentTimeMillis(),
                emptyToNull(turn.leaseToken()));
        requireTerminalWrite(updated, turn.leaseToken(), "Turn", turn.turnId());
    }

    private void finishRun(String runId, String status, long startedAt, String output,
                           String errorCategory, String errorMessage,
                           String rawErrorMessage, int toolCallCount,
                           String leaseToken) {
        AiRunRecord row = new AiRunRecord();
        row.setRunId(runId);
        row.setStatus(status);
        long finishedAt = System.currentTimeMillis();
        row.setFinishedAt(finishedAt);
        row.setDurationMs(Math.max(0L, finishedAt - startedAt));
        row.setOutput(output);
        row.setErrorCategory(errorCategory);
        row.setErrorMessage(errorMessage);
        row.setRawErrorMessage(rawErrorMessage);
        row.setToolCallCount(toolCallCount);
        row.setLeaseToken(emptyToNull(leaseToken));
        int updated = mapper.finishRun(row);
        requireTerminalWrite(updated, leaseToken, "Run", runId);
    }

    private static void requireFencedWrite(int updated, String target, String id) {
        if (updated != 1) {
            throw new IllegalStateException("执行租约已失效，拒绝写入过期" + target + ": " + id);
        }
    }

    private static void requireFencedRows(int updated, String target, String id) {
        if (updated < 1) {
            throw new IllegalStateException("执行租约已失效，拒绝写入过期" + target + ": " + id);
        }
    }

    private static void requireTerminalWrite(int updated, String leaseToken,
                                             String target, String id) {
        if (updated == 1) return;
        String reason = blank(leaseToken)
                ? "终态已由其他执行者确定"
                : "执行租约已失效或终态已由其他执行者确定";
        throw new IllegalStateException(reason + "，拒绝重复终结" + target + ": " + id);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String emptyToNull(String value) {
        return blank(value) ? null : value;
    }
}
