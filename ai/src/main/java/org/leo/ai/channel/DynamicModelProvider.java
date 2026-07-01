package org.leo.ai.channel;

import com.alibaba.fastjson.JSON;
import dev.langchain4j.http.client.jdk.JdkHttpClientBuilder;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiResponsesChatModel;
import dev.langchain4j.model.openai.OpenAiResponsesStreamingChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import org.leo.core.entity.AiModelConfig;
import org.leo.core.entity.ModelDefaults;
import org.leo.core.entity.ProviderCapabilities;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 动态模型提供者：从 {@link AiModelConfigService#getActive()} 读取激活配置，
 * 构建并维护 OpenAI Responses API / Chat Completions 兼容的流式 / 非流式模型实例。
 *
 * <p>启动时若数据库无激活记录，回退到 application.properties 中的 fallback 值。
 * 外部调用 {@link #refresh()} 可热切换底层模型。
 */
@Component
@DependsOn("aiModelCapabilitySchemaMigrator")
public class DynamicModelProvider {

    private static final Logger log = LoggerFactory.getLogger(DynamicModelProvider.class);

    public static final String PROTOCOL_RESPONSES = "responses";
    public static final String PROTOCOL_CHAT_COMPLETIONS = "chat_completions";
    public static final String RESPONSES_PATH = "/responses";
    public static final String CHAT_COMPLETIONS_PATH = "/v1/chat/completions";

    private static final Duration STREAMING_TIMEOUT = Duration.ofMinutes(5);
    private static final Duration BLOCKING_TIMEOUT = Duration.ofMinutes(2);

    private final AiModelConfigService configService;
    private final DelegatingStreamingChatModel streamingModel;
    private final DelegatingChatModel chatModel;

    @Value("${leo.ai.openai.api-key:}")
    private String fallbackApiKey;

    @Value("${leo.ai.openai.model:gpt-4o}")
    private String fallbackModelName;

    @Value("${leo.ai.openai.thinking-enabled:false}")
    private boolean fallbackThinkingEnabled;

    @Value("${leo.ai.openai.base-url:https://api.openai.com}")
    private String fallbackBaseUrl;

    @Value("${leo.ai.openai.protocol:}")
    private String fallbackProtocol;

    @Value("${leo.ai.openai.completions-path:}")
    private String fallbackCompletionsPath;

    public DynamicModelProvider(AiModelConfigService configService,
                                DelegatingStreamingChatModel streamingModel,
                                DelegatingChatModel chatModel) {
        this.configService = configService;
        this.streamingModel = streamingModel;
        this.chatModel = chatModel;
    }

    @PostConstruct
    public void init() {
        configService.setDynamicModelProvider(this);
        try {
            AiModelConfig active = configService.getActive();
            if (active != null && active.getApiKey() != null && !active.getApiKey().isEmpty()) {
                log.info("从数据库加载激活模型配置: {} (id={}, model={})",
                        active.getName(), active.getId(), active.getModel());
                refreshFromConfig(active);
            } else {
                log.info("数据库无激活模型配置，使用 application.properties fallback 配置");
            }
        } catch (Exception e) {
            log.warn("初始化动态模型失败，保持 fallback 配置: {}", e.getMessage());
        }
    }

    /** 热切换模型：从数据库重新加载激活配置并重建模型实例。 */
    public void refresh() {
        AiModelConfig active = configService.getActive();
        if (active == null) {
            if (fallbackApiKey != null && !fallbackApiKey.isBlank()) {
                log.warn("refresh() 调用时无激活模型配置，回退到 application 配置");
                AiModelConfig fallback = new AiModelConfig();
                fallback.setId(-1);
                fallback.setName("application-fallback");
                fallback.setApiKey(fallbackApiKey);
                fallback.setBaseUrl(fallbackBaseUrl);
                fallback.setModel(fallbackModelName);
                fallback.setProtocol(normalizeProtocol(fallbackProtocol));
                fallback.setCompletionsPath(fallbackCompletionsPath);
                fallback.setThinkingEnabled(fallbackThinkingEnabled ? 1 : 0);
                refreshFromConfig(fallback);
                return;
            }
            log.warn("refresh() 调用时无激活模型配置且无 fallback apiKey，模型保持不变");
            return;
        }
        refreshFromConfig(active);
    }

    /** 根据指定配置重建模型。 */
    public void refreshFromConfig(AiModelConfig config) {
        ModelRuntime runtime;
        try {
            runtime = buildRuntime(config);
        } catch (IllegalArgumentException e) {
            log.warn("模型配置 (id={}) 不可用，跳过模型切换: {}", config.getId(), e.getMessage());
            return;
        }
        streamingModel.setDelegate(runtime.streamingModel());
        chatModel.setDelegate(runtime.chatModel());
        log.info("模型已切换 — protocol={}, model={}, maxTokens={}, reasoning={}",
                runtime.protocol(), runtime.modelName(), runtime.maxTokens(), runtime.doReasoning());
    }

    public ModelRuntime buildRuntime(AiModelConfig config) {
        String apiKey = config.getApiKey();
        if (apiKey == null || apiKey.isEmpty()) {
            throw new IllegalArgumentException("模型配置 apiKey 为空，id=" + config.getId());
        }
        boolean responsesApi = useResponsesApi(config);
        String baseUrl = responsesApi ? resolveResponsesBaseUrl(config) : resolveChatCompletionsBaseUrl(config);
        ModelPlan plan = plan(config);
        StreamingChatModel streaming = responsesApi
                ? buildResponsesStreaming(apiKey, baseUrl, plan)
                : buildChatStreaming(apiKey, baseUrl, plan);
        ChatModel blocking = responsesApi
                ? buildResponsesBlocking(apiKey, baseUrl, plan)
                : buildChatBlocking(apiKey, baseUrl, plan);
        return new ModelRuntime(streaming, blocking, resolveProtocol(config),
                config.getProviderKey(), baseUrl, plan.modelName, plan.maxTokens,
                plan.doReasoning, plan.reasoningEffort,
                plan.supportsFunctionCalling, plan.parallelToolCalls);
    }

    public static String runtimeCacheKey(AiModelConfig config) {
        if (config == null) return "";
        return Integer.toHexString(Objects.hash(
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
                config.getUpdateTime()));
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
                runtime.parallelToolCalls());
    }

    public String plannedRuntimeCacheKey(AiModelConfig config) {
        if (config == null) return "";
        boolean responsesApi = useResponsesApi(config);
        String baseUrl = responsesApi ? resolveResponsesBaseUrl(config) : resolveChatCompletionsBaseUrl(config);
        ModelPlan plan = plan(config);
        return runtimeCacheKey(config,
                resolveProtocol(config),
                config.getProviderKey(),
                baseUrl,
                plan.modelName,
                plan.maxTokens,
                plan.doReasoning,
                plan.reasoningEffort,
                plan.supportsFunctionCalling,
                plan.parallelToolCalls);
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
                                          boolean parallelToolCalls) {
        return Integer.toHexString(Objects.hash(
                runtimeCacheKey(config),
                protocol,
                providerKey,
                effectiveBaseUrl,
                modelName,
                maxTokens,
                doReasoning,
                reasoningEffort,
                supportsFunctionCalling,
                parallelToolCalls));
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
            snapshot.put("reasoning", runtime.doReasoning());
            snapshot.put("reasoningEffort", runtime.reasoningEffort());
            snapshot.put("supportsFunctionCalling", runtime.supportsFunctionCalling());
            snapshot.put("parallelToolCalls", runtime.parallelToolCalls());
        }
        return JSON.toJSONString(snapshot);
    }

    // ── 计划构造 ──────────────────────────────────────────────────────────

    private ModelPlan plan(AiModelConfig config) {
        String modelName = config.getModel();
        String providerKey = config.getProviderKey();
        ProviderCapabilities caps = configService.capabilitiesForModel(providerKey, modelName);
        if (!caps.supportsTextGeneration()) {
            throw new IllegalArgumentException("模型不支持文本生成，不能用于对话调用: " + modelName);
        }
        if (!caps.supportsStreaming()) {
            throw new IllegalArgumentException("模型不支持流式输出，不能用于当前对话通道: " + modelName);
        }

        Boolean userIntent = toBoolean(config.getThinkingEnabled());
        Boolean modelDefault = ModelDefaults.defaultThinkingEnabled(providerKey, modelName);
        String reasoningEffort = config.getReasoningEffort();
        boolean wantsReasoning;
        if (userIntent != null) {
            wantsReasoning = userIntent;
        } else if (reasoningEffort != null && !reasoningEffort.isBlank()
                && !"auto".equalsIgnoreCase(reasoningEffort)) {
            wantsReasoning = true;
        } else if (modelDefault != null) {
            wantsReasoning = modelDefault;
        } else {
            wantsReasoning = false;
        }

        boolean doReasoning = wantsReasoning && caps.supportsReasoning();
        if (wantsReasoning && !caps.supportsReasoning()) {
            log.warn("模型 {} 不支持 reasoning_content，配置将被忽略", modelName);
        }

        Integer maxTokens = positive(config.getMaxOutputTokens());
        int effectiveMaxTokens = maxTokens != null
                ? Math.min(maxTokens, caps.maxOutputTokens())
                : caps.maxOutputTokens();
        String effectiveReasoningEffort = doReasoning && reasoningEffort != null
                && !reasoningEffort.isBlank() && !"auto".equalsIgnoreCase(reasoningEffort)
                ? normalizeReasoningEffortForModel(modelName, reasoningEffort)
                : null;
        Map<String, Object> customParameters = chatCustomParameters(config, doReasoning, userIntent);
        boolean sendThinking = doReasoning && isDeepSeekLike(providerKey, modelName);
        boolean parallelToolCalls = caps.supportsFunctionCalling()
                && caps.supportsParallelToolCalls()
                && !usesRepeatedToolCallId(providerKey, modelName);
        boolean accumulateToolCallId = !usesRepeatedToolCallId(providerKey, modelName);
        Double temperature = doReasoning && isDeepSeekLike(providerKey, modelName)
                ? null
                : config.getTemperature();
        return new ModelPlan(modelName, effectiveMaxTokens, doReasoning,
                effectiveReasoningEffort, temperature, parseHeaders(config.getHeadersJson()),
                customParameters, sendThinking, accumulateToolCallId,
                caps.supportsFunctionCalling(), parallelToolCalls);
    }

    // ── SDK builder ─────────────────────────────────────────────────────

    private StreamingChatModel buildResponsesStreaming(String apiKey, String baseUrl, ModelPlan plan) {
        var builder = OpenAiResponsesStreamingChatModel.builder()
                .httpClientBuilder(new JdkHttpClientBuilder()
                        .connectTimeout(Duration.ofSeconds(15))
                        .readTimeout(STREAMING_TIMEOUT))
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .modelName(plan.modelName)
                .parallelToolCalls(plan.parallelToolCalls)
                .store(false)
                .strictTools(false)
                .maxOutputTokens(plan.maxTokens);
        if (plan.reasoningEffort != null) builder.reasoningEffort(plan.reasoningEffort);
        if (plan.doReasoning) builder.reasoningSummary("auto");
        if (plan.temperature != null) builder.temperature(plan.temperature);
        if (!plan.customHeaders.isEmpty()) {
            log.warn("Responses API 模型暂不支持自定义请求头配置，已忽略 {} 个 header", plan.customHeaders.size());
        }
        return builder.build();
    }

    private ChatModel buildResponsesBlocking(String apiKey, String baseUrl, ModelPlan plan) {
        var builder = OpenAiResponsesChatModel.builder()
                .httpClientBuilder(new JdkHttpClientBuilder()
                        .connectTimeout(Duration.ofSeconds(15))
                        .readTimeout(BLOCKING_TIMEOUT))
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .modelName(plan.modelName)
                .parallelToolCalls(plan.parallelToolCalls)
                .store(false)
                .strictTools(false)
                .maxOutputTokens(plan.maxTokens);
        if (plan.reasoningEffort != null) builder.reasoningEffort(plan.reasoningEffort);
        if (plan.doReasoning) builder.reasoningSummary("auto");
        if (plan.temperature != null) builder.temperature(plan.temperature);
        if (!plan.customHeaders.isEmpty()) {
            log.warn("Responses API 模型暂不支持自定义请求头配置，已忽略 {} 个 header", plan.customHeaders.size());
        }
        return builder.build();
    }

    private StreamingChatModel buildChatStreaming(String apiKey, String baseUrl, ModelPlan plan) {
        var builder = OpenAiStreamingChatModel.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .modelName(plan.modelName)
                .maxTokens(plan.maxTokens)
                .timeout(STREAMING_TIMEOUT)
                .parallelToolCalls(plan.parallelToolCalls)
                .returnThinking(plan.doReasoning)
                .sendThinking(plan.sendThinking)
                .accumulateToolCallId(plan.accumulateToolCallId);
        if (plan.reasoningEffort != null) builder.reasoningEffort(plan.reasoningEffort);
        if (plan.temperature != null) builder.temperature(plan.temperature);
        if (!plan.customHeaders.isEmpty()) builder.customHeaders(plan.customHeaders);
        if (!plan.customParameters.isEmpty()) builder.customParameters(plan.customParameters);
        return builder.build();
    }

    private ChatModel buildChatBlocking(String apiKey, String baseUrl, ModelPlan plan) {
        var builder = OpenAiChatModel.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .modelName(plan.modelName)
                .maxTokens(plan.maxTokens)
                .timeout(BLOCKING_TIMEOUT)
                .parallelToolCalls(plan.parallelToolCalls)
                .returnThinking(plan.doReasoning)
                .sendThinking(plan.sendThinking);
        if (plan.reasoningEffort != null) builder.reasoningEffort(plan.reasoningEffort);
        if (plan.temperature != null) builder.temperature(plan.temperature);
        if (!plan.customHeaders.isEmpty()) builder.customHeaders(plan.customHeaders);
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

    private static Map<String, String> parseHeaders(String headersJson) {
        if (headersJson == null || headersJson.isBlank()) return Map.of();
        try {
            Map<?, ?> raw = JSON.parseObject(headersJson, Map.class);
            Map<String, String> headers = new LinkedHashMap<>();
            raw.forEach((key, value) -> {
                if (key != null && value != null) {
                    headers.put(String.valueOf(key), String.valueOf(value));
                }
            });
            return headers;
        } catch (Exception e) {
            log.warn("自定义请求头 JSON 解析失败，将忽略该配置: {}", e.getMessage());
            return Map.of();
        }
    }

    private static String normalizeReasoningEffortForModel(String modelName, String reasoningEffort) {
        String effort = reasoningEffort.trim().toLowerCase();
        String model = modelName == null ? "" : modelName.toLowerCase();
        if (model.contains("deepseek-v4")) {
            return switch (effort) {
                case "low", "medium" -> "high";
                case "xhigh" -> "max";
                default -> effort;
            };
        }
        return effort;
    }

    private static Map<String, Object> chatCustomParameters(AiModelConfig config,
                                                            boolean doReasoning,
                                                            Boolean userIntent) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (usesDeepSeekThinkingBody(config.getProviderKey(), config.getModel())) {
            if (doReasoning) {
                params.put("thinking", Map.of("type", "enabled"));
            } else if (Boolean.FALSE.equals(userIntent)) {
                params.put("thinking", Map.of("type", "disabled"));
            }
        }
        return params;
    }

    private static boolean usesDeepSeekThinkingBody(String providerKey, String modelName) {
        String model = modelName == null ? "" : modelName.toLowerCase();
        return model.contains("deepseek-v4");
    }

    private static boolean isDeepSeekLike(String providerKey, String modelName) {
        String provider = providerKey == null ? "" : providerKey.toLowerCase();
        String model = modelName == null ? "" : modelName.toLowerCase();
        return provider.contains("deepseek") || model.contains("deepseek");
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

    /**
     * 计算当前配置的有效 baseUrl。保留给管理接口调用，按协议自动选择。
     */
    public static String resolveEffectiveBaseUrl(AiModelConfig config) {
        return useResponsesApi(config)
                ? resolveResponsesBaseUrl(config)
                : resolveChatCompletionsBaseUrl(config);
    }

    /**
     * 计算 Responses API 有效 baseUrl。LangChain4j Responses 模型内部会拼接 "/responses"，
     * 因此这里统一返回 API root，不再使用历史 completionsPath。
     */
    public static String resolveResponsesBaseUrl(AiModelConfig config) {
        String baseUrl = config.getBaseUrl();
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = "https://api.openai.com/v1";
        }
        baseUrl = stripTrailingSlash(baseUrl.trim());
        if (baseUrl.equals("https://api.openai.com") || baseUrl.equals("http://api.openai.com")) {
            baseUrl = baseUrl + "/v1";
        }
        if (baseUrl.endsWith("/responses")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - "/responses".length());
        }
        return baseUrl;
    }

    /**
     * 计算 Chat Completions 兼容协议有效 baseUrl：合并 baseUrl 与 completionsPath，
     * 然后去掉 LangChain4j 内部会自动拼接的 "/chat/completions" 后缀。
     */
    public static String resolveChatCompletionsBaseUrl(AiModelConfig config) {
        String baseUrl = config.getBaseUrl();
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = "https://api.openai.com/v1";
        }
        baseUrl = stripTrailingSlash(baseUrl.trim());

        String completionsPath = config.getCompletionsPath();
        if (completionsPath != null && !completionsPath.isBlank()) {
            completionsPath = completionsPath.trim();
            if (!completionsPath.startsWith("/")) completionsPath = "/" + completionsPath;
            completionsPath = stripTrailingSlash(completionsPath);
            String fullUrl = baseUrl + completionsPath;
            if (fullUrl.endsWith("/chat/completions")) {
                fullUrl = fullUrl.substring(0, fullUrl.length() - "/chat/completions".length());
            }
            return fullUrl.isEmpty() ? baseUrl : fullUrl;
        }

        if (baseUrl.equals("https://api.openai.com") || baseUrl.equals("http://api.openai.com")) {
            baseUrl = baseUrl + "/v1";
        }
        return baseUrl;
    }

    private static String stripTrailingSlash(String s) {
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }

    public static boolean useResponsesApi(AiModelConfig config) {
        return PROTOCOL_RESPONSES.equals(resolveProtocol(config));
    }

    public static String resolveProtocol(AiModelConfig config) {
        if (config == null) return PROTOCOL_CHAT_COMPLETIONS;
        String protocol = normalizeProtocol(config.getProtocol());
        if (protocol != null) {
            return protocol;
        }
        String path = config.getCompletionsPath();
        if (path != null && path.toLowerCase().contains("/responses")) {
            return PROTOCOL_RESPONSES;
        }
        if (path != null && path.toLowerCase().contains("/chat/completions")) {
            return PROTOCOL_CHAT_COMPLETIONS;
        }
        return inferDefaultProtocol(config.getProviderKey(), config.getBaseUrl());
    }

    public static String normalizeProtocol(String value) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim().toLowerCase().replace('-', '_');
        if ("responses".equals(normalized) || "response".equals(normalized)) {
            return PROTOCOL_RESPONSES;
        }
        if ("chat_completions".equals(normalized) || "chat_completion".equals(normalized)
                || "openai_compatible".equals(normalized) || "compatible".equals(normalized)) {
            return PROTOCOL_CHAT_COMPLETIONS;
        }
        return null;
    }

    public static String inferDefaultProtocol(String providerKey, String baseUrl) {
        if (providerKey != null && "openai".equalsIgnoreCase(providerKey.trim())) {
            return PROTOCOL_RESPONSES;
        }
        if (baseUrl != null && baseUrl.toLowerCase().contains("api.openai.com")) {
            return PROTOCOL_RESPONSES;
        }
        return PROTOCOL_CHAT_COMPLETIONS;
    }

    public static String defaultPathForProtocol(String protocol) {
        return PROTOCOL_RESPONSES.equals(normalizeProtocol(protocol))
                ? RESPONSES_PATH
                : CHAT_COMPLETIONS_PATH;
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
                             boolean parallelToolCalls) {}

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
                               boolean parallelToolCalls) {}
}
