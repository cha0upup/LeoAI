package org.leo.core.entity;

/**
 * 模型能力描述。OpenAI Chat Completions 兼容下，能力差异主要由 model name 决定，
 * 这里按模型名推断，集中给消费者一个只读视图。
 *
 * @param supportsReasoning           模型是否能产出 reasoning_content（如 o1/o3、deepseek-reasoner、qwen-*-thinking）
 * @param supportsParallelToolCalls   是否支持 parallel_tool_calls（默认 true，明确不支持的模型为 false）
 * @param defaultMaxOutputTokens      未显式配置 max_output_tokens 时的兜底
 */
public record ProviderCapabilities(
        boolean supportsReasoning,
        boolean supportsParallelToolCalls,
        int defaultMaxOutputTokens) {

    /** 按模型名推断能力。所有未识别模型回退到通用兜底。 */
    public static ProviderCapabilities forModel(String modelName) {
        Boolean reasoning = ModelDefaults.defaultThinkingEnabled(modelName);
        boolean supportsReasoning = Boolean.TRUE.equals(reasoning);
        int maxTokens = inferDefaultMaxOutputTokens(modelName);
        return new ProviderCapabilities(supportsReasoning, true, maxTokens);
    }

    private static int inferDefaultMaxOutputTokens(String modelName) {
        if (modelName == null) return 32768;
        String m = modelName.toLowerCase(java.util.Locale.ROOT);
        // reasoning 模型 token 用量更大，给一个宽松的默认
        if (m.contains("o1") || m.contains("o3") || m.contains("reasoner")) return 65536;
        if (m.contains("gpt-5") || m.contains("gpt-4o")) return 16384;
        if (m.contains("mini") || m.contains("flash") || m.contains("haiku")) return 8192;
        return 32768;
    }
}
