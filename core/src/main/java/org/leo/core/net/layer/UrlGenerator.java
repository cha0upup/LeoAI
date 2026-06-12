package org.leo.core.net.layer;

import java.util.List;
import java.util.Random;
import java.util.UUID;

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
    private final Random random = new Random();

    public UrlGenerator(UrlStrategy strategy, String fallbackUrl) {
        this.strategy = strategy;
        this.fallbackUrl = fallbackUrl;
    }

    /**
     * 生成本次请求使用的 URL 路径。
     * 如果策略未启用或配置无效，返回 fallbackUrl。
     */
    public String nextUrl() {
        if (strategy == null || !strategy.isEnabled()) {
            return fallbackUrl;
        }

        UrlStrategy.Mode mode = strategy.getMode();
        if (mode == null) {
            return fallbackUrl;
        }

        switch (mode) {
            case POOL:
                return pickFromPool();
            case TEMPLATE:
                return renderTemplate();
            case STATIC_ASSET:
                return generateStaticAssetPath();
            default:
                return fallbackUrl;
        }
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

    private String renderTemplate() {
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
            result = replaceFirst(result, "{ext}", randomExtension());
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

    private String generateStaticAssetPath() {
        String prefix = strategy.getPrefix() != null ? strategy.getPrefix() : "/static";
        String dir = STATIC_DIRS[random.nextInt(STATIC_DIRS.length)];
        String name = randomAlphaNum(4) + "." + randomHex(6);
        String ext = randomExtension();

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

    private String randomExtension() {
        List<String> exts = strategy.getExtensions();
        if (exts != null && !exts.isEmpty()) {
            return exts.get(random.nextInt(exts.size()));
        }
        return DEFAULT_EXTENSIONS[random.nextInt(DEFAULT_EXTENSIONS.length)];
    }

    private static String replaceFirst(String source, String target, String replacement) {
        int idx = source.indexOf(target);
        if (idx < 0) {
            return source;
        }
        return source.substring(0, idx) + replacement + source.substring(idx + target.length());
    }
}
