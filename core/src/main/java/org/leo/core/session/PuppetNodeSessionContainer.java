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
public class PuppetNodeSessionContainer {

    private static final Logger log = LoggerFactory.getLogger(PuppetNodeSessionContainer.class);

    private static final Map<String, PuppetNodeSession> sessionMap = new ConcurrentHashMap<>();

    /** 最大允许的并发会话数量。超出时自动驱逐最久未活跃的会话。 */
    private static final int MAX_SESSIONS = 500;

    /**
     * 会话销毁监听器：在会话被移除/驱逐时回调，参数为 sessionId。
     * 用于跨模块的会话级资源清理（如 SubAgentDispatchTools 的静态映射）。
     */
    private static final List<Consumer<String>> destroyListeners = new CopyOnWriteArrayList<>();

    /** 注册会话销毁监听器。多个监听器按注册顺序触发；任一监听器异常不影响其他监听器。 */
    public static void registerDestroyListener(Consumer<String> listener) {
        if (listener != null) destroyListeners.add(listener);
    }

    private static void fireSessionDestroyed(String sessionId) {
        for (Consumer<String> listener : destroyListeners) {
            try { listener.accept(sessionId); } catch (Exception e) {
                log.warn("[PuppetNodeSession] destroy listener 异常 sessionId={}", sessionId, e);
            }
        }
    }

    /**
     * 添加会话。若当前会话数已达上限，先驱逐最久未活跃的会话再添加。
     */
    public static boolean addSession(String sessionId, PuppetNodeSession session) {
        evictIfOverCapacity();
        sessionMap.put(sessionId, session);
        return true;
    }

    /**
     * 当会话数达到或超过 {@link #MAX_SESSIONS} 时，驱逐最久未活跃的 10% 会话腾出空间。
     */
    private static void evictIfOverCapacity() {
        if (sessionMap.size() < MAX_SESSIONS) {
            return;
        }
        int evictCount = Math.max(1, MAX_SESSIONS / 10);
        List<Map.Entry<String, PuppetNodeSession>> sorted = new ArrayList<>(sessionMap.entrySet());
        sorted.sort(Comparator.comparingLong(e -> e.getValue().getLastActiveTime()));

        int evicted = 0;
        for (Map.Entry<String, PuppetNodeSession> entry : sorted) {
            if (evicted >= evictCount) break;
            PuppetNodeSession removed = sessionMap.remove(entry.getKey());
            if (removed != null) {
                try { removed.close(); } catch (Exception ignored) {}
                fireSessionDestroyed(entry.getKey());
                evicted++;
            }
        }
        if (evicted > 0) {
            log.warn("[PuppetNodeSession] 容量保护：已驱逐 {} 个最久未活跃的会话（当前上限 {}）", evicted, MAX_SESSIONS);
        }
    }

    /**
     * 移除会话并释放底层资源
     */
    public static boolean removeSession(String sessionId) {
        PuppetNodeSession session = sessionMap.remove(sessionId);
        if (session != null) {
            try { session.close(); } catch (Exception ignored) {}
            fireSessionDestroyed(sessionId);
        }
        return true;
    }

    /**
     * 获取会话
     */
    public static PuppetNodeSession getSession(String sessionId) {
        return sessionMap.get(sessionId);
    }

    /**
     * 检查会话是否存在
     */
    public static boolean hasSession(String sessionId) {
        return sessionMap.containsKey(sessionId);
    }

    /**
     * 获取所有会话
     */
    public static Map<String, PuppetNodeSession> getAllSession() {
        return sessionMap;
    }

    /**
     * 获取会话数量
     */
    public static int getSessionCount() {
        return sessionMap.size();
    }

    /**
     * 清空所有会话
     */
    public static void clearAllSessions() {
        List<String> sessionIds = new ArrayList<>(sessionMap.keySet());
        sessionMap.clear();
        for (String id : sessionIds) fireSessionDestroyed(id);
    }

    /**
     * 移除所有超过 {@code maxIdleMs} 毫秒未活跃的会话，并释放底层资源。
     *
     * @param maxIdleMs 最大空闲时长（毫秒），超过此值的会话将被驱逐
     * @return 被驱逐的 sessionId 列表
     */
    public static List<String> evictExpired(long maxIdleMs) {
        long now = System.currentTimeMillis();
        List<String> evicted = new ArrayList<>();
        for (Map.Entry<String, PuppetNodeSession> entry : sessionMap.entrySet()) {
            PuppetNodeSession session = entry.getValue();
            if (now - session.getLastActiveTime() > maxIdleMs) {
                sessionMap.remove(entry.getKey());
                try { session.close(); } catch (Exception ignored) {}
                fireSessionDestroyed(entry.getKey());
                evicted.add(entry.getKey());
            }
        }
        return evicted;
    }
}
