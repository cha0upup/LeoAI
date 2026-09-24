package org.leo.web.dto.platform.ai;

import org.leo.core.entity.AiChatAuditEntry;
import org.leo.web.dto.ai.AiFileAttachment;

import java.util.List;

public final class PlatformAiDtos {

    private PlatformAiDtos() {
    }

    public record ChatRequest(String threadId,
                              String message,
                              Integer configId,
                              String reasoningEffort,
                              List<AiFileAttachment> attachments,
                              String clientUserMessageId,
                              String answerToQuestionId) {
    }

    public record AgentConfigRequest(String threadId, Integer configId) {
    }

    public record EventsRequest(String threadId, Long afterSeq, Integer limit) {
    }

    public record ThreadIdRequest(String threadId) {
    }

    public record TurnInterruptRequest(String threadId, String turnId) {
    }

    public record CreateThreadRequest(String title, Integer configId) {
    }

    public record ThreadRenameRequest(String threadId, String title) {
    }

    public record MessagesRequest(String threadId, Integer offset, Integer limit) {
    }

    public record AuditLogsRequest(Integer limit) {
    }

    public record AuditLogsResponse(int total, int returned, List<AiChatAuditEntry> logs) {
    }
}
