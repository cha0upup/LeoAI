package org.leo.ai.channel;

import com.alibaba.fastjson.JSON;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiResponsesChatModel;
import dev.langchain4j.model.openai.OpenAiResponsesStreamingChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import org.leo.core.entity.AiModelConfig;
import org.leo.core.entity.ProviderCapabilities;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 动态模型提供者：从 {@link AiModelConfigService#getActive()} 读取激活配置，
 * 构建并维护 OpenAI Responses API / Chat Completions 兼容的流式 / 非流式模型实例。
 *
 * <p>模型配置以数据库中的激活记录为唯一来源。
 * 外部调用 {@link #refresh()} 可热切换底层模型。
 */
@Component
public class DynamicModelProvider {

    private static final Logger log = LoggerFactory.getLogger(DynamicModelProvider.class);

    public static final String PROTOCOL_RESPONSES = "responses";
    public static final String PROTOCOL_CHAT_COMPLETIONS = "chat_completions";
    public static final String RESPONSES_PATH = "/responses";
    public static final String CHAT_COMPLETIONS_PATH = "/chat/completions";

    private static final Duration STREAMING_TIMEOUT = Duration.ofMinutes(5);
    private static final Duration BLOCKING_TIMEOUT = Duration.ofMinutes(2);

    private final AiModelConfigService configService;
    private final DelegatingChatModel chatModel;

    public DynamicModelProvider(AiModelConfigService configService,
                                DelegatingChatModel chatModel) {
        this.configService = configService;
        this.chatModel = chatModel;
    }

    @PostConstruct
    public void init() {
        try {
            AiModelConfig active = configService.getActive();
            if (active != null) {
                log.info("从数据库加载激活模型配置: {} (id={}, model={})",
                        active.getName(), active.getId(), active.getModel());
                refreshFromConfig(active);
            } else {
                clearModels();
                log.info("数据库无激活模型配置，AI 模型保持未初始化");
            }
        } catch (Exception e) {
            clearModels();
            log.warn("初始化动态模型失败: {}", e.getMessage());
        }
    }

    /** 热切换模型：从数据库重新加载激活配置并重建模型实例。 */
    public synchronized void refresh() {
        AiModelConfig active = configService.getActive();
        if (active == null) {
            clearModels();
            log.info("数据库无激活模型配置，AI 模型已清空");
            return;
        }
        refreshFromConfig(active);
    }

    @TransactionalEventListener(fallbackExecution = true)
    public synchronized void onConfigurationChanged(AiModelConfigurationChanged event) {
        try {
            // Read only: reuse the just-committed connection. REQUIRES_NEW would need a second
            // pooled connection while the original one is still held by transaction cleanup.
            refresh();
        } catch (RuntimeException error) {
            // A committed disable/capability change must never keep an old credential alive.
            clearModels();
            log.warn("模型配置已保存，但当前默认模型不可用: {}", error.getMessage());
        }
    }

    /** 根据指定配置重建模型。 */
    public void refreshFromConfig(AiModelConfig config) {
        ModelRuntime runtime = buildRuntime(config);
        chatModel.setDelegate(runtime.chatModel());
        log.info("模型已切换 — protocol={}, model={}, maxTokens={}, reasoning={}",
                runtime.protocol(), runtime.modelName(), runtime.maxTokens(), runtime.doReasoning());
    }

    private void clearModels() {
        chatModel.clearDelegate();
    }

    public ModelRuntime buildRuntime(AiModelConfig config) {
        return buildRuntime(config, false, null, false);
    }

    /** 构建带有单次会话推理强度覆盖的运行时，不修改持久化模型配置。 */
    public ModelRuntime buildRuntime(AiModelConfig config, String reasoningEffortOverride) {
        return buildRuntime(config, false, normalizeReasoningEffortOverride(reasoningEffortOverride), false);
    }

    /**
     * 构建用于管理员能力探测的运行时。forceReasoning 仅用于探针请求，
     * 不会改变已保存的模型配置或普通会话的推理策略。
     */
    public ModelRuntime buildProbeRuntime(AiModelConfig config, boolean forceReasoning) {
        return buildRuntime(config, forceReasoning, null, true);
    }

    private ModelRuntime buildRuntime(AiModelConfig config, boolean forceReasoning, String reasoningEffortOverride, boolean probe) {
        String apiKey = config.getApiKey();
        if (apiKey == null || apiKey.isEmpty()) {
            throw new IllegalArgumentException("模型配置 apiKey 为空，id=" + config.getId());
        }
        boolean responsesApi = useResponsesApi(config);
        String baseUrl = ModelEndpoint.apiRoot(config);
        ModelPlan plan = plan(config, forceReasoning, reasoningEffortOverride, probe);
        StreamingChatModel streaming = responsesApi
                ? buildResponsesStreaming(apiKey, baseUrl, plan)
                : buildChatStreaming(apiKey, baseUrl, plan);
        ChatModel blocking = responsesApi
                ? buildResponsesBlocking(apiKey, baseUrl, plan)
                : buildChatBlocking(apiKey, baseUrl, plan);
        return new ModelRuntime(streaming, blocking, resolveProtocol(config),
                config.getProviderKey(), baseUrl, plan.modelName, plan.maxTokens,
                plan.doReasoning, plan.reasoningEffort,
                plan.supportsFunctionCalling, plan.parallelToolCalls, plan.contextWindowTokens);
    }

    public static String runtimeCacheKey(AiModelConfig config) {
        if (config == null) return "";
        return ModelConfigurationKey.digest(
                config.getId(),
                config.getProviderId(),
                config.getProviderKey(),
                config.getBaseUrl(),
                config.getApiKey(),
                config.getModel(),
                config.getProtocol(),
                config.getCompletionsPath(),
                config.getMaxOutputTokens(),
                config.getThinkingEnabled(),
                config.getReasoningEffort(),
                config.getContextWindowTokens(),
                config.getTemperature(),
                config.getHeadersJson(),
                config.getUpdateTime());
    }

    public static String runtimeCacheKey(AiModelConfig config, ModelRuntime runtime) {
        if (runtime == null) return runtimeCacheKey(config);
        return runtimeCacheKey(config,
                runtime.protocol(),
                runtime.providerKey(),
                runtime.effectiveBaseUrl(),
                runtime.modelName(),
                runtime.maxTokens(),
                runtime.doReasoning(),
                runtime.reasoningEffort(),
                runtime.supportsFunctionCalling(),
                runtime.parallelToolCalls(),
                runtime.contextWindowTokens());
    }

    public String plannedRuntimeCacheKey(AiModelConfig config) {
        return plannedRuntimeCacheKey(config, null);
    }

    public String plannedRuntimeCacheKey(AiModelConfig config, String reasoningEffortOverride) {
        if (config == null) return "";
        String baseUrl = ModelEndpoint.apiRoot(config);
        ModelPlan plan = plan(config, false, normalizeReasoningEffortOverride(reasoningEffortOverride), false);
        return runtimeCacheKey(config,
                resolveProtocol(config),
                config.getProviderKey(),
                baseUrl,
                plan.modelName,
                plan.maxTokens,
                plan.doReasoning,
                plan.reasoningEffort,
                plan.supportsFunctionCalling,
                plan.parallelToolCalls, plan.contextWindowTokens);
    }

    private static String runtimeCacheKey(AiModelConfig config,
                                          String protocol,
                                          String providerKey,
                                          String effectiveBaseUrl,
                                          String modelName,
                                          int maxTokens,
                                          boolean doReasoning,
                                          String reasoningEffort,
                                          boolean supportsFunctionCalling,
                                          boolean parallelToolCalls, int contextWindowTokens) {
        return ModelConfigurationKey.digest(
                runtimeCacheKey(config),
                protocol,
                providerKey,
                effectiveBaseUrl,
                modelName,
                maxTokens,
                doReasoning,
                reasoningEffort,
                supportsFunctionCalling,
                parallelToolCalls, contextWindowTokens);
    }

    public static String runtimeSnapshotJson(AiModelConfig config, ModelRuntime runtime) {
        LinkedHashMap<String, Object> snapshot = new LinkedHashMap<>();
        if (config != null) {
            snapshot.put("configId", config.getId());
            snapshot.put("configName", config.getName());
            snapshot.put("providerId", config.getProviderId());
            snapshot.put("providerName", config.getProviderName());
            snapshot.put("thinkingEnabled", config.getThinkingEnabled());
            snapshot.put("contextWindowTokens", config.getContextWindowTokens());
            snapshot.put("temperature", config.getTemperature());
            snapshot.put("cacheKey", runtimeCacheKey(config, runtime));
        }
        if (runtime != null) {
            snapshot.put("providerKey", runtime.providerKey());
            snapshot.put("model", runtime.modelName());
            snapshot.put("protocol", runtime.protocol());
            snapshot.put("effectiveBaseUrl", runtime.effectiveBaseUrl());
            snapshot.put("maxOutputTokens", runtime.maxTokens());
            snapshot.put("effectiveContextWindowTokens", runtime.contextWindowTokens());
            snapshot.put("reasoning", runtime.doReasoning());
            snapshot.put("reasoningEffort", runtime.reasoningEffort());
            snapshot.put("supportsFunctionCalling", runtime.supportsFunctionCalling());
            snapshot.put("parallelToolCalls", runtime.parallelToolCalls());
        }
        return JSON.toJSONString(snapshot);
    }

    // ── 计划构造 ──────────────────────────────────────────────────────────

    private ModelPlan plan(AiModelConfig config, boolean forceReasoning, String reasoningEffortOverride, boolean probe) {
        String modelName = config.getModel();
        String providerKey = config.getProviderKey();
        String modelKey = ProviderCapabilities.normalizeModelName(providerKey, modelName);
        ProviderCapabilities caps = configService.capabilitiesForModel(config);
        if (!probe && !caps.supportsTextGeneration()) {
            throw new IllegalArgumentException("模型不支持文本生成，不能用于对话调用: " + modelName);
        }
        if (!probe && !caps.supportsStreaming()) {
            throw new IllegalArgumentException("模型不支持流式输出，不能用于当前对话通道: " + modelName);
        }

        Boolean userIntent = probe ? Boolean.valueOf(forceReasoning) : toBoolean(config.getThinkingEnabled());
        String reasoningEffort = probe ? null : reasoningEffortOverride != null
                ? reasoningEffortOverride : config.getReasoningEffort();
        boolean wantsReasoning = userIntent != null ? userIntent : caps.supportsReasoning();

        boolean requiresReasoning = modelKey.startsWith("glm-5.3") || modelKey.startsWith("gemini-3.");
        if (!probe && requiresReasoning && Boolean.FALSE.equals(userIntent)) {
            throw new IllegalArgumentException(modelName + " 不支持关闭思考，请使用 auto 或 low 推理强度");
        }
        boolean doReasoning = requiresReasoning || wantsReasoning && (forceReasoning || caps.supportsReasoning());
        if (!probe && wantsReasoning && !caps.supportsReasoning()) {
            log.warn("模型 {} 不支持 reasoning_content，配置将被忽略", modelName);
        }

        Integer maxTokens = positive(config.getMaxOutputTokens());
        int effectiveMaxTokens = probe ? (doReasoning ? 1024 : 256) : maxTokens != null
                ? Math.min(maxTokens, caps.maxOutputTokens())
                : caps.maxOutputTokens();
        if (effectiveMaxTokens <= 0) throw new IllegalArgumentException("模型最大输出长度必须大于 0");
        String effectiveReasoningEffort = doReasoning && reasoningEffort != null
                && !reasoningEffort.isBlank() && !"auto".equalsIgnoreCase(reasoningEffort)
                ? normalizeReasoningEffortForModel(modelKey, reasoningEffort)
                : null;
        if (probe && requiresReasoning) effectiveReasoningEffort = "low";
        if (!doReasoning && caps.supportsReasoning() && useResponsesApi(config)) effectiveReasoningEffort = "none";
        Map<String, Object> customParameters = chatCustomParameters(modelKey, doReasoning);
        boolean sendThinking = doReasoning && (modelKey.startsWith("deepseek-") || modelKey.startsWith("glm-")
                || modelKey.startsWith("mimo-") || modelKey.startsWith("qwen"));
        boolean parallelToolCalls = !probe && caps.supportsFunctionCalling()
                && caps.supportsParallelToolCalls();
        boolean accumulateToolCallId = !usesRepeatedToolCallId(providerKey, modelName);
        Double temperature = probe || (doReasoning && (modelKey.startsWith("deepseek-") || modelKey.startsWith("mimo-")))
                ? null
                : config.getTemperature();
        return new ModelPlan(modelName, effectiveMaxTokens, doReasoning,
                effectiveReasoningEffort, temperature, ModelEndpoint.headers(config.getHeadersJson()),
                customParameters, sendThinking, accumulateToolCallId,
                caps.supportsFunctionCalling(), parallelToolCalls,
                config.getContextWindowTokens() != null && config.getContextWindowTokens() > 0
                        ? Math.min(config.getContextWindowTokens(), caps.contextWindowTokens()) : caps.contextWindowTokens(),
                modelKey.startsWith("mimo-"), probe);
    }

    private static String normalizeReasoningEffortOverride(String value) {
        if (value == null || value.isBlank() || "auto".equalsIgnoreCase(value)) return null;
        String normalized = value.trim().toLowerCase();
        return switch (normalized) {
            case "minimal", "low", "medium", "high", "xhigh", "max" -> normalized;
            default -> throw new IllegalArgumentException("reasoningEffort 只支持 auto/minimal/low/medium/high/xhigh/max");
        };
    }

    // ── SDK builder ─────────────────────────────────────────────────────

    private StreamingChatModel buildResponsesStreaming(String apiKey, String baseUrl, ModelPlan plan) {
        var builder = OpenAiResponsesStreamingChatModel.builder()
                .httpClientBuilder(new ModelHttpClientBuilder(plan.customHeaders, timeout(plan, true)))
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .modelName(plan.modelName)
                .store(false)
                .strictTools(false)
                .maxOutputTokens(plan.maxTokens);
        if (!plan.probe && plan.supportsFunctionCalling) builder.parallelToolCalls(plan.parallelToolCalls);
        if (plan.reasoningEffort != null) builder.reasoningEffort(plan.reasoningEffort);
        if (plan.doReasoning) builder.reasoningSummary("auto");
        if (plan.temperature != null) builder.temperature(plan.temperature);
        return builder.build();
    }

    private ChatModel buildResponsesBlocking(String apiKey, String baseUrl, ModelPlan plan) {
        var builder = OpenAiResponsesChatModel.builder()
                .httpClientBuilder(new ModelHttpClientBuilder(plan.customHeaders, timeout(plan, false)))
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .modelName(plan.modelName)
                .store(false)
                .strictTools(false)
                .maxOutputTokens(plan.maxTokens);
        if (!plan.probe && plan.supportsFunctionCalling) builder.parallelToolCalls(plan.parallelToolCalls);
        if (plan.reasoningEffort != null) builder.reasoningEffort(plan.reasoningEffort);
        if (plan.doReasoning) builder.reasoningSummary("auto");
        if (plan.temperature != null) builder.temperature(plan.temperature);
        return builder.build();
    }

    private StreamingChatModel buildChatStreaming(String apiKey, String baseUrl, ModelPlan plan) {
        var builder = OpenAiStreamingChatModel.builder()
                .httpClientBuilder(new ModelHttpClientBuilder(plan.customHeaders, timeout(plan, true)))
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .modelName(plan.modelName)
                .maxTokens(plan.useMaxCompletionTokens ? null : plan.maxTokens)
                .maxCompletionTokens(plan.useMaxCompletionTokens ? plan.maxTokens : null)
                .timeout(timeout(plan, true))
                .returnThinking(plan.doReasoning)
                .sendThinking(plan.sendThinking)
                .accumulateToolCallId(plan.accumulateToolCallId);
        if (!plan.probe && plan.supportsFunctionCalling) builder.parallelToolCalls(plan.parallelToolCalls);
        if (plan.reasoningEffort != null) builder.reasoningEffort(plan.reasoningEffort);
        if (plan.temperature != null) builder.temperature(plan.temperature);
        if (!plan.customParameters.isEmpty()) builder.customParameters(plan.customParameters);
        return builder.build();
    }

    private ChatModel buildChatBlocking(String apiKey, String baseUrl, ModelPlan plan) {
        var builder = OpenAiChatModel.builder()
                .httpClientBuilder(new ModelHttpClientBuilder(plan.customHeaders, timeout(plan, false)))
                .maxRetries(plan.probe ? 0 : 2)
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .modelName(plan.modelName)
                .maxTokens(plan.useMaxCompletionTokens ? null : plan.maxTokens)
                .maxCompletionTokens(plan.useMaxCompletionTokens ? plan.maxTokens : null)
                .timeout(timeout(plan, false))
                .returnThinking(plan.doReasoning)
                .sendThinking(plan.sendThinking);
        if (!plan.probe && plan.supportsFunctionCalling) builder.parallelToolCalls(plan.parallelToolCalls);
        if (plan.reasoningEffort != null) builder.reasoningEffort(plan.reasoningEffort);
        if (plan.temperature != null) builder.temperature(plan.temperature);
        if (!plan.customParameters.isEmpty()) builder.customParameters(plan.customParameters);
        return builder.build();
    }

    // ── 工具方法 ──────────────────────────────────────────────────────────

    private static Boolean toBoolean(Integer flag) {
        if (flag == null) return null;
        return flag > 0;
    }

    private static Integer positive(Integer value) {
        return value != null && value > 0 ? value : null;
    }

    private static Duration timeout(ModelPlan plan, boolean streaming) {
        return plan.probe ? Duration.ofSeconds(30) : streaming ? STREAMING_TIMEOUT : BLOCKING_TIMEOUT;
    }

    private static String normalizeReasoningEffortForModel(String model, String reasoningEffort) {
        String effort = normalizeReasoningEffortOverride(reasoningEffort);
        if ((model.startsWith("deepseek-") || model.startsWith("glm-5.3"))
                && !java.util.Set.of("low", "high", "max").contains(effort)) {
            throw new IllegalArgumentException(model + " 的推理强度只支持 auto/low/high/max");
        }
        if (model.startsWith("gemini-3.") && !java.util.Set.of("minimal", "low", "medium", "high").contains(effort)) {
            throw new IllegalArgumentException(model + " 的推理强度只支持 auto/minimal/low/medium/high");
        }
        if (model.startsWith("mimo-") || model.startsWith("qwen")) {
            throw new IllegalArgumentException(model + " 请使用 auto 推理强度，通过 thinkingEnabled 控制思考开关");
        }
        return effort;
    }

    private static Map<String, Object> chatCustomParameters(String model, boolean doReasoning) {
        if (model.startsWith("deepseek-") || model.startsWith("mimo-") || model.startsWith("glm-5.3")) {
            return Map.of("thinking", Map.of("type", doReasoning ? "enabled" : "disabled"));
        }
        if (model.startsWith("qwen")) return Map.of("enable_thinking", doReasoning);
        return Map.of();
    }

    private static boolean usesRepeatedToolCallId(String providerKey, String modelName) {
        String provider = providerKey == null ? "" : providerKey.toLowerCase();
        String model = modelName == null ? "" : modelName.toLowerCase();
        return provider.contains("deepseek")
                || provider.contains("qwen")
                || provider.contains("dashscope")
                || model.contains("deepseek")
                || model.contains("qwen");
    }

    public static String resolveEffectiveBaseUrl(AiModelConfig config) {
        return ModelEndpoint.apiRoot(config);
    }

    public static boolean useResponsesApi(AiModelConfig config) {
        return PROTOCOL_RESPONSES.equals(resolveProtocol(config));
    }

    public static String resolveProtocol(AiModelConfig config) {
        return normalizeProtocol(config == null ? null : config.getProtocol());
    }

    public static String normalizeProtocol(String value) {
        if (value == null || value.isBlank()) return PROTOCOL_CHAT_COMPLETIONS;
        String protocol = value.trim();
        if (!PROTOCOL_RESPONSES.equals(protocol) && !PROTOCOL_CHAT_COMPLETIONS.equals(protocol)) {
            throw new IllegalArgumentException("protocol 只支持 responses/chat_completions");
        }
        return protocol;
    }

    public static String defaultPathForProtocol(String protocol) {
        return PROTOCOL_RESPONSES.equals(normalizeProtocol(protocol)) ? RESPONSES_PATH : CHAT_COMPLETIONS_PATH;
    }

    private record ModelPlan(String modelName,
                             int maxTokens,
                             boolean doReasoning,
                             String reasoningEffort,
                             Double temperature,
                             Map<String, String> customHeaders,
                             Map<String, Object> customParameters,
                             boolean sendThinking,
                             boolean accumulateToolCallId,
                             boolean supportsFunctionCalling,
                             boolean parallelToolCalls, int contextWindowTokens, boolean useMaxCompletionTokens, boolean probe) {}

    public record ModelRuntime(StreamingChatModel streamingModel,
                               ChatModel chatModel,
                               String protocol,
                               String providerKey,
                               String effectiveBaseUrl,
                               String modelName,
                               int maxTokens,
                               boolean doReasoning,
                               String reasoningEffort,
                               boolean supportsFunctionCalling,
                               boolean parallelToolCalls, int contextWindowTokens) {}
}
