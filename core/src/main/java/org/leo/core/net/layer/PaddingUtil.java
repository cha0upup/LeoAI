package org.leo.core.net.layer;

import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 请求体 Padding 工具类（v4 — Map 级填充）。
 * <p>
 * 核心思想：在 encode 之前向参数 Map 中注入随机填充字段，
 * Disguise.encode() 会将填充数据与真实数据一起加密。
 * <p>
 * 优势：
 * <ul>
 *   <li>零协议开销 — 不需要长度前缀、信封、格式检测</li>
 *   <li>完全隐蔽 — 填充在加密内部，外部无任何可识别特征</li>
 *   <li>Puppet 端零改动 — 只读已知 key，自动忽略填充字段</li>
 *   <li>实现极简 — 核心逻辑不到 30 行</li>
 * </ul>
 * <p>
 * 填充 key 使用 {@code _p} 前缀 + 随机后缀，避免与业务字段冲突。
 *
 * @author LeoSpring
 */
public class PaddingUtil {

    /** 填充 key 前缀（puppet 端不会读取以此开头的 key） */
    private static final String PADDING_KEY_PREFIX = "_p";

    private static final String ALPHA_NUM =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";

    private PaddingUtil() {}

    /**
     * 在参数 Map 中注入随机填充数据。
     * <p>
     * 注入后的 Map 交给 Disguise.encode() 一起加密，
     * 填充数据在加密后完全不可识别。
     *
     * @param params   待编码的参数 Map（会被直接修改）
     * @param strategy padding 策略配置
     */
    public static void pad(Map<String, Object> params, PaddingStrategy strategy) {
        if (strategy == null || !strategy.isEnabled() || params == null) {
            return;
        }

        int paddingLen = computePaddingLength(strategy);

        // 生成随机填充值
        String paddingValue = randomString(paddingLen);

        // 注入填充字段（key 带随机后缀，防止多次调用时覆盖）
        String key = PADDING_KEY_PREFIX + randomString(4);
        params.put(key, paddingValue);
    }

    /**
     * 从解码后的 Map 中移除填充字段（可选调用，用于控制端解码响应时清理）。
     * <p>
     * 通常不需要调用 — puppet 端天然忽略未知 key。
     * 仅在控制端需要严格干净的响应数据时使用。
     *
     * @param params 解码后的参数 Map
     */
    public static void removePadding(Map<String, Object> params) {
        if (params == null) return;
        params.keySet().removeIf(key -> key.startsWith(PADDING_KEY_PREFIX));
    }

    // ==================== 填充长度计算 ====================

    private static int computePaddingLength(PaddingStrategy strategy) {
        int min = strategy.getMinBytes();
        int max = strategy.getMaxBytes();

        switch (strategy.getLengthDistribution()) {
            case GAUSSIAN: {
                double mean = (min + max) / 2.0;
                double stddev = (max - min) / 6.0;
                double value = ThreadLocalRandom.current().nextGaussian() * stddev + mean;
                return Math.max(min, Math.min(max, (int) value));
            }
            case EXPONENTIAL: {
                double lambda = 3.0 / (max - min);
                double value = -Math.log(1 - ThreadLocalRandom.current().nextDouble()) / lambda;
                return Math.max(min, Math.min(max, min + (int) value));
            }
            case UNIFORM:
            default:
                return randomBetween(min, max);
        }
    }

    // ==================== 工具方法 ====================

    private static int randomBetween(int min, int max) {
        if (min >= max) return min;
        return ThreadLocalRandom.current().nextInt(min, max + 1);
    }

    private static String randomString(int length) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(ALPHA_NUM.charAt(rng.nextInt(ALPHA_NUM.length())));
        }
        return sb.toString();
    }
}
