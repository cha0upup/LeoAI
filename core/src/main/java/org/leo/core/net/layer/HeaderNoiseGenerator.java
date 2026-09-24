package org.leo.core.net.layer;

import java.util.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * HTTP Header 噪声生成器。
 * <p>
 * 根据 {@link HeaderNoiseStrategy} 配置，每次调用生成一组随机 Header KV 对，
 * 注入到 HTTP 请求中，改变请求指纹。
 *
 * @author LeoSpring
 */
public class HeaderNoiseGenerator {

    private static final String ALPHA_NUM = "abcdefghijklmnopqrstuvwxyz0123456789";
    private static final String HEX_CHARS = "0123456789abcdef";

    private final HeaderNoiseStrategy strategy;
    private final long sessionSeed;

    /**
     * 创建会话级 Header 生成器。同一会话会持续返回同一组 Header，
     * 避免单个客户端在短时间内不断改变 Header 集合和值格式。
     */
    public HeaderNoiseGenerator(HeaderNoiseStrategy strategy, String seed) {
        this.strategy = strategy;
        if (seed == null || seed.isBlank()) throw new IllegalArgumentException("seed不能为空");
        this.sessionSeed = seedLong(seed);
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

        Random random = new Random(sessionSeed);
        int count = randomBetween(strategy.getMinHeaders(), strategy.getMaxHeaders(), random);
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
        Collections.shuffle(indices, random);

        int limit = Math.min(count, prefixes.length);
        for (int i = 0; i < limit; i++) {
            String headerName = prefixes[indices.get(i)];
            String headerValue = generateValue(strategy.getValueMode(), random);
            noiseHeaders.put(headerName, headerValue);
        }

        return noiseHeaders;
    }

    // ==================== 值生成 ====================

    private String generateValue(HeaderNoiseStrategy.HeaderValueMode mode, Random random) {
        switch (mode) {
            case UUID_LIKE:
                return generateUuidLike(random);
            case NUMERIC:
                return generateNumeric(random);
            case RANDOM_ALPHANUM:
            default:
                return generateAlphaNum(12 + random.nextInt(20), random);
        }
    }

    private String generateAlphaNum(int len, Random random) {
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            sb.append(ALPHA_NUM.charAt(random.nextInt(ALPHA_NUM.length())));
        }
        return sb.toString();
    }

    private String generateUuidLike(Random random) {
        // 格式：xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx
        return hexBlock(8, random) + "-" + hexBlock(4, random) + "-" + hexBlock(4, random)
                + "-" + hexBlock(4, random) + "-" + hexBlock(12, random);
    }

    private String hexBlock(int len, Random random) {
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            sb.append(HEX_CHARS.charAt(random.nextInt(16)));
        }
        return sb.toString();
    }

    private String generateNumeric(Random random) {
        long base = 1_700_000_000_000L + Math.floorMod(random.nextLong(), 100_000_000_000L);
        return String.valueOf(base);
    }

    private int randomBetween(int min, int max, Random random) {
        if (min >= max) return min;
        return min + random.nextInt(max - min + 1);
    }

    private long seedLong(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            long seed = 0L;
            for (int index = 0; index < Long.BYTES; index++) {
                seed = (seed << 8) | (digest[index] & 0xffL);
            }
            return seed;
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
