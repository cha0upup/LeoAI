package org.leo.ai.channel;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
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

/**
 * 动态模型提供者：从 {@link AiModelConfigService#getActive()} 读取激活配置，
 * 构建并维护 OpenAI 兼容的流式 / 非流式模型实例。
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
            log.warn("refresh() 调用时无激活模型配置，模型保持不变");
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
        String baseUrl = resolveEffectiveBaseUrl(config);
        ModelPlan plan = plan(config);
        streamingModel.setDelegate(buildStreaming(apiKey, baseUrl, plan));
        chatModel.setDelegate(buildBlocking(apiKey, baseUrl, plan));
        log.info("模型已切换 — model={}, maxTokens={}, reasoning={}",
                plan.modelName, plan.maxTokens, plan.doReasoning);
    }

    // ── 计划构造 ──────────────────────────────────────────────────────────

    private ModelPlan plan(AiModelConfig config) {
        String modelName = config.getModel();
        ProviderCapabilities caps = ProviderCapabilities.forModel(modelName);

        Boolean userIntent = toBoolean(config.getThinkingEnabled());
        Boolean modelDefault = ModelDefaults.defaultThinkingEnabled(modelName);
        boolean wantsReasoning;
        if (userIntent != null) {
            wantsReasoning = userIntent;
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
        int effectiveMaxTokens = maxTokens != null ? maxTokens : caps.defaultMaxOutputTokens();
        return new ModelPlan(modelName, effectiveMaxTokens, doReasoning);
    }

    // ── SDK builder ─────────────────────────────────────────────────────

    private StreamingChatModel buildStreaming(String apiKey, String baseUrl, ModelPlan plan) {
        return OpenAiStreamingChatModel.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .modelName(plan.modelName)
                .maxTokens(plan.maxTokens)
                .timeout(STREAMING_TIMEOUT)
                .parallelToolCalls(true)
                .returnThinking(plan.doReasoning)
                .build();
    }

    private ChatModel buildBlocking(String apiKey, String baseUrl, ModelPlan plan) {
        return OpenAiChatModel.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .modelName(plan.modelName)
                .maxTokens(plan.maxTokens)
                .timeout(BLOCKING_TIMEOUT)
                .parallelToolCalls(true)
                .build();
    }

    // ── 工具方法 ──────────────────────────────────────────────────────────

    private static Boolean toBoolean(Integer flag) {
        if (flag == null) return null;
        return flag > 0;
    }

    private static Integer positive(Integer value) {
        return value != null && value > 0 ? value : null;
    }

    /**
     * 计算有效 baseUrl：合并 baseUrl 与 completionsPath，然后去掉 LangChain4j 内部
     * 会自动拼接的 "/chat/completions" 后缀。
     */
    public static String resolveEffectiveBaseUrl(AiModelConfig config) {
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

    private record ModelPlan(String modelName, int maxTokens, boolean doReasoning) {}
}
