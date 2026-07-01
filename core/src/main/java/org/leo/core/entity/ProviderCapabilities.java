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

    /** 兼容历史调用点。 */
    public int defaultMaxOutputTokens() {
        return maxOutputTokens;
    }

    /** 兼容历史调用点。实际模型调用以数据库能力库为准。 */
    public static ProviderCapabilities forModel(String modelName) {
        return forModel(null, modelName);
    }

    /** 兼容历史调用点。实际模型调用以数据库能力库为准。 */
    public static ProviderCapabilities forModel(String providerKey, String modelName) {
        if (modelName == null || modelName.isBlank()) return missing();
        String provider = providerKey == null ? "" : providerKey.toLowerCase(Locale.ROOT);
        String m = normalizeModelName(provider, modelName);

        if (m.equals("mimo-v2.5-pro") || m.equals("mimo2.5pro")) {
            return recognized(1_000_000, 128_000, true, true, true, true, true, true);
        }
        if (m.equals("mimo-v2.5-flash") || m.equals("mimo2.5flash")) {
            return recognized(256_000, 32_000, true, true, true, true, true, false);
        }
        if (m.equals("mimo-embedding-v1") || m.contains("embedding")) {
            return new ProviderCapabilities(true, "recognized", "official",
                    8_192, 0, false, false, false, false, false, false, false);
        }

        if (m.contains("gemini-2.5-pro")) return recognized(1_000_000, 65_536, true, true, true, true, true, true);
        if (m.contains("gemini-2.5-flash") || m.contains("gemini-2.0-flash")) return recognized(1_000_000, 65_536, true, false, true, true, true, true);

        if (m.contains("deepseek-v4-flash")) return recognized(1_000_000, 384_000, true, true, true, true, true, false);
        if (m.contains("deepseek-v4-pro")) return recognized(1_000_000, 384_000, true, true, true, true, true, false);
        if (m.contains("deepseek-reasoner") || m.contains("deepseek-r1")) return recognized(128_000, 65_536, true, true, true, true, true, false);
        if (m.contains("deepseek-chat")) return recognized(128_000, 8_192, true, false, true, true, true, false);

        if (m.contains("qwen3") || m.contains("qwq")) return recognized(128_000, 32_000, true, true, true, true, true, false);
        if (m.contains("qwen-max") || m.contains("qwen-plus") || m.contains("qwen-turbo")) return recognized(128_000, 8_192, true, false, true, true, true, false);

        if (m.equals("gpt-5.5") || m.equals("gpt5.5")
                || m.equals("gpt-5.4") || m.equals("gpt5.4")) {
            return recognized(400_000, 128_000, true, true, true, true, true, true);
        }
        if (m.equals("glm-5.2") || m.equals("glm5.2")
                || m.equals("glm-5.1") || m.equals("glm5.1")) {
            return recognized(256_000, 64_000, true, true, true, true, true, true);
        }

        if (m.startsWith("o1") || m.startsWith("o3") || m.startsWith("o4") || m.contains("-o1") || m.contains("-o3") || m.contains("-o4")) {
            return recognized(200_000, 100_000, true, true, true, true, true, false);
        }
        if (m.contains("gpt-4o")) return recognized(128_000, 16_384, true, false, true, true, true, false);
        if (m.contains("gpt-4")) return recognized(128_000, 8_192, true, false, true, true, true, false);

        if (m.contains("moonshot-v1-128k")) return recognized(128_000, 8_192, true, false, true, true, false, false);
        if (m.contains("moonshot-v1-32k")) return recognized(32_000, 8_192, true, false, true, true, false, false);
        if (m.contains("moonshot-v1-8k")) return recognized(8_000, 4_096, true, false, true, true, false, false);

        return missing();
    }

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
            case "openai", "deepseek", "qwen", "dashscope", "mimo", "gemini", "moonshot", "zhipu" -> true;
            default -> false;
        };
    }

    private static ProviderCapabilities recognized(int contextWindowTokens,
                                                   int maxOutputTokens,
                                                   boolean textGeneration,
                                                   boolean reasoning,
                                                   boolean streaming,
                                                   boolean functionCalling,
                                                   boolean structuredOutput,
                                                   boolean webSearch) {
        return new ProviderCapabilities(true, "recognized", "official",
                contextWindowTokens, maxOutputTokens, textGeneration, reasoning, streaming,
                functionCalling, structuredOutput, webSearch, true);
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
