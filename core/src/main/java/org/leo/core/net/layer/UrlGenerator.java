package org.leo.core.net.layer;

import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * URL 生成器，根据 UrlStrategy 配置为每次请求生成不同的 URL。
 *
 * @author LeoSpring
 */
public class UrlGenerator {

    private static final String ALPHA_NUM = "abcdefghijklmnopqrstuvwxyz0123456789";
    private static final String HEX_CHARS = "0123456789abcdef";

    private static final String[] COMMON_WORDS = {
            "analytics", "telemetry", "config", "sync", "health", "check",
            "resource", "bundle", "chunk", "vendor", "module", "asset",
            "callback", "beacon", "collect", "upload", "report", "metric",
            "profile", "session", "token", "refresh", "validate", "query"
    };

    private static final String[] STATIC_DIRS = {
            "js", "css", "img", "fonts", "media", "icons", "chunks", "assets"
    };

    private static final String[] DEFAULT_EXTENSIONS = {
            ".js", ".css", ".png", ".woff2", ".svg", ".json", ".map"
    };

    private final UrlStrategy strategy;
    private final String fallbackUrl;
    private final Random random;
    private String stableUrl;

    /** 创建 seed 驱动的会话级 URL 生成器；同一 seed 始终选择同一路径。 */
    public UrlGenerator(UrlStrategy strategy, String fallbackUrl, String seed) {
        this.strategy = strategy;
        this.fallbackUrl = fallbackUrl;
        if (seed == null || seed.isBlank()) throw new IllegalArgumentException("seed不能为空");
        this.random = new Random(seedLong(seed));
    }

    /**
     * 生成本次请求使用的 URL 路径。
     * 如果策略未启用或配置无效，返回 fallbackUrl。
     */
    public String nextUrl() {
        return nextUrl(null);
    }

    /**
     * 按请求方法生成 URL。POST/PUT/PATCH 不使用图片、字体等二进制静态资源扩展名。
     */
    public synchronized String nextUrl(String method) {
        if (strategy == null || !strategy.isEnabled()) {
            return fallbackUrl;
        }

        if (stableUrl != null) {
            return stableUrl;
        }

        UrlStrategy.Mode mode = strategy.getMode();
        if (mode == null) {
            return fallbackUrl;
        }

        String generated;
        switch (mode) {
            case POOL:
                generated = pickFromPool();
                break;
            case TEMPLATE:
                generated = renderTemplate(method);
                break;
            case STATIC_ASSET:
                generated = generateStaticAssetPath(method);
                break;
            default:
                generated = fallbackUrl;
        }
        generated = resolve(generated);
        stableUrl = generated;
        return generated;
    }

    // ==================== POOL 模式 ====================

    private String pickFromPool() {
        List<String> pool = strategy.getUrlPool();
        if (pool == null || pool.isEmpty()) {
            return fallbackUrl;
        }
        return pool.get(random.nextInt(pool.size()));
    }

    // ==================== TEMPLATE 模式 ====================

    private String renderTemplate(String method) {
        String template = strategy.getUrlTemplate();
        if (template == null || template.isEmpty()) {
            return fallbackUrl;
        }

        String prefix = strategy.getPrefix() != null ? strategy.getPrefix() : "";
        String result = template;

        // 逐个替换占位符（每次调用生成不同值）
        while (result.contains("{rand}")) {
            result = replaceFirst(result, "{rand}", randomAlphaNum(8));
        }
        while (result.contains("{uuid}")) {
            result = replaceFirst(result, "{uuid}", UUID.randomUUID().toString().replace("-", "").substring(0, 8));
        }
        while (result.contains("{ts}")) {
            result = replaceFirst(result, "{ts}", String.valueOf(System.currentTimeMillis()));
        }
        while (result.contains("{ext}")) {
            result = replaceFirst(result, "{ext}", randomExtension(method));
        }
        while (result.contains("{word}")) {
            result = replaceFirst(result, "{word}", COMMON_WORDS[random.nextInt(COMMON_WORDS.length)]);
        }
        while (result.contains("{dir}")) {
            result = replaceFirst(result, "{dir}", STATIC_DIRS[random.nextInt(STATIC_DIRS.length)]);
        }
        while (result.contains("{hex}")) {
            result = replaceFirst(result, "{hex}", randomHex(6));
        }

        return prefix + result;
    }

    // ==================== STATIC_ASSET 模式 ====================

    private String generateStaticAssetPath(String method) {
        String prefix = strategy.getPrefix() != null ? strategy.getPrefix() : "/static";
        String dir = STATIC_DIRS[random.nextInt(STATIC_DIRS.length)];
        String name = randomAlphaNum(4) + "." + randomHex(6);
        String ext = randomExtension(method);

        return prefix + "/" + dir + "/" + name + ext;
    }

    // ==================== 工具方法 ====================

    private String randomAlphaNum(int len) {
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            sb.append(ALPHA_NUM.charAt(random.nextInt(ALPHA_NUM.length())));
        }
        return sb.toString();
    }

    private String randomHex(int len) {
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            sb.append(HEX_CHARS.charAt(random.nextInt(HEX_CHARS.length())));
        }
        return sb.toString();
    }

    private String randomExtension(String method) {
        List<String> exts = strategy.getExtensions();
        List<String> candidates = exts != null && !exts.isEmpty()
                ? exts : java.util.Arrays.asList(DEFAULT_EXTENSIONS);
        if (isBodyMethod(method)) {
            List<String> coherent = candidates.stream()
                    .filter(this::isBodyExtension)
                    .toList();
            if (!coherent.isEmpty()) candidates = coherent;
            else return ".json";
        }
        return candidates.get(random.nextInt(candidates.size()));
    }

    private boolean isBodyMethod(String method) {
        return method != null && ("POST".equalsIgnoreCase(method)
                || "PUT".equalsIgnoreCase(method) || "PATCH".equalsIgnoreCase(method));
    }

    private boolean isBodyExtension(String extension) {
        if (extension == null) return false;
        String value = extension.toLowerCase(java.util.Locale.ROOT);
        return value.equals(".json") || value.equals(".js") || value.equals(".map")
                || value.equals(".txt") || value.equals(".html");
    }

    private String resolve(String candidate) {
        if (candidate == null || candidate.isBlank()) return fallbackUrl;
        try {
            URI value = URI.create(candidate);
            if (value.isAbsolute()) return value.toString();
            URI base = URI.create(fallbackUrl);
            if (!base.isAbsolute()) return candidate;
            String path = candidate.startsWith("/") ? candidate : "/" + candidate;
            return new URI(base.getScheme(), base.getUserInfo(), base.getHost(), base.getPort(),
                    null, null, null).resolve(path).toString();
        } catch (Exception ignored) {
            return fallbackUrl;
        }
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

    private static String replaceFirst(String source, String target, String replacement) {
        int idx = source.indexOf(target);
        if (idx < 0) {
            return source;
        }
        return source.substring(0, idx) + replacement + source.substring(idx + target.length());
    }
}
