package org.leo.ai.channel;

import org.leo.core.entity.AiModelConfig;
import org.leo.core.entity.ProviderCapabilities;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Pure request planning. Preview and inference consume the same resolved values. */
public record ModelRequestPlan(String modelName, int maxTokens, boolean doReasoning, String reasoningEffort,
        Double temperature, Map<String, String> customHeaders, Map<String, Object> customParameters,
        boolean sendThinking, boolean returnThinking, boolean accumulateToolCallId,
        boolean supportsFunctionCalling, boolean parallelToolCalls, int contextWindowTokens,
        boolean useMaxCompletionTokens, boolean probe, Preview preview) {

    public record FieldError(String field, String message) {}
    public record ParameterValue(String field, String status, Object configuredValue, Object effectiveValue, String reason) {}
    public record Preview(boolean valid, String model, String protocol, ModelRequestOptions contract,
            Map<String, Object> requestParameters, List<ParameterValue> parameters, List<FieldError> errors) {}

    public static final class ValidationException extends IllegalArgumentException {
        private final List<FieldError> errors;
        ValidationException(List<FieldError> errors) {
            super(errors.get(0).field() + ": " + errors.get(0).message());
            this.errors = List.copyOf(errors);
        }
        public List<FieldError> errors() { return errors; }
    }

    public static Preview preview(AiModelConfig config, ProviderCapabilities caps) {
        try {
            return resolve(config, caps, null, false, false).preview();
        } catch (ValidationException error) {
            return new Preview(false, config.getModel(), DynamicModelProvider.resolveProtocol(config),
                    ModelRequestOptions.forConfiguration(config, caps), Map.of(), List.of(), error.errors());
        }
    }

    public static ModelRequestPlan resolve(AiModelConfig config, ProviderCapabilities caps,
            String effortOverride, boolean forceReasoning, boolean probe) {
        ModelRequestOptions options = ModelRequestOptions.forConfiguration(config, caps);
        String family = options.family();
        String override = normalizedEffort(effortOverride);
        String effort = override != null ? override : normalizedEffort(config.getReasoningEffort());
        if (!probe) validate(config, options, effort);
        Boolean intent = probe ? Boolean.valueOf(forceReasoning)
                : config.getThinkingEnabled() == null ? null : Boolean.valueOf(config.getThinkingEnabled() == 1);
        boolean thinking = options.thinkingRequired() || (intent != null ? intent
                : options.defaultThinking() != null ? options.defaultThinking() : caps.supportsReasoning());
        if (probe) effort = options.thinkingRequired() && !options.reasoningEfforts().isEmpty() ? "low" : null;
        String effectiveEffort = thinking ? effort : null;
        boolean responses = DynamicModelProvider.useResponsesApi(config);
        if (Boolean.FALSE.equals(intent) && !thinking
                && (responses || family.equals("openai6") || family.equals("ollama"))) effectiveEffort = "none";
        if (thinking && intent != null && intent && effectiveEffort == null && !options.thinkingRequired()
                && (responses || family.equals("ollama") || family.equals("openai6"))) {
            effectiveEffort = options.defaultReasoningEffort() != null ? options.defaultReasoningEffort() : "medium";
        }
        boolean needsResponses = options.responsesRequiredForTools()
                && !(options.modelName().equals("gpt-6-luna") && !thinking);
        if (!probe && needsResponses && caps.supportsFunctionCalling() && !responses) {
            throw invalid("protocol", "该模型的工具调用请使用 Responses API");
        }
        int max = probe ? thinking ? 1024 : 256
                : config.getMaxOutputTokens() == null ? caps.maxOutputTokens() : Math.min(config.getMaxOutputTokens(), caps.maxOutputTokens());
        if (max <= 0) throw invalid("maxOutputTokens", "模型输出上限应大于 0");
        int context = ModelRequestOptions.inputWindow(config.getProviderKey(), config.getModel(), caps.contextWindowTokens());
        if (!probe && config.getContextWindowTokens() != null) context = Math.min(context, config.getContextWindowTokens());
        Map<String, Object> custom = new LinkedHashMap<>();
        if (intent != null) {
            if (family.equals("openrouter")) {
                custom.put("reasoning", !thinking ? Map.of("enabled", false)
                        : effectiveEffort == null ? Map.of("enabled", true) : Map.of("effort", effectiveEffort));
            } else if (family.equals("qwen")) {
                custom.put("enable_thinking", thinking);
            } else if (Set.of("deepseek", "glm", "mimo").contains(family)
                    || family.equals("kimi") && options.modelName().equals("kimi-k2.6")) {
                custom.put("thinking", Map.of("type", thinking ? "enabled" : "disabled"));
            }
        } else if (family.equals("openrouter") && effectiveEffort != null) {
            custom.put("reasoning", Map.of("effort", effectiveEffort));
        }
        if (family.equals("openrouter") && !responses) effectiveEffort = null;
        Double temperature = probe || !options.temperatureConfigurable() ? null : config.getTemperature();
        boolean parallel = !probe && caps.supportsFunctionCalling() && caps.supportsParallelToolCalls();
        boolean maxCompletion = family.equals("mimo") || family.equals("openai6")
                || family.equals("kimi") && options.modelName().startsWith("kimi-k3");
        boolean sendThinking = (intent == null || thinking) && Set.of("deepseek", "glm", "mimo", "qwen", "kimi").contains(family);
        String provider = config.getProviderKey() == null ? "" : config.getProviderKey().toLowerCase(Locale.ROOT);
        String model = config.getModel() == null ? "" : config.getModel().toLowerCase(Locale.ROOT);
        boolean accumulate = !(provider.contains("deepseek") || provider.contains("qwen") || provider.contains("dashscope")
                || model.contains("deepseek") || model.contains("qwen"));
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("model", config.getModel());
        parameters.put(responses ? "max_output_tokens" : maxCompletion ? "max_completion_tokens" : "max_tokens", max);
        if (responses) {
            parameters.put("store", false);
            Map<String, Object> reasoning = new LinkedHashMap<>();
            if (effectiveEffort != null) reasoning.put("effort", effectiveEffort);
            if (thinking) reasoning.put("summary", "auto");
            if (!reasoning.isEmpty()) parameters.put("reasoning", Map.copyOf(reasoning));
        } else {
            parameters.putAll(custom);
            if (effectiveEffort != null) parameters.put("reasoning_effort", effectiveEffort);
        }
        if (!probe && caps.supportsFunctionCalling()) parameters.put("parallel_tool_calls", parallel);
        if (temperature != null) parameters.put("temperature", temperature);
        boolean effortSent = parameters.containsKey("reasoning_effort")
                || parameters.get("reasoning") instanceof Map<?, ?> reasoning && reasoning.containsKey("effort");
        boolean modeSent = parameters.containsKey("thinking") || parameters.containsKey("enable_thinking")
                || intent != null && effortSent
                || parameters.get("reasoning") instanceof Map<?, ?> reasoning && reasoning.containsKey("enabled");
        List<ParameterValue> values = new ArrayList<>();
        values.add(new ParameterValue("thinkingEnabled", intent == null || options.thinkingRequired() && !modeSent
                ? "default" : modeSent ? "sent" : "omitted", config.getThinkingEnabled(),
                intent == null ? options.defaultThinking() : Boolean.valueOf(thinking),
                intent == null ? "沿用厂商默认，不发送思考开关" : modeSent ? "按当前协议转换思考开关" : "当前协议未发送独立开关，沿用厂商行为"));
        values.add(new ParameterValue("reasoningEffort", effortSent ? "sent" : effort == null ? "default" : "omitted", config.getReasoningEffort(),
                effortSent ? effectiveEffort != null ? effectiveEffort : effort : null,
                effort == null ? "自动等级沿用厂商默认；显式开关需要时使用契约默认值" : thinking ? "按协议转换等级" : "关闭思考时省略等级"));
        values.add(new ParameterValue("temperature", temperature != null ? "sent" : config.getTemperature() == null ? "default" : "omitted",
                config.getTemperature(), temperature != null ? temperature : options.parameters().get("temperature").fixedValue(),
                temperature != null ? "发送已配置采样值" : options.temperatureConfigurable() ? "留空沿用厂商默认" : "当前模型或思考模式使用固定采样策略，不发送该字段"));
        values.add(new ParameterValue("maxOutputTokens", config.getMaxOutputTokens() != null && max < config.getMaxOutputTokens() ? "clamped" : "sent",
                config.getMaxOutputTokens(), max, "输出上限包含厂商计入的思考 Token，并受模型能力上限限制"));
        values.add(new ParameterValue("contextWindowTokens", "local", config.getContextWindowTokens(), context,
                "仅约束本地对话预算，不作为厂商请求参数发送"));
        Preview preview = new Preview(true, config.getModel(), options.protocol(), options,
                Map.copyOf(parameters), List.copyOf(values), List.of());
        return new ModelRequestPlan(config.getModel(), max, thinking, effectiveEffort, temperature,
                ModelEndpoint.headers(config.getHeadersJson()), Map.copyOf(custom), sendThinking, intent == null || thinking,
                accumulate, caps.supportsFunctionCalling(), parallel, context, maxCompletion, probe, preview);
    }

    static String normalizedEffort(String effort) {
        String normalized = effort == null ? "" : effort.trim().toLowerCase(Locale.ROOT);
        return normalized.isEmpty() || normalized.equals("auto") ? null : normalized;
    }

    private static void validate(AiModelConfig config, ModelRequestOptions options, String effort) {
        List<FieldError> errors = new ArrayList<>();
        if (config.getModel() == null || config.getModel().isBlank()) errors.add(new FieldError("model", "请填写模型 ID"));
        Integer intent = config.getThinkingEnabled();
        if (intent != null && intent != 0 && intent != 1) errors.add(new FieldError("thinkingEnabled", "请选择厂商默认、开启或关闭"));
        if (options.thinkingRequired() && Integer.valueOf(0).equals(intent)) errors.add(new FieldError("thinkingEnabled", "该模型始终开启思考，请选择厂商默认或开启"));
        if (effort != null && !options.reasoningEfforts().contains(effort)) {
            errors.add(new FieldError("reasoningEffort", "可选等级为 auto" + (options.reasoningEfforts().isEmpty() ? "" : "/" + String.join("/", options.reasoningEfforts()))));
        }
        if (config.getMaxOutputTokens() != null && config.getMaxOutputTokens() <= 0) errors.add(new FieldError("maxOutputTokens", "输出上限应大于 0，或留空"));
        if (config.getContextWindowTokens() != null && config.getContextWindowTokens() <= 0) errors.add(new FieldError("contextWindowTokens", "上下文预算应大于 0，或留空"));
        Double temperature = config.getTemperature();
        Number maximum = options.parameters().get("temperature").maximum();
        if (temperature != null && (!Double.isFinite(temperature) || temperature < 0 || temperature > maximum.doubleValue())) {
            errors.add(new FieldError("temperature", "采样值应在 0 到 " + maximum + " 之间，或留空"));
        } else if (temperature != null && options.parameters().get("temperature").precision() != null
                && java.math.BigDecimal.valueOf(temperature).stripTrailingZeros().scale() > options.parameters().get("temperature").precision()) {
            errors.add(new FieldError("temperature", "该模型的采样值最多保留两位小数"));
        }
        if (!errors.isEmpty()) throw new ValidationException(errors);
    }

    private static ValidationException invalid(String field, String message) {
        return new ValidationException(List.of(new FieldError(field, message)));
    }
}
