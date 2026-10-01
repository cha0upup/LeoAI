package org.leo.web.dto.agentnode.ai;

public record AiTurnInterruptRequest(String sessionId,
                                     String threadId,
                                     String turnId) {
}
