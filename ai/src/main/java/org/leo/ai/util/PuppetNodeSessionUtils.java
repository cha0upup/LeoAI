package org.leo.ai.util;

import org.leo.core.entity.AiRuntimeStats;
import org.leo.core.puppet.AbstractPuppetNode;
import org.leo.core.puppet.impl.JavaPuppetNode;
import org.leo.core.session.PuppetNodeSession;
import org.leo.core.session.PuppetNodeSessionContainer;

public class PuppetNodeSessionUtils {

    public static PuppetNodeSession getSession(String sessionId) {
        PuppetNodeSession session = PuppetNodeSessionContainer.getSession(sessionId);
        if (session == null) {
            throw new IllegalArgumentException("会话不存在: " + sessionId);
        }
        return session;
    }

    public static JavaPuppetNode getJavaPuppetNode(String sessionId) {
        return getSession(sessionId).getJavaPuppetNode();
    }

    public static AbstractPuppetNode getPuppetNode(String sessionId) {
        return getSession(sessionId).getPuppetNode();
    }

    public static Object getAiContextValue(String sessionId, String key) {
        return getSession(sessionId).getAiContextValue(key);
    }

    public static void putAiContextValue(String sessionId, String key, Object value) {
        getSession(sessionId).putAiContextValue(key, value);
    }

    public static void removeAiContextValue(String sessionId, String key) {
        getSession(sessionId).removeAiContextValue(key);
    }

    public static void removeAiContextByPrefix(String sessionId, String prefix) {
        getSession(sessionId).removeAiContextByPrefix(prefix);
    }

    public static AiRuntimeStats getAiRuntimeStats(String sessionId) {
        return getSession(sessionId).getAiRuntimeStats();
    }
}
