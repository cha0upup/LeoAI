package org.leo.web.dto.puppetnode.ai;

public record AiThreadMessagesRequest(String sessionId, String threadId, Integer offset, Integer limit) {
}
