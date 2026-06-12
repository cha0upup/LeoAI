package org.leo.web.dto.puppetnode.ai;

public record AiPlanPreApproveRequest(String sessionId, String threadId, Integer stepIndex) {
}
