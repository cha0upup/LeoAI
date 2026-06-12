package org.leo.core.net.layer;

import java.util.*;

/**
 * HTTP Header 噪声生成器。
 * <p>
 * 根据 {@link HeaderNoiseStrategy} 配置，每次调用生成一组随机 Header KV 对，
 * 注入到 HTTP 请求中，改变请求指纹。
 *
 * @author LeoSpring
 */
public class HeaderNoiseGenerator {

    private static final Random RANDOM = new Random();
    private static final String ALPHA_NUM = "abcdefghijklmnopqrstuvwxyz0123456789";
    private static final String HEX_CHARS = "0123456789abcdef";

    private final HeaderNoiseStrategy strategy;

    public HeaderNoiseGenerator(HeaderNoiseStrategy strategy) {
        this.strategy = strategy;
    }

    /**
     * 生成一批噪声 Header（key→value）。
     * 如果策略未启用或为 null，返回空 Map。
     *
     * @return 噪声 Header 映射
     */
    public Map<String, String> generate() {
        if (strategy == null || !strategy.isEnabled()) {
            return Collections.emptyMap();
        }

        int count = randomBetween(strategy.getMinHeaders(), strategy.getMaxHeaders());
        if (count <= 0) {
            return Collections.emptyMap();
        }

        String[] prefixes = strategy.getPrefixes();
        Map<String, String> noiseHeaders = new LinkedHashMap<>(count);

        // 随机选取不重复的 Header 名
        List<Integer> indices = new ArrayList<>(prefixes.length);
        for (int i = 0; i < prefixes.length; i++) {
            indices.add(i);
        }
        Collections.shuffle(indices, RANDOM);

        int limit = Math.min(count, prefixes.length);
        for (int i = 0; i < limit; i++) {
            String headerName = prefixes[indices.get(i)];
            String headerValue = generateValue(strategy.getValueMode());
            noiseHeaders.put(headerName, headerValue);
        }

        return noiseHeaders;
    }

    // ==================== 值生成 ====================

    private String generateValue(HeaderNoiseStrategy.HeaderValueMode mode) {
        switch (mode) {
            case UUID_LIKE:
                return generateUuidLike();
            case NUMERIC:
                return generateNumeric();
            case RANDOM_ALPHANUM:
            default:
                return generateAlphaNum(12 + RANDOM.nextInt(20));
        }
    }

    private String generateAlphaNum(int len) {
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            sb.append(ALPHA_NUM.charAt(RANDOM.nextInt(ALPHA_NUM.length())));
        }
        return sb.toString();
    }

    private String generateUuidLike() {
        // 格式：xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx
        return hexBlock(8) + "-" + hexBlock(4) + "-" + hexBlock(4) + "-" + hexBlock(4) + "-" + hexBlock(12);
    }

    private String hexBlock(int len) {
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            sb.append(HEX_CHARS.charAt(RANDOM.nextInt(16)));
        }
        return sb.toString();
    }

    private String generateNumeric() {
        // 模拟时间戳或长数字串
        long base = System.currentTimeMillis() + RANDOM.nextInt(100000);
        return String.valueOf(base);
    }

    private int randomBetween(int min, int max) {
        if (min >= max) return min;
        return min + RANDOM.nextInt(max - min + 1);
    }
}
