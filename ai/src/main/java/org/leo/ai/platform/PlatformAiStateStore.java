package org.leo.ai.platform;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 平台侧 AI 状态存储。
 * 不复用 PuppetNodeSessionContainer，避免平台侧与 PuppetNode 会话模型耦合。
 */
public final class PlatformAiStateStore {

    private static final Map<String, PlatformAiState> STATE_MAP = new ConcurrentHashMap<>();

    private PlatformAiStateStore() {
    }

    public static PlatformAiState create(String stateId) {
        PlatformAiState state = new PlatformAiState(stateId);
        STATE_MAP.put(stateId, state);
        return state;
    }

    public static PlatformAiState get(String stateId) {
        return STATE_MAP.get(stateId);
    }

    public static boolean has(String stateId) {
        return STATE_MAP.containsKey(stateId);
    }

    public static void remove(String stateId) {
        STATE_MAP.remove(stateId);
    }
}
