package org.leo.ai.channel;

import org.leo.ai.service.AiErrorClassifier;
import org.leo.core.entity.AiModelConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.LongSupplier;

/**
 * 模型调用健康状态与熔断选择。
 *
 * <p>状态仅保存在进程内：它用来快速保护当前实例，不会把一次短暂的网络故障写回模型配置。
 * 熔断后仅在下一轮新请求选择已配置的备用模型，绝不对已经开始工具执行的流式请求做跨模型重试。
 */
@Component
public class AiModelFailoverService {

    private final AiModelConfigService configService;
    private final LongSupplier clock;
    private final ConcurrentMap<Integer, HealthState> states = new ConcurrentHashMap<>();

    @Value("${leo.ai.failover.failure-threshold:2}")
    private int failureThreshold = 2;

    @Value("${leo.ai.failover.cooldown-seconds:120}")
    private long cooldownSeconds = 120L;

    @Autowired
    public AiModelFailoverService(AiModelConfigService configService) {
        this(configService, System::currentTimeMillis);
    }

    AiModelFailoverService(AiModelConfigService configService, LongSupplier clock) {
        this.configService = configService;
        this.clock = clock;
    }

    /** 返回本轮实际应使用的模型；主模型正常时不会改变用户选中的模型。 */
    public ModelSelection selectForExecution(AiModelConfig requested) {
        if (requested == null) {
            throw new IllegalArgumentException("AI 模型不能为空");
        }
        if (tryAcquire(requested.getId())) {
            return ModelSelection.direct(requested);
        }

        List<Integer> attempted = new ArrayList<>();
        Set<Integer> visited = new HashSet<>();
        AiModelConfig cursor = requested;
        while (cursor != null && cursor.getId() != null && visited.add(cursor.getId())) {
            attempted.add(cursor.getId());
            Integer fallbackId = cursor.getFallbackModelId();
            if (fallbackId == null) break;
            AiModelConfig candidate = configService.resolve(fallbackId);
            if (!isEligible(candidate)) {
                break;
            }
            if (tryAcquire(candidate.getId())) {
                return ModelSelection.failover(requested, candidate, attempted,
                        "主模型暂时熔断，已切换到备用模型「" + candidate.getName() + "」");
            }
            cursor = candidate;
        }
        throw new IllegalStateException("AI 模型处于熔断冷却期或恢复探测中，且没有可用的备用模型，请稍后重试");
    }

    /** 成功响应会立即关闭该模型的熔断并清空连续失败计数。 */
    public void recordSuccess(Integer configId) {
        if (configId == null) return;
        HealthState state = state(configId);
        synchronized (state) {
            state.consecutiveFailures = 0;
            state.openUntil = 0L;
            state.probeUntil = 0L;
            state.lastSuccessAt = clock.getAsLong();
            state.lastCategory = null;
            state.lastMessage = null;
        }
    }

    /**
     * 仅把短暂性故障计入熔断阈值。鉴权、模型不存在和参数错误必须由管理员修复，
     * 不应静默切换到其他模型掩盖问题。
     */
    public void recordFailure(Integer configId, AiErrorClassifier.Classification classification) {
        if (configId == null || classification == null) return;
        HealthState state = state(configId);
        synchronized (state) {
            long now = clock.getAsLong();
            state.lastFailureAt = now;
            state.lastCategory = classification.category();
            state.lastMessage = classification.message();
            boolean recovering = state.probeUntil > 0L;
            state.probeUntil = 0L;
            if (!isTransient(classification.category())) {
                state.openUntil = 0L;
                state.consecutiveFailures = 0;
                return;
            }
            state.consecutiveFailures++;
            if (recovering || state.consecutiveFailures >= effectiveThreshold()) {
                state.openUntil = now + effectiveCooldownSeconds() * 1000L;
            }
        }
    }

    /** 手动测试连接成功后可调用，恢复该模型的服务状态。 */
    public void reset(Integer configId) {
        if (configId == null) return;
        states.remove(configId);
    }

    public HealthSnapshot snapshot(Integer configId) {
        if (configId == null) return HealthSnapshot.unknown(null);
        HealthState state = states.get(configId);
        if (state == null) return HealthSnapshot.unknown(configId);
        synchronized (state) {
            long now = clock.getAsLong();
            boolean open = state.openUntil > now || state.probeUntil > now;
            String status = state.openUntil > 0L && state.openUntil <= now ? "half_open" : open ? "open"
                    : state.consecutiveFailures > 0 || state.lastFailureAt > state.lastSuccessAt
                    ? "degraded" : "healthy";
            return new HealthSnapshot(configId, status, open, state.consecutiveFailures, state.openUntil,
                    state.lastCategory, state.lastMessage, state.lastSuccessAt, state.lastFailureAt);
        }
    }

