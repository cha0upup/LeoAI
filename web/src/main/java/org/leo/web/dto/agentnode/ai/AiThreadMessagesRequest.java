package org.leo.web.dto.agentnode.ai;

public record AiThreadMessagesRequest(String sessionId, String threadId, Integer offset, Integer limit) {
}
