package org.leo.web.dto.puppetnode.ai;

public record AiThreadEventsRequest(String sessionId, String threadId, Long afterSeq, Integer limit) {
}