    public List<Map<String, Object>> snapshots(List<AiModelConfig> configs) {
        if (configs == null || configs.isEmpty()) return List.of();
        List<Map<String, Object>> list = new ArrayList<>(configs.size());
        for (AiModelConfig config : configs) {
            if (config == null) continue;
            LinkedHashMap<String, Object> item = new LinkedHashMap<>(snapshot(config.getId()).toMap());
            item.put("configId", config.getId());
            item.put("name", config.getName());
            item.put("model", config.getModel());
            item.put("fallbackModelId", config.getFallbackModelId());
            list.add(item);
        }
        return list;
    }

    private boolean tryAcquire(Integer configId) {
        if (configId == null) return true;
        HealthState state = states.get(configId);
        if (state == null) return true;
        synchronized (state) {
            if (state.openUntil <= 0L) return true;
            long now = clock.getAsLong();
            if (now < state.openUntil || now < state.probeUntil) return false;
            // Permit one recovery request; a cancelled/abandoned turn cannot hold it forever.
            state.probeUntil = now + Math.max(300L, effectiveCooldownSeconds()) * 1000L;
            return true;
        }
    }

    private static boolean isEligible(AiModelConfig config) {
        return config != null && Integer.valueOf(1).equals(config.getEnabled());
    }

    private static boolean isTransient(String category) {
        return AiErrorClassifier.CATEGORY_RATE_LIMIT.equals(category)
                || AiErrorClassifier.CATEGORY_TIMEOUT.equals(category)
                || AiErrorClassifier.CATEGORY_NETWORK.equals(category)
                || AiErrorClassifier.CATEGORY_SERVER_ERROR.equals(category)
                || AiErrorClassifier.CATEGORY_MALFORMED_RESPONSE.equals(category);
    }

    private HealthState state(Integer configId) {
        return states.computeIfAbsent(configId, ignored -> new HealthState());
    }

    private int effectiveThreshold() {
        return Math.max(1, failureThreshold);
    }

    private long effectiveCooldownSeconds() {
        return Math.max(1L, cooldownSeconds);
    }

    private static final class HealthState {
        private int consecutiveFailures;
        private long openUntil;
        private long probeUntil;
        private String lastCategory;
        private String lastMessage;
        private long lastSuccessAt;
        private long lastFailureAt;
    }

    public record ModelSelection(AiModelConfig requestedConfig, AiModelConfig effectiveConfig,
                                 boolean failover, String message, List<Integer> attemptedConfigIds) {
        private static ModelSelection direct(AiModelConfig config) {
            return direct(config, List.of(config.getId()), null);
        }

        private static ModelSelection direct(AiModelConfig config, List<Integer> attempted, String message) {
            return new ModelSelection(config, config, false, message, List.copyOf(attempted));
        }

        private static ModelSelection failover(AiModelConfig requested, AiModelConfig effective,
                                               List<Integer> attempted, String message) {
            return new ModelSelection(requested, effective, true, message, List.copyOf(attempted));
        }
    }

    public record HealthSnapshot(Integer configId, String status, boolean circuitOpen,
                                 int consecutiveFailures, long openUntil, String lastCategory,
                                 String lastMessage, long lastSuccessAt, long lastFailureAt) {
        private static HealthSnapshot unknown(Integer configId) {
            return new HealthSnapshot(configId, "unknown", false, 0, 0L,
                    null, null, 0L, 0L);
        }

        public Map<String, Object> toMap() {
            Map<String, Object> map = new HashMap<>();
            map.put("configId", configId);
            map.put("status", status);
            map.put("circuitOpen", circuitOpen);
            map.put("consecutiveFailures", consecutiveFailures);
            map.put("openUntil", openUntil > 0L ? openUntil : null);
            map.put("lastCategory", lastCategory);
            map.put("lastSuccessAt", lastSuccessAt > 0L ? lastSuccessAt : null);
            map.put("lastFailureAt", lastFailureAt > 0L ? lastFailureAt : null);
            return map;
        }
    }
}
