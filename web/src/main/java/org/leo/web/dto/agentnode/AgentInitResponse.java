package org.leo.web.dto.agentnode;

import java.util.List;

public record AgentInitResponse(String sessionId, String projectId,
                                 boolean cacheMode, List<String> capabilities) {
}
