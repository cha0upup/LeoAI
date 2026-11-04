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
        return buildRuntime(config, false, reasoningEffortOverride, false);
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
        ModelRequestPlan plan = plan(config, forceReasoning, reasoningEffortOverride, probe);
        StreamingChatModel streaming = responsesApi
                ? buildResponsesStreaming(apiKey, baseUrl, plan)
                : buildChatStreaming(apiKey, baseUrl, plan);
        ChatModel blocking = responsesApi
                ? buildResponsesBlocking(apiKey, baseUrl, plan)
                : buildChatBlocking(apiKey, baseUrl, plan);
        return new ModelRuntime(streaming, blocking, resolveProtocol(config),
                config.getProviderKey(), baseUrl, plan.modelName(), plan.maxTokens(),
                plan.doReasoning(), plan.reasoningEffort(),
                plan.supportsFunctionCalling(), plan.parallelToolCalls(), plan.contextWindowTokens());
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

    public String plannedRuntimeCacheKey(AiModelConfig config, String reasoningEffortOverride) {
        if (config == null) return "";
        String baseUrl = ModelEndpoint.apiRoot(config);
        ModelRequestPlan plan = plan(config, false, reasoningEffortOverride, false);
        return runtimeCacheKey(config,
                resolveProtocol(config),
                config.getProviderKey(),
                baseUrl,
                plan.modelName(),
                plan.maxTokens(),
                plan.doReasoning(),
                plan.reasoningEffort(),
                plan.supportsFunctionCalling(),
                plan.parallelToolCalls(), plan.contextWindowTokens());
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

    private ModelRequestPlan plan(AiModelConfig config, boolean forceReasoning, String reasoningEffortOverride, boolean probe) {
        ProviderCapabilities caps = configService.capabilitiesForModel(config);
        if (!probe && (!caps.supportsTextGeneration() || !caps.supportsStreaming())) {
            throw new IllegalArgumentException("当前模型未确认支持文本及流式对话: " + config.getModel());
        }
        return ModelRequestPlan.resolve(config, caps, reasoningEffortOverride, forceReasoning, probe);
    }

    // ── SDK builder ─────────────────────────────────────────────────────

    private StreamingChatModel buildResponsesStreaming(String apiKey, String baseUrl, ModelRequestPlan plan) {
        var builder = OpenAiResponsesStreamingChatModel.builder()
                .httpClientBuilder(new ModelHttpClientBuilder(plan.customHeaders(), timeout(plan, true)))
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .modelName(plan.modelName())
                .store(false)
                .strictTools(false)
                .maxOutputTokens(plan.maxTokens());
        if (!plan.probe() && plan.supportsFunctionCalling()) builder.parallelToolCalls(plan.parallelToolCalls());
        if (plan.reasoningEffort() != null) builder.reasoningEffort(plan.reasoningEffort());
        if (plan.doReasoning()) builder.reasoningSummary("auto");
        if (plan.temperature() != null) builder.temperature(plan.temperature());
        return builder.build();
    }

    private ChatModel buildResponsesBlocking(String apiKey, String baseUrl, ModelRequestPlan plan) {
        var builder = OpenAiResponsesChatModel.builder()
                .httpClientBuilder(new ModelHttpClientBuilder(plan.customHeaders(), timeout(plan, false)))
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .modelName(plan.modelName())
                .store(false)
                .strictTools(false)
                .maxOutputTokens(plan.maxTokens());
        if (!plan.probe() && plan.supportsFunctionCalling()) builder.parallelToolCalls(plan.parallelToolCalls());
        if (plan.reasoningEffort() != null) builder.reasoningEffort(plan.reasoningEffort());
        if (plan.doReasoning()) builder.reasoningSummary("auto");
        if (plan.temperature() != null) builder.temperature(plan.temperature());
        return builder.build();
    }

    private StreamingChatModel buildChatStreaming(String apiKey, String baseUrl, ModelRequestPlan plan) {
        var builder = OpenAiStreamingChatModel.builder()
                .httpClientBuilder(new ModelHttpClientBuilder(plan.customHeaders(), timeout(plan, true)))
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .modelName(plan.modelName())
                .maxTokens(plan.useMaxCompletionTokens() ? null : plan.maxTokens())
                .maxCompletionTokens(plan.useMaxCompletionTokens() ? plan.maxTokens() : null)
                .timeout(timeout(plan, true))
                .returnThinking(plan.returnThinking())
                .sendThinking(plan.sendThinking())
                .accumulateToolCallId(plan.accumulateToolCallId());
        if (!plan.probe() && plan.supportsFunctionCalling()) builder.parallelToolCalls(plan.parallelToolCalls());
        if (plan.reasoningEffort() != null) builder.reasoningEffort(plan.reasoningEffort());
        if (plan.temperature() != null) builder.temperature(plan.temperature());
        if (!plan.customParameters().isEmpty()) builder.customParameters(plan.customParameters());
        return builder.build();
    }

    private ChatModel buildChatBlocking(String apiKey, String baseUrl, ModelRequestPlan plan) {
        var builder = OpenAiChatModel.builder()
                .httpClientBuilder(new ModelHttpClientBuilder(plan.customHeaders(), timeout(plan, false)))
                .maxRetries(plan.probe() ? 0 : 2)
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .modelName(plan.modelName())
                .maxTokens(plan.useMaxCompletionTokens() ? null : plan.maxTokens())
                .maxCompletionTokens(plan.useMaxCompletionTokens() ? plan.maxTokens() : null)
                .timeout(timeout(plan, false))
                .returnThinking(plan.returnThinking())
                .sendThinking(plan.sendThinking());
        if (!plan.probe() && plan.supportsFunctionCalling()) builder.parallelToolCalls(plan.parallelToolCalls());
        if (plan.reasoningEffort() != null) builder.reasoningEffort(plan.reasoningEffort());
        if (plan.temperature() != null) builder.temperature(plan.temperature());
        if (!plan.customParameters().isEmpty()) builder.customParameters(plan.customParameters());
        return builder.build();
    }

    // ── 工具方法 ──────────────────────────────────────────────────────────

    private static Duration timeout(ModelRequestPlan plan, boolean streaming) {
        return plan.probe() ? Duration.ofSeconds(30) : streaming ? STREAMING_TIMEOUT : BLOCKING_TIMEOUT;
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
