package org.leo.web.dto.platform.ai;

import org.leo.core.entity.AiChatAuditEntry;

import java.util.List;

public final class PlatformAiDtos {

    private PlatformAiDtos() {
    }

    public record ChatRequest(String message,
                              Integer configId) {
    }

    public record AgentConfigRequest(Integer configId, String mode) {
        public AgentConfigRequest(Integer configId) {
            this(configId, null);
        }
    }

    public record SwitchModeRequest(String mode) {
    }

    public record AgentInfoResponse(int grantedTypesCount) {
    }

    public record ConfirmRequest(String callId, Boolean approved) {
    }

    public record GrantRequest(String toolType, Boolean grantAll) {
    }

    public record EventsRequest(Long afterSeq, Integer limit) {
    }

    public record ThreadIdRequest(String threadId) {
    }

    public record CreateThreadRequest(String title, Integer configId) {
    }

    public record ThreadRenameRequest(String threadId, String title) {
    }

    public record MessagesRequest(Integer offset, Integer limit) {
    }

    public record AuditLogsRequest(Integer limit) {
    }

    public record AuditLogsResponse(int total, int returned, List<AiChatAuditEntry> logs) {
    }
}
