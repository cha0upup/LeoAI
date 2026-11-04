package org.leo.ai.channel;

import org.leo.core.entity.AiModelConfig;
import org.leo.core.entity.ProviderCapabilities;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Typed, model-specific request contract used by validation, planning and the UI. */
public record ModelRequestOptions(boolean thinkingRequired, List<String> reasoningEfforts,
        boolean temperatureConfigurable, boolean responsesRequiredForTools,
        Boolean defaultThinking, String defaultReasoningEffort, String family,
        String modelName, String protocol, String source, String sourceUrl, String checkedAt,
        Map<String, ParameterRule> parameters) {
    private static final List<String> GENERAL = List.of("minimal", "low", "medium", "high", "xhigh", "max");
    private static final Set<String> OPENAI = Set.of("gpt-6-astra", "gpt-6.1-sol", "gpt-6.1-terra", "gpt-6-luna");
    private static final Set<String> KIMI = Set.of("kimi-k3.1-pro", "kimi-k3.1-pro-highspeed", "kimi-k3",
            "kimi-k2.7-code", "kimi-k2.7-code-highspeed", "kimi-k2.6");
    private static final Set<String> GLM = Set.of("glm-5.3", "glm-5.3-flash", "glm-5.3-flashx", "glm-5.3-flashx-legal");
    private static final Set<String> MIMO = Set.of("mimo-v2.6-pro", "mimo-v2.6-flash", "mimo-v2.6-pro-ultraspeed");
    private static final Set<String> QWEN = Set.of("qwen3.8-max", "qwen3.8-flash", "qwen3.7-plus");
    private static final Set<String> GEMINI = Set.of("gemini-4-argon", "gemini-3.8-flash", "gemini-3.6-flash", "gemini-3.5-flash-lite");

    public record ParameterRule(String type, boolean nullable, Number minimum, Number maximum,
            List<?> enumValues, Object fixedValue, String sendWhen, String defaultPolicy, Integer precision) {}

    public static ModelRequestOptions forModel(String providerKey, String modelName) {
        AiModelConfig config = new AiModelConfig();
        config.setProviderKey(providerKey);
        config.setModel(modelName);
        return forConfiguration(config, null);
    }

    public static int inputWindow(String providerKey, String modelName, int contextWindow) {
        String model = ProviderCapabilities.normalizeModelName(providerKey, modelName);
        return OPENAI.contains(model) && !gateway(providerKey) ? Math.min(contextWindow, 922_000) : contextWindow;
    }

    static boolean gateway(String key) {
        return "openrouter".equalsIgnoreCase(key) || "ollama".equalsIgnoreCase(key)
                || "litellm".equalsIgnoreCase(key) || "oneapi".equalsIgnoreCase(key);
    }

    public static ModelRequestOptions forConfiguration(AiModelConfig config, ProviderCapabilities caps) {
        String model = ProviderCapabilities.normalizeModelName(config.getProviderKey(), config.getModel());
        String family = family(config.getProviderKey(), model);
        boolean required = family.equals("openai6") && Set.of("gpt-6-astra", "gpt-6.1-sol").contains(model)
                || family.equals("glm") || family.equals("gemini")
                || family.equals("kimi") && !model.equals("kimi-k2.6");
        List<String> efforts = switch (family) {
            case "openai6" -> List.of("low", "medium", "high", "xhigh", "max");
            case "glm", "deepseek" -> List.of("low", "high", "max");
            case "kimi" -> model.startsWith("kimi-k3") ? List.of("low", "high", "max") : List.of();
            case "gemini" -> List.of("minimal", "low", "medium", "high");
            case "mimo", "qwen" -> List.of();
            default -> GENERAL;
        };
        boolean official = !Set.of("generic", "openrouter", "ollama", "gateway").contains(family);
        Boolean defaultThinking = official ? Boolean.TRUE : null;
        String defaultEffort = switch (family) {
            case "openai6" -> model.equals("gpt-6-astra") ? null : "medium";
            case "gemini" -> model.equals("gemini-3.5-flash-lite") ? "minimal" : "medium";
            case "glm" -> "max";
            case "kimi" -> model.startsWith("kimi-k3") ? "max" : null;
            case "deepseek" -> "high";
            default -> null;
        };
        boolean thinking = required || (config.getThinkingEnabled() != null ? config.getThinkingEnabled() == 1
                : defaultThinking != null ? defaultThinking : caps != null && caps.supportsReasoning());
        String sampling = family.equals("kimi") ? "never"
                : Set.of("openai6", "deepseek", "mimo").contains(family) ? "thinking_disabled" : "always";
        boolean temperature = sampling.equals("always") || sampling.equals("thinking_disabled") && !thinking;
        Double fixedTemperature = family.equals("kimi") ? Double.valueOf(thinking ? 1.0 : 0.6)
                : family.equals("mimo") && thinking ? Double.valueOf(1.0) : null;
        Map<String, ParameterRule> rules = new LinkedHashMap<>();
        rules.put("thinkingEnabled", new ParameterRule("integer", true, null, null,
                required ? List.of(1) : List.of(0, 1), required ? 1 : null, "explicit", "provider", null));
        rules.put("reasoningEffort", new ParameterRule("string", true, null, null, efforts, null, "thinking_enabled", "provider", null));
        rules.put("temperature", new ParameterRule("number", true, 0, family.equals("mimo") ? 1.5 : family.equals("glm") ? 1.0 : 2.0,
                List.of(), fixedTemperature, sampling, "provider", family.equals("glm") ? 2 : null));
        rules.put("contextWindowTokens", new ParameterRule("integer", true, 1,
                caps == null ? null : inputWindow(config.getProviderKey(), config.getModel(), caps.contextWindowTokens()),
                List.of(), null, "local_only", "model_limit", null));
        rules.put("maxOutputTokens", new ParameterRule("integer", true, 1,
                caps == null ? null : caps.maxOutputTokens(), List.of(), null, "always", "model_limit", null));
        return new ModelRequestOptions(required, efforts, temperature, family.equals("openai6"), defaultThinking,
                defaultEffort, family, model, DynamicModelProvider.normalizeProtocol(config.getProtocol()),
                official ? "official" : gateway(config.getProviderKey()) ? "gateway" : "conservative",
                sourceUrl(family), official ? "2026-11-04" : null, Map.copyOf(rules));
    }

    private static String family(String provider, String model) {
        if ("openrouter".equalsIgnoreCase(provider)) return "openrouter";
        if ("ollama".equalsIgnoreCase(provider)) return "ollama";
        if (gateway(provider)) return "gateway";
        if (OPENAI.contains(model)) return "openai6";
        if (KIMI.contains(model)) return "kimi";
        if (GLM.contains(model)) return "glm";
        if (MIMO.contains(model)) return "mimo";
        if (QWEN.contains(model)) return "qwen";
        if (GEMINI.contains(model)) return "gemini";
        if (Set.of("deepseek-flash", "deepseek-v4-pro").contains(model)) return "deepseek";
        return "generic";
    }

    private static String sourceUrl(String family) {
        return switch (family) {
            case "openai6" -> "https://developers.openai.com/api/docs/guides/latest-model";
            case "kimi" -> "https://platform.kimi.com/docs/api/models-overview";
            case "glm" -> "https://docs.bigmodel.cn/cn/guide/models/text/glm-5.3";
            case "mimo" -> "https://mimo.mi.com/docs/en-US/api/guidance/model-hyperparameters";
            case "qwen" -> "https://help.aliyun.com/en/model-studio/deep-thinking";
            case "gemini" -> "https://aistudio.google.com/docs/openai";
            case "deepseek" -> "https://api-docs.deepseek.com/guides/thinking_mode/";
            case "openrouter" -> "https://openrouter.ai/docs/guides/best-practices/reasoning-tokens";
            case "ollama" -> "https://docs.ollama.com/api/openai-compatibility";
            default -> null;
        };
    }
}
