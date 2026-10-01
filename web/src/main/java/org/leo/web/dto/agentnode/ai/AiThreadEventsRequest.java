package org.leo.web.dto.agentnode.ai;

public record AiThreadEventsRequest(String sessionId, String threadId, Long afterSeq, Integer limit) {
}
