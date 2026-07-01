package org.leo.ai.agent;

import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.memory.chat.TokenWindowChatMemory;
import dev.langchain4j.http.client.jdk.JdkHttpClientBuilder;
import dev.langchain4j.model.TokenCountEstimator;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiResponsesChatModel;
import dev.langchain4j.model.openai.OpenAiResponsesStreamingChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import org.leo.ai.channel.AiModelConfigService;
import org.leo.ai.channel.DelegatingChatModel;
import org.leo.ai.channel.DelegatingStreamingChatModel;
import org.leo.ai.channel.DynamicModelProvider;
import org.leo.ai.config.AiAgentProperties;
import org.leo.ai.service.SkillRegistryService;
import org.leo.core.entity.AiModelConfig;
import org.leo.ai.tools.platform.SkillActivationTools;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.scheduling.annotation.EnableAsync;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * LangChain4j Agent 配置。
 *
 * <p>使用 {@link DelegatingStreamingChatModel} / {@link DelegatingChatModel} 代理层，
 * 支持运行时通过 {@link org.leo.ai.channel.DynamicModelProvider#refresh()} 热切换底层模型。
 *
 * <p>启动时使用 application.yml 中的 fallback 配置构建初始模型实例；
 * 随后 DynamicModelProvider 的 @PostConstruct 会尝试从数据库加载激活 Provider 覆盖。
 *
 * <p>线程池架构：
 * <ul>
 *   <li>{@code rawAiToolExecutor} — 底层固定 12 线程池，承载所有工具执行</li>
 *   <li>{@code aiToolExecutor}    — 主 Agent 工具执行器（限流包装，maxParallelTools=5）</li>
 * </ul>
 *
 * <p>所有工具直接附着到主 Agent，无子 Agent 调度层。
 * 纯 OS 命令包装工具已移除，统一通过 exec 工具替代。
 */
@Configuration
@EnableAsync
public class AgentConfig {

    private static final int TOOL_EXECUTOR_THREADS = 12;

    // ── 注入依赖 ──────────────────────────────────────────────────────────────

    private final AiAgentProperties agentProps;
    private final AiModelConfigService modelConfigService;

    public AgentConfig(AiAgentProperties agentProps, AiModelConfigService modelConfigService) {
        this.agentProps = agentProps;
        this.modelConfigService = modelConfigService;
    }

    @Value("${leo.ai.openai.api-key:}")
    private String apiKey;

    @Value("${leo.ai.openai.model:gpt-4o}")
    private String modelName;

    @Value("${leo.ai.openai.base-url:https://api.openai.com/v1}")
    private String baseUrl;

    @Value("${leo.ai.openai.protocol:}")
    private String protocol;

    @Value("${leo.ai.openai.completions-path:}")
    private String completionsPath;

    @Value("${leo.ai.openai.thinking-enabled:false}")
    private boolean thinkingEnabled;

    @Value("${leo.ai.openai.max-tokens:}")
    private String maxTokens;

    // ── 线程池 ────────────────────────────────────────────────────────────────

    /**
     * 底层工具执行线程池：固定 12 线程，供 destroy 生命周期管理。
     */
    @Bean(destroyMethod = "shutdown")
    public ExecutorService rawAiToolExecutor() {
        AtomicInteger counter = new AtomicInteger(1);
        return Executors.newFixedThreadPool(TOOL_EXECUTOR_THREADS, runnable -> {
            Thread thread = new Thread(runnable, "ai-tool-" + counter.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * 主 Agent 工具执行器：限流包装，最大并发数由 maxParallelTools 配置控制。
     */
    @Bean
    @Primary
    public ExecutorService aiToolExecutor(@Qualifier("rawAiToolExecutor") ExecutorService raw) {
        int maxParallel = agentProps.getPuppetNode().getMain().getMaxParallelTools();
        return new ThrottledExecutorService(raw, maxParallel);
    }

    // ── Token 估算与对话记忆 ─────────────────────────────────────────────────

    /**
     * 轻量级字符 token 估算器，无网络调用。
     * 用于 {@link TokenWindowChatMemory} 的滑动窗口淘汰判定和压缩触发判断。
     */
    @Bean
    public TokenCountEstimator charBasedTokenEstimator() {
        return new CharBasedTokenEstimator();
    }

    /**
     * 上下文压缩服务：在对话历史接近窗口上限时自动将旧消息压缩为摘要。
     */
    @Bean
    public ContextCompressionService contextCompressionService(
            DelegatingChatModel chatModel, TokenCountEstimator tokenEstimator) {
        return new ContextCompressionService(chatModel, tokenEstimator);
    }

    /**
     * 主 Agent 对话记忆：自动适配模型上下文窗口大小。
     *
     * <p>优先级：
     * <ol>
     *   <li>数据库激活模型配置的 {@code contextWindowTokens} 字段</li>
     *   <li>常见模型名的默认窗口推断（gpt-4o→200K, gemini→1M 等）</li>
     *   <li>配置文件 {@code leo.ai.agent.puppet-node.main.max-context-tokens}（默认 180K）</li>
     * </ol>
     *
     * <p>小窗口（&lt;=96K）使用 {@link TokenWindowChatMemory}（基于 token 精确淘汰）；
     * 大窗口（&gt;96K）使用 {@link MessageWindowChatMemory}（按消息条数淘汰），
     * 避免 {@link CharBasedTokenEstimator} 在百万 token 量级下的累积误差。
     *
     * <p>压缩由 {@link ContextCompressionService#compressIfNeeded} 在工具执行前触发，
     * 仅当窗口 &gt;=100K 且当前 token 数 &gt; 80% 阈值时执行。
     */
    @Bean
    @Primary
    public ChatMemoryProvider chatMemoryProvider(TokenCountEstimator tokenEstimator,
                                                  ContextCompressionService compressionService) {
        return memoryId -> {
            int modelWindow = modelConfigService.getActiveContextWindowTokens();
            // 预留 system prompt + tools 空间，默认扣除 20K
            int effectiveWindow = modelWindow > 0 ? modelWindow - 20_000 : 180_000;
            int configuredMin = agentProps.getPuppetNode().getMain().getMaxContextTokens();
            effectiveWindow = Math.max(effectiveWindow, configuredMin);

            // 小窗口用 TokenWindow（精确），大窗口用 MessageWindow（避免 token 估算误差）
            if (effectiveWindow <= 96_000) {
                return TokenWindowChatMemory.builder()
                        .id(memoryId)
                        .maxTokens(effectiveWindow, tokenEstimator)
                        .build();
            }
            // 大窗口：按消息条数 + 压缩兜底
            int maxMessages = estimateMaxMessages(effectiveWindow);
            return new CompressingChatMemory(
                    memoryId,
                    MessageWindowChatMemory.builder().id(memoryId).maxMessages(maxMessages).build(),
                    tokenEstimator,
                    compressionService,
                    effectiveWindow
            );
        };
    }

    /**
     * 根据上下文窗口估算可保留的最大消息条数。
     * 假设每条消息平均 2K token（含工具调用和结果）。
     */
    private static int estimateMaxMessages(int contextWindowTokens) {
        return Math.max(50, contextWindowTokens / 2000);
    }

    // ── 模型 Bean ────────────────────────────────────────────────────────────

    /**
     * 代理流式模型 Bean。Agent 绑定此代理，底层实例可热切换。
     * 启动时使用 fallback 配置构建初始实例；DynamicModelProvider @PostConstruct 会从 DB 覆盖。
     * OpenAI Responses API：reasoning summary 通过 reasoningSummary("auto") 启用。
     */
    @Bean
    public DelegatingStreamingChatModel delegatingStreamingChatModel() {
        AiModelConfig fallback = fallbackConfig();
        if (!DynamicModelProvider.useResponsesApi(fallback)) {
            var builder = OpenAiStreamingChatModel.builder()
                    .apiKey(apiKey)
                    .baseUrl(DynamicModelProvider.resolveChatCompletionsBaseUrl(fallback))
                    .modelName(modelName)
                    .parallelToolCalls(true)
                    .timeout(Duration.ofMinutes(5));
            Integer configuredMaxTokens = parsePositiveInt(maxTokens);
            if (configuredMaxTokens != null) {
                builder.maxTokens(configuredMaxTokens);
            }
            return new DelegatingStreamingChatModel(builder.build());
        }
        var builder = OpenAiResponsesStreamingChatModel.builder()
                .httpClientBuilder(new JdkHttpClientBuilder()
                        .connectTimeout(Duration.ofSeconds(15))
                        .readTimeout(Duration.ofMinutes(5)))
                .apiKey(apiKey)
                .baseUrl(DynamicModelProvider.resolveResponsesBaseUrl(fallback))
                .modelName(modelName)
                .parallelToolCalls(true)
                .store(false)
                .strictTools(false);
        if (thinkingEnabled) {
            builder.reasoningSummary("auto");
        }
        Integer configuredMaxTokens = parsePositiveInt(maxTokens);
        if (configuredMaxTokens != null) {
            builder.maxOutputTokens(configuredMaxTokens);
        }
        return new DelegatingStreamingChatModel(builder.build());
    }

    /**
     * 代理非流式模型 Bean。辅助服务（摘要、情报提取）注入此 Bean。
     */
    @Bean
    public DelegatingChatModel delegatingChatModel() {
        AiModelConfig fallback = fallbackConfig();
        if (!DynamicModelProvider.useResponsesApi(fallback)) {
            var builder = OpenAiChatModel.builder()
                    .apiKey(apiKey)
                    .baseUrl(DynamicModelProvider.resolveChatCompletionsBaseUrl(fallback))
                    .modelName(modelName)
                    .parallelToolCalls(true)
                    .timeout(Duration.ofMinutes(2));
            Integer configuredMaxTokens = parsePositiveInt(maxTokens);
            if (configuredMaxTokens != null) {
                builder.maxTokens(configuredMaxTokens);
            }
            return new DelegatingChatModel(builder.build());
        }
        var builder = OpenAiResponsesChatModel.builder()
                .httpClientBuilder(new JdkHttpClientBuilder()
                        .connectTimeout(Duration.ofSeconds(15))
                        .readTimeout(Duration.ofMinutes(2)))
                .apiKey(apiKey)
                .baseUrl(DynamicModelProvider.resolveResponsesBaseUrl(fallback))
                .modelName(modelName)
                .parallelToolCalls(true)
                .store(false)
                .strictTools(false);
        Integer configuredMaxTokens = parsePositiveInt(maxTokens);
        if (configuredMaxTokens != null) {
            builder.maxOutputTokens(configuredMaxTokens);
        }
        return new DelegatingChatModel(builder.build());
    }

    private AiModelConfig fallbackConfig() {
        AiModelConfig config = new AiModelConfig();
        config.setApiKey(apiKey);
        config.setBaseUrl(baseUrl);
        config.setModel(modelName);
        config.setProtocol(DynamicModelProvider.normalizeProtocol(protocol));
        config.setCompletionsPath(completionsPath);
        if (config.getProtocol() == null) {
            config.setProtocol(DynamicModelProvider.resolveProtocol(config));
        }
        return config;
    }

    private static Integer parsePositiveInt(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            int parsed = Integer.parseInt(value.trim());
            return parsed > 0 ? parsed : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ── Skill 工具 Bean ───────────────────────────────────────────────────────

    @Bean
    public SkillActivationTools puppetNodeSkillActivationTools(SkillRegistryService skillRegistry) {
        return new SkillActivationTools(skillRegistry, SkillRegistryService.SCOPE_PUPPET_NODE);
    }

    @Bean
    public SkillActivationTools platformSkillActivationTools(SkillRegistryService skillRegistry) {
        return new SkillActivationTools(skillRegistry, SkillRegistryService.SCOPE_PLATFORM);
    }

    // ── 主 Agent（默认 Bean，按激活通道热切换；会话级 Agent 由 AiAgentFactory 构建）────

    @Bean
    public PuppetNodeAgent puppetNodeAgent(DelegatingStreamingChatModel streamingModel,
                                           DelegatingChatModel chatModel,
                                           AiAgentFactory agentFactory) {
        return agentFactory.createPuppetNodeAgent(streamingModel, chatModel);
    }

    // ── Platform Agent ───────────────────────────────────────────────────────

    @Bean
    public PlatformAgent platformAgent(DelegatingStreamingChatModel model,
                                       AiAgentFactory agentFactory) {
        return agentFactory.createPlatformAgent(model);
    }
}
