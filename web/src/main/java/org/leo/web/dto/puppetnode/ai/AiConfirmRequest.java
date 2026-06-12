package org.leo.web.dto.puppetnode.ai;

public record AiConfirmRequest(String sessionId, String threadId, String callId, Boolean approved) {
}
