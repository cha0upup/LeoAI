package org.leo.core.entity;

import java.util.Locale;

/**
 * 模型级默认推断。按 model name 推断"该模型默认是否启用 reasoning"。
 *
 * <p>OpenAI Responses API：reasoning 能力按模型能力和配置启用。用户在
 * {@link AiModelConfig} 上的显式配置始终优先于这里的推断。
 */
public final class ModelDefaults {

    private ModelDefaults() {}

    /**
     * 模型默认是否启用 reasoning。null = 没有强意见，沿用调用方 fallback。
     *
     * <ul>
     *   <li>OpenAI o1 / o3 / o4 系列：默认开</li>
     *   <li>DeepSeek-reasoner / *-thinking / deepseek-r1 / qwq：默认开</li>
     *   <li>gpt-5 reasoning 变体：默认开</li>
     *   <li>含 "mini"（非 o3/o4-mini）/ "flash" / "haiku" / "lite" / "nano"：默认关</li>
     *   <li>未识别：返回 null</li>
     * </ul>
     */
    public static Boolean defaultThinkingEnabled(String modelName) {
        return defaultThinkingEnabled(null, modelName);
    }

    public static Boolean defaultThinkingEnabled(String providerKey, String modelName) {
        if (modelName == null) return null;
        String provider = providerKey == null ? "" : providerKey.toLowerCase(Locale.ROOT);
        String m = ProviderCapabilities.normalizeModelName(provider, modelName);

        // OpenAI reasoning 系列优先识别（避免被 mini 规则误杀）
        if (m.contains("o3-mini") || m.contains("o4-mini")) return true;
        if (m.startsWith("o1") || m.contains("-o1")) return true;
        if (m.startsWith("o3") || m.contains("-o3")) return true;
        if (m.startsWith("o4") || m.contains("-o4")) return true;
        if (m.contains("gpt-5") || m.contains("gpt5.")) return true;

        // 第三方 reasoning
        if (m.contains("reasoner")) return true;           // deepseek-reasoner
        if (m.contains("thinking")) return true;           // qwen-*-thinking, glm-*-thinking
        if (m.contains("deepseek-r1")) return true;
        if (m.contains("deepseek-v4")) return true;
        if (m.contains("glm-5") || m.contains("glm5.")) return true;
        if (m.contains("mimo-v2.5") || m.contains("mimo2.5")) return true;
        if (m.contains("qwq")) return true;                // qwen QwQ

        // 轻量/快速模型：永远不开 reasoning
        if (m.contains("haiku")) return false;
        if (m.contains("flash")) return false;
        if (m.contains("lite")) return false;
        if (m.contains("nano")) return false;
        if (m.contains("mini")) return false;

        return null;
    }
}
