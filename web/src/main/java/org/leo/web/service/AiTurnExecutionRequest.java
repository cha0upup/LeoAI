package org.leo.web.service;

import org.leo.core.entity.AiChatAuditEntry;

/** Prepared turn input shared by platform and node execution adapters. */
public record AiTurnExecutionRequest(
        String sessionId,
        String userMessage,
        String messageForAgent,
        String reasoningEffort,
        Object attachments,
        String turnId,
        String userItemId,
        String assistantItemId,
        AiChatAuditEntry audit,
        long startMs) {

    public static AiTurnExecutionRequest from(AiTurnProtocolService.TurnSnapshot turn,
                                              AiTurnCommandPayload command,
                                              String messageForAgent,
                                              AiChatAuditEntry audit, long startMs) {
        return new AiTurnExecutionRequest(command.getSessionId(), command.getUserMessage(),
                messageForAgent, command.getReasoningEffort(), command.getAttachments(),
                turn.id(), turn.userItemId(), turn.assistantItemId(), audit, startMs);
    }
}
