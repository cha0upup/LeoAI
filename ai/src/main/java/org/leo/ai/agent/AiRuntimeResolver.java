package org.leo.ai.agent;

import org.leo.ai.platform.PlatformAiStateStore;
import org.leo.core.ai.AiRuntimeState;
import org.leo.core.session.AgentNodeSession;
import org.leo.core.session.AgentNodeSessionContainer;
import org.springframework.stereotype.Component;

/** 将 Platform/Agent memoryId 统一解析为运行时状态。 */
@Component
public class AiRuntimeResolver {

    public AiRuntimeState resolve(
            AiToolAuthorizationPolicy.AiAgentScope scope, Object memoryId) {
        String value = memoryId != null ? String.valueOf(memoryId) : null;
        if (value == null || value.isBlank()) return null;
        if (scope == AiToolAuthorizationPolicy.AiAgentScope.PLATFORM) {
            return PlatformAiStateStore.get(value);
        }
        AgentNodeSession session = resolveAgentSession(value);
        if (session == null) return null;
        String threadId = threadId(value);
        return threadId != null ? session.getAiThread(threadId) : session.getActiveThread();
    }

    public AiRuntimeState resolveCurrent() {
        String sessionId = AiToolContext.getSessionId();
        if (sessionId == null || sessionId.isBlank()) return null;
        String threadId = AiToolContext.getThreadId();
        if (threadId == null || threadId.isBlank()) {
            return PlatformAiStateStore.get(sessionId);
        }
        AgentNodeSession session = AgentNodeSessionContainer.getSession(sessionId);
        return session != null ? session.getAiThread(threadId) : null;
    }

    public AgentNodeSession resolveAgentSession(Object memoryId) {
        if (memoryId == null) return null;
        String value = String.valueOf(memoryId);
        int separator = value.indexOf(':');
        String sessionId = separator > 0 ? value.substring(0, separator) : value;
        return AgentNodeSessionContainer.getSession(sessionId);
    }

    private static String threadId(String memoryId) {
        int separator = memoryId.indexOf(':');
        return separator > 0 && separator < memoryId.length() - 1
                ? memoryId.substring(separator + 1) : null;
    }
}
