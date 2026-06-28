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
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 动态模型提供者：从 {@link AiModelConfigService#getActive()} 读取激活配置，
 * 构建并维护 OpenAI Responses API / Chat Completions 兼容的流式 / 非流式模型实例。
 *
 * <p>启动时若数据库无激活记录，回退到 application.properties 中的 fallback 值。
 * 外部调用 {@link #refresh()} 可热切换底层模型。
 */
@Component
public class DynamicModelProvider {

    private static final Logger log = LoggerFactory.getLogger(DynamicModelProvider.class);

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
        String apiKey = config.getApiKey();
        if (apiKey == null || apiKey.isEmpty()) {
            log.warn("模型配置 (id={}) apiKey 为空，跳过模型切换", config.getId());
            return;
        }
        boolean responsesApi = useResponsesApi(config);
        String baseUrl = responsesApi ? resolveResponsesBaseUrl(config) : resolveChatCompletionsBaseUrl(config);
        ModelPlan plan = plan(config);
        streamingModel.setDelegate(responsesApi
                ? buildResponsesStreaming(apiKey, baseUrl, plan)
                : buildChatStreaming(apiKey, baseUrl, plan));
        chatModel.setDelegate(responsesApi
                ? buildResponsesBlocking(apiKey, baseUrl, plan)
                : buildChatBlocking(apiKey, baseUrl, plan));
        log.info("模型已切换 — protocol={}, model={}, maxTokens={}, reasoning={}",
                responsesApi ? "responses" : "chat-completions",
                plan.modelName, plan.maxTokens, plan.doReasoning);
    }

    // ── 计划构造 ──────────────────────────────────────────────────────────

    private ModelPlan plan(AiModelConfig config) {
        String modelName = config.getModel();
        ProviderCapabilities caps = configService.capabilitiesForModel(modelName);

        Boolean userIntent = toBoolean(config.getThinkingEnabled());
        Boolean modelDefault = ModelDefaults.defaultThinkingEnabled(modelName);
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
                ? reasoningEffort.trim().toLowerCase()
                : null;
        return new ModelPlan(modelName, effectiveMaxTokens, doReasoning,
                effectiveReasoningEffort, config.getTemperature(), parseHeaders(config.getHeadersJson()));
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
                .parallelToolCalls(true)
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
                .parallelToolCalls(true)
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
                .parallelToolCalls(true)
                .returnThinking(plan.doReasoning);
        if (plan.reasoningEffort != null) builder.reasoningEffort(plan.reasoningEffort);
        if (plan.temperature != null) builder.temperature(plan.temperature);
        if (!plan.customHeaders.isEmpty()) builder.customHeaders(plan.customHeaders);
        return builder.build();
    }

    private ChatModel buildChatBlocking(String apiKey, String baseUrl, ModelPlan plan) {
        var builder = OpenAiChatModel.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .modelName(plan.modelName)
                .maxTokens(plan.maxTokens)
                .timeout(BLOCKING_TIMEOUT)
                .parallelToolCalls(true)
                .returnThinking(plan.doReasoning);
        if (plan.reasoningEffort != null) builder.reasoningEffort(plan.reasoningEffort);
        if (plan.temperature != null) builder.temperature(plan.temperature);
        if (!plan.customHeaders.isEmpty()) builder.customHeaders(plan.customHeaders);
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
        if (config == null) return false;
        String path = config.getCompletionsPath();
        if (path != null && path.toLowerCase().contains("/responses")) {
            return true;
        }
        String key = config.getProviderKey();
        String baseUrl = config.getBaseUrl();
        if (key != null && "openai".equalsIgnoreCase(key.trim())) {
            return true;
        }
        if (baseUrl == null || baseUrl.isBlank()) {
            return false;
        }
        String normalized = baseUrl.toLowerCase();
        return normalized.contains("api.openai.com");
    }

    private record ModelPlan(String modelName,
                             int maxTokens,
                             boolean doReasoning,
                             String reasoningEffort,
                             Double temperature,
                             Map<String, String> customHeaders) {}
}
