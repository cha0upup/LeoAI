package org.leo.web.dto.puppetnode.ai;

public record AiGrantRequest(String sessionId, String threadId, String toolType, Boolean grantAll) {
}
