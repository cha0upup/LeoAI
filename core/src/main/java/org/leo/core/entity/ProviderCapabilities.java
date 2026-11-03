package org.leo.core.entity;

import java.util.Locale;

/**
 * 系统维护的模型能力上限。能力库命中时使用精确能力；未收录模型使用保守默认能力。
 */
public record ProviderCapabilities(
        boolean recognized,
        String status,
        String source,
        int contextWindowTokens,
        int maxOutputTokens,
        boolean supportsTextGeneration,
        boolean supportsReasoning,
        boolean supportsStreaming,
        boolean supportsFunctionCalling,
        boolean supportsStructuredOutput,
        boolean supportsWebSearch,
        boolean supportsParallelToolCalls) {

    public static String normalizeModelName(String providerKey, String modelName) {
        String m = modelName == null ? "" : modelName.trim().toLowerCase(Locale.ROOT);
        if (m.contains("/")) {
            String[] parts = m.split("/", 2);
            String prefix = parts[0];
            String tail = parts.length > 1 ? parts[1] : m;
            if (isKnownProvider(prefix)
                    || "openrouter".equals(providerKey)
                    || "litellm".equals(providerKey)
                    || "oneapi".equals(providerKey)) {
                return tail;
            }
        }
        return m;
    }

    private static boolean isKnownProvider(String key) {
        return switch (key) {
            case "openai", "deepseek", "qwen", "dashscope", "mimo", "gemini", "kimi", "moonshot", "zhipu" -> true;
            default -> false;
        };
    }

    public static ProviderCapabilities missing() {
        return new ProviderCapabilities(false, "missing", "capability_library",
                0, 0, false, false, false, false, false, false, false);
    }

    public static ProviderCapabilities conservativeDefault() {
        return new ProviderCapabilities(false, "unverified", "conservative_default",
                32_768, 4_096, true, false, true, false, false, false, false);
    }
}
