package org.leo.core.entity;

import java.util.Locale;

/**
 * 系统维护的模型能力上限。未识别模型使用保守默认值，避免超出真实能力导致调用失败。
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

    private static final int CONSERVATIVE_CONTEXT = 32_768;
    private static final int CONSERVATIVE_OUTPUT = 4_096;

    /** 兼容历史调用点。 */
    public int defaultMaxOutputTokens() {
        return maxOutputTokens;
    }

    /** 按模型名识别能力。所有未识别模型回退到保守兜底。 */
    public static ProviderCapabilities forModel(String modelName) {
        if (modelName == null || modelName.isBlank()) return conservative();
        String m = modelName.toLowerCase(Locale.ROOT);

        if (m.equals("mimo-v2.5-pro")) {
            return recognized(1_000_000, 128_000, true, true, true, true, true, true);
        }
        if (m.equals("mimo-v2.5-flash")) {
            return recognized(256_000, 32_000, true, true, true, true, true, false);
        }
        if (m.equals("mimo-embedding-v1") || m.contains("embedding")) {
            return new ProviderCapabilities(true, "recognized", "official",
                    8_192, 0, false, false, false, false, false, false, false);
        }

        if (m.contains("gemini-2.5-pro")) return recognized(1_000_000, 65_536, true, true, true, true, true, true);
        if (m.contains("gemini-2.5-flash") || m.contains("gemini-2.0-flash")) return recognized(1_000_000, 65_536, true, false, true, true, true, true);

        if (m.contains("deepseek-v4-flash")) return recognized(1_000_000, 384_000, true, false, true, true, true, false);
        if (m.contains("deepseek-v4-pro")) return recognized(1_000_000, 384_000, true, true, true, true, true, false);
        if (m.contains("deepseek-reasoner") || m.contains("deepseek-r1")) return recognized(128_000, 65_536, true, true, true, true, true, false);
        if (m.contains("deepseek-chat")) return recognized(128_000, 8_192, true, false, true, true, true, false);

        if (m.contains("qwen3") || m.contains("qwq")) return recognized(128_000, 32_000, true, true, true, true, true, false);
        if (m.contains("qwen-max") || m.contains("qwen-plus") || m.contains("qwen-turbo")) return recognized(128_000, 8_192, true, false, true, true, true, false);

        if (m.startsWith("o1") || m.startsWith("o3") || m.startsWith("o4") || m.contains("-o1") || m.contains("-o3") || m.contains("-o4")) {
            return recognized(200_000, 100_000, true, true, true, true, true, false);
        }
        if (m.contains("gpt-4o")) return recognized(128_000, 16_384, true, false, true, true, true, false);
        if (m.contains("gpt-4")) return recognized(128_000, 8_192, true, false, true, true, true, false);

        if (m.contains("moonshot-v1-128k")) return recognized(128_000, 8_192, true, false, true, true, false, false);
        if (m.contains("moonshot-v1-32k")) return recognized(32_000, 8_192, true, false, true, true, false, false);
        if (m.contains("moonshot-v1-8k")) return recognized(8_000, 4_096, true, false, true, true, false, false);

        return conservative();
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

    private static ProviderCapabilities conservative() {
        return new ProviderCapabilities(false, "pending", "conservative",
                CONSERVATIVE_CONTEXT, CONSERVATIVE_OUTPUT, true, false, true,
                false, false, false, true);
    }
}
