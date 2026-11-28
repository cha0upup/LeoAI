package org.leo.core.session;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * 会话容器管理类
 * 管理所有活跃的会话连接
 * 当前实现使用 ConcurrentHashMap，以支持并发访问场景
 *
 * @author LeoSpring
 * @version 2.1
 */
public final class AgentNodeSessionContainer {

    private AgentNodeSessionContainer() { }

    private static final Logger log = LoggerFactory.getLogger(AgentNodeSessionContainer.class);

    private static final Map<String, AgentNodeSession> sessionMap = new ConcurrentHashMap<>();

    /** 最大允许的并发会话数量。超出时自动驱逐最久未活跃的会话。 */
    private static final int MAX_SESSIONS = 500;

    /**
     * 会话销毁监听器：在会话被移除/驱逐时回调，参数为 sessionId。
     */
    private static final CopyOnWriteArrayList<Consumer<String>> destroyListeners =
            new CopyOnWriteArrayList<>();

    /** 注册会话销毁监听器。多个监听器按注册顺序触发；任一监听器异常不影响其他监听器。 */
    public static void registerDestroyListener(Consumer<String> listener) {
        if (listener != null) destroyListeners.addIfAbsent(listener);
    }

    public static void unregisterDestroyListener(Consumer<String> listener) {
        if (listener != null) destroyListeners.remove(listener);
    }

    private static void fireSessionDestroyed(String sessionId) {
        for (Consumer<String> listener : destroyListeners) {
            try { listener.accept(sessionId); } catch (Exception e) {
                log.warn("[AgentNodeSession] destroy listener 异常 sessionId={}", sessionId, e);
            }
        }
    }

    /**
     * 添加或替换会话。仅新增会话在达到上限时触发最久未活跃会话的驱逐。
     */
    public static void addSession(String sessionId, AgentNodeSession session) {
        if (!sessionMap.containsKey(sessionId)) evictIfOverCapacity();
        sessionMap.put(sessionId, session);
    }

    /**
     * 当会话数达到或超过 {@link #MAX_SESSIONS} 时，驱逐最久未活跃的 10% 会话腾出空间。
     */
    private static void evictIfOverCapacity() {
        if (sessionMap.size() < MAX_SESSIONS) {
            return;
        }
        int evictCount = Math.max(1, MAX_SESSIONS / 10);
        List<Map.Entry<String, AgentNodeSession>> sorted = new ArrayList<>(sessionMap.entrySet());
        sorted.sort(Comparator.comparingLong(e -> e.getValue().getLastActiveTime()));

        int evicted = 0;
        for (Map.Entry<String, AgentNodeSession> entry : sorted) {
            if (evicted >= evictCount) break;
            // 快照中的会话可能已被同 ID 的新实例替换。
            if (sessionMap.remove(entry.getKey(), entry.getValue())) {
                closeSession(entry.getKey(), entry.getValue());
                evicted++;
            }
        }
        if (evicted > 0) {
            log.warn("[AgentNodeSession] 容量保护：已驱逐 {} 个最久未活跃的会话（当前上限 {}）", evicted, MAX_SESSIONS);
        }
    }

    /**
     * 移除会话并释放底层资源
     */
    public static boolean removeSession(String sessionId) {
        AgentNodeSession session = sessionMap.remove(sessionId);
        if (session != null) {
            closeSession(sessionId, session);
        }
        return session != null;
    }

    private static void closeSession(String sessionId, AgentNodeSession session) {
        try { session.close(); } catch (Exception ignored) { }
        fireSessionDestroyed(sessionId);
    }

    /**
     * 获取会话
     */
    public static AgentNodeSession getSession(String sessionId) {
        return sessionMap.get(sessionId);
    }

    /**
     * 获取所有会话
     */
    public static Map<String, AgentNodeSession> getAllSessions() {
        return Map.copyOf(sessionMap);
    }

    /** 仅遍历会话时使用；列表与后续注册表变更独立，不复制会话对象。 */
    public static List<AgentNodeSession> snapshotSessions() {
        return List.copyOf(sessionMap.values());
    }

    /**
     * 清空所有会话
     */
    public static void clearAllSessions() {
        Map<String, AgentNodeSession> sessions = Map.copyOf(sessionMap);
        sessionMap.clear();
        sessions.forEach(AgentNodeSessionContainer::closeSession);
    }

}
