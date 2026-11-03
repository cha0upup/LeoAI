package org.leo.ai.channel;

import org.leo.core.entity.ProviderCapabilities;

import java.util.List;

/** Request constraints shared by runtime validation and the configuration UI. */
public record ModelRequestOptions(boolean thinkingRequired, List<String> reasoningEfforts,
                                  boolean temperatureConfigurable, boolean responsesRequiredForTools) {
    private static final List<String> GENERAL = List.of("minimal", "low", "medium", "high", "xhigh", "max");

    public static int inputWindow(String providerKey, String modelName, int contextWindow) {
        String model = ProviderCapabilities.normalizeModelName(providerKey, modelName);
        return model.startsWith("gpt-6") && !"openrouter".equalsIgnoreCase(providerKey)
                && !"ollama".equalsIgnoreCase(providerKey) ? Math.min(contextWindow, 922_000) : contextWindow;
    }

    public static ModelRequestOptions forModel(String providerKey, String modelName) {
        // Gateways expose their own parameter contracts; do not send a vendor's private fields there.
        if ("openrouter".equalsIgnoreCase(providerKey) || "ollama".equalsIgnoreCase(providerKey)) {
            return new ModelRequestOptions(false, GENERAL, true, false);
        }
        String model = ProviderCapabilities.normalizeModelName(providerKey, modelName);
        if (model.startsWith("gpt-6")) {
            boolean required = model.equals("gpt-6-astra") || model.equals("gpt-6.1-sol");
            return new ModelRequestOptions(required, List.of("low", "medium", "high", "xhigh", "max"), true, true);
        }
        if (model.startsWith("kimi-k3") || model.startsWith("glm-5.3")) {
            return new ModelRequestOptions(true, List.of("low", "high", "max"), !model.startsWith("kimi-"), false);
        }
        if (model.startsWith("kimi-k2.7-code")) return new ModelRequestOptions(true, List.of(), false, false);
        if (model.startsWith("kimi-k2.6")) return new ModelRequestOptions(false, List.of(), false, false);
        if (model.startsWith("gemini-3.") || model.startsWith("gemini-4-")) {
            return new ModelRequestOptions(true, List.of("minimal", "low", "medium", "high"), true, false);
        }
        if (model.startsWith("deepseek-")) return new ModelRequestOptions(false, List.of("low", "high", "max"), true, false);
        if (model.startsWith("mimo-") || model.startsWith("qwen")) {
            return new ModelRequestOptions(false, List.of(), true, false);
        }
        return new ModelRequestOptions(false, GENERAL, true, false);
    }
}
