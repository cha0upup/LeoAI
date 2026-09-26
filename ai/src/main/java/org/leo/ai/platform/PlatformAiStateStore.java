package org.leo.ai.platform;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * 平台侧 AI 状态存储。
 * 不复用 PuppetNodeSessionContainer，避免平台侧与 PuppetNode 会话模型耦合。
 */
public final class PlatformAiStateStore {

    private static final int MAX_STATES = 500;
    private static final long MAX_IDLE_MS = 2 * 60 * 60 * 1000L;
    private static final Map<String, PlatformAiState> STATE_MAP = new ConcurrentHashMap<>();
    private static final CopyOnWriteArrayList<Consumer<String>> DESTROY_LISTENERS =
            new CopyOnWriteArrayList<>();

    private PlatformAiStateStore() {
    }

    public static PlatformAiState create(String stateId) {
        evictExpired();
        evictOldestIfFull();
        PlatformAiState state = new PlatformAiState(stateId);
        PlatformAiState replaced = STATE_MAP.put(stateId, state);
        if (replaced != null) notifyDestroyed(stateId);
        return state;
    }

    public static PlatformAiState get(String stateId) {
        PlatformAiState state = STATE_MAP.get(stateId);
        if (state != null && !state.isExecuting()
                && System.currentTimeMillis() - state.getLastActiveAt() > MAX_IDLE_MS) {
            if (STATE_MAP.remove(stateId, state)) notifyDestroyed(stateId);
            return null;
        }
        return state;
    }

    public static void remove(String stateId) {
        if (stateId != null && STATE_MAP.remove(stateId) != null) {
            notifyDestroyed(stateId);
        }
    }

    public static void registerDestroyListener(Consumer<String> listener) {
        if (listener != null) DESTROY_LISTENERS.addIfAbsent(listener);
    }

    public static void unregisterDestroyListener(Consumer<String> listener) {
        if (listener != null) DESTROY_LISTENERS.remove(listener);
    }

    private static void evictExpired() {
        long cutoff = System.currentTimeMillis() - MAX_IDLE_MS;
        for (Map.Entry<String, PlatformAiState> entry : STATE_MAP.entrySet()) {
            PlatformAiState state = entry.getValue();
            if (!state.isExecuting() && state.getLastActiveAt() < cutoff
                    && STATE_MAP.remove(entry.getKey(), state)) {
                notifyDestroyed(entry.getKey());
            }
        }
    }

    private static void evictOldestIfFull() {
        if (STATE_MAP.size() < MAX_STATES) return;
        STATE_MAP.entrySet().stream()
                .filter(entry -> !entry.getValue().isExecuting())
                .min(java.util.Comparator.comparingLong(
                        entry -> entry.getValue().getLastActiveAt()))
                .ifPresent(entry -> {
                    if (STATE_MAP.remove(entry.getKey(), entry.getValue())) {
                        notifyDestroyed(entry.getKey());
                    }
                });
    }

    private static void notifyDestroyed(String stateId) {
        for (Consumer<String> listener : DESTROY_LISTENERS) {
            try {
                listener.accept(stateId);
            } catch (RuntimeException ignored) {
                // 清理通知不能阻断状态销毁。
            }
        }
    }
}
