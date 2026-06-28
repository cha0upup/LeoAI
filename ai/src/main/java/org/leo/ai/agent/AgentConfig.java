package org.leo.ai.agent;

import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.memory.chat.TokenWindowChatMemory;
import dev.langchain4j.http.client.jdk.JdkHttpClientBuilder;
import dev.langchain4j.model.TokenCountEstimator;
import dev.langchain4j.model.openai.OpenAiResponsesChatModel;
import dev.langchain4j.model.openai.OpenAiResponsesStreamingChatModel;
import dev.langchain4j.service.AiServices;
import org.leo.ai.channel.AiModelConfigService;
import org.leo.ai.channel.DelegatingChatModel;
import org.leo.ai.channel.DelegatingStreamingChatModel;
import org.leo.ai.config.AiAgentProperties;
import org.leo.ai.service.AutoReconAppendService;
import org.leo.ai.service.SkillRegistryService;
import org.leo.ai.tools.platform.*;
import org.leo.ai.tools.puppetnode.*;
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
        var builder = OpenAiResponsesStreamingChatModel.builder()
                .httpClientBuilder(new JdkHttpClientBuilder()
                        .connectTimeout(Duration.ofSeconds(15))
                        .readTimeout(Duration.ofMinutes(5)))
                .apiKey(apiKey)
                .baseUrl(baseUrl)
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
        var builder = OpenAiResponsesChatModel.builder()
                .httpClientBuilder(new JdkHttpClientBuilder()
                        .connectTimeout(Duration.ofSeconds(15))
                        .readTimeout(Duration.ofMinutes(2)))
                .apiKey(apiKey)
                .baseUrl(baseUrl)
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

    // ── 主 Agent（单一 Agent，所有工具直接附着）────────────────────────────────

    /**
     * 主 Agent：所有 puppet 侧工具直接注入，无子 Agent 调度层。
     *
     * <p>工具分层：
     * <ul>
     *   <li>核心操作 — exec、文件信息、加解密、反向隧道</li>
     *   <li>侦察 — 端口扫描、浏览器数据、凭据采集、剪贴板</li>
     *   <li>持久化 — Java 插件调用、Catalina 容器管理</li>
     *   <li>攻击 — HTTP/Fuzz、脚本执行、SQL、资源读取</li>
     *   <li>元管理 — 计划追踪、侦察情报汇总、技能激活</li>
     * </ul>
     *
     * <p>纯 OS 命令包装工具（进程/用户/磁盘/网络/文件操作等）已移除，统一通过 exec 工具替代。
     */
    @Bean
    public PuppetNodeAgent puppetNodeAgent(
            DelegatingStreamingChatModel streamingModel,
            DelegatingChatModel chatModel,
            ChatMemoryProvider memoryProvider,
            PuppetNodeSystemPromptProvider systemPromptProvider,
            // 核心操作
            CommandTools commandTools,
            BasicInfoTools basicInfoTools,
            ReverseTunnelTools reverseTunnelTools,
            UtilTools utilTools,
            FileTools fileTools,
            // 侦察
            ScanTools scanTools,
            BrowserDataTools browserDataTools,
            CredentialHarvestTools credentialHarvestTools,
            ClipboardTools clipboardTools,
            // 持久化
            CatalinaTools catalinaTools,
            JavaPluginTools javaPluginTools,
            // 攻击
            HttpRequestTools httpRequestTools,
            ScriptTools scriptTools,
            SqlTools sqlTools,
            ResourceTools resourceTools,
            // 元管理
            SessionTools sessionTools,
            PlanTools planTools,
            @Qualifier("puppetNodeSkillActivationTools") SkillActivationTools skillActivationTools,
            AutoReconAppendService autoReconAppendService,
            ExecutorService aiToolExecutor) {

        return AiServices.builder(PuppetNodeAgent.class)
                .streamingChatModel(streamingModel)
                .chatModel(chatModel)
                .chatMemoryProvider(memoryProvider)
                .systemMessageProvider(systemPromptProvider::getSystemMessage)
                .executeToolsConcurrently(aiToolExecutor)
                .beforeToolExecution(execution -> {
                    if (execution != null && execution.invocationContext() != null) {
                        AiToolContext.setFromMemoryId(execution.invocationContext().chatMemoryId());
                    }
                    // 关联 plan step：如果当前计划有正在执行的步骤，将 stepIndex 注入上下文
                    autoAssociatePlanStep();
                })
                .afterToolExecution(execution -> {
                    try {
                        triggerAutoReconAppend(execution, autoReconAppendService);
                        // 自动将工具结果写入 plan step
                        autoAppendToolResultToPlanStep(execution);
                    } finally {
                        AiToolContext.clear();
                    }
                })
                .tools(commandTools, basicInfoTools,
                        reverseTunnelTools,
                        utilTools, fileTools,
                        scanTools, browserDataTools, credentialHarvestTools, clipboardTools,
                        catalinaTools, javaPluginTools,
                        httpRequestTools, scriptTools, sqlTools, resourceTools,
                        sessionTools, planTools, skillActivationTools)
                .build();
    }

    /**
     * 在主 Agent 的 afterToolExecution 钩子中触发自动侦察情报提取。
     *
     * <p>跳过下列工具，避免无效分析或反馈循环：
     * <ul>
     *   <li>SessionTools 的 reconSummary 读写工具：分析自己的输出毫无意义</li>
     *   <li>PlanTools：计划/进度本身不是侦察情报</li>
     * </ul>
     *
     * <p>{@link AutoReconAppendService#analyzeAndAppend} 自身带 {@code @Async} 注解，
     * 配合 {@code @EnableAsync}（见本类顶部）实际异步执行；调用立即返回，不阻塞工具回写。
     * 即便如此仍捕获所有异常，确保旁路分析的任何故障都不会影响主对话流程。
     */
    private static final java.util.Set<String> AUTO_RECON_APPEND_SKIPPED_TOOLS = java.util.Set.of(
            "manage_recon_summary",
            "createPlan", "updatePlan", "getPlan", "deletePlan",
            "activate_skill"
    );

    private static void triggerAutoReconAppend(dev.langchain4j.service.tool.ToolExecution execution,
                                                AutoReconAppendService autoReconAppendService) {
        if (execution == null || execution.hasFailed()) return;
        String toolName = execution.request() != null ? execution.request().name() : null;
        if (toolName == null || AUTO_RECON_APPEND_SKIPPED_TOOLS.contains(toolName)) return;

        String sessionId = AiToolContext.getSessionId();
        if (sessionId == null || sessionId.isBlank()) return;

        String result = execution.result();
        if (result == null || result.isBlank()) return;

        try {
            autoReconAppendService.analyzeAndAppend(sessionId, toolName, result);
        } catch (Throwable t) {
            org.slf4j.LoggerFactory.getLogger(AgentConfig.class)
                    .debug("AutoReconAppend 触发失败 tool={} sessionId={}: {}",
                            toolName, sessionId, t.getMessage());
        }
    }

    // ── Plan Step 自动关联 ──────────────────────────────────────────────────

    /** Plan 工具本身不应触发 step 关联（避免 createPlan/updatePlanStep 自关联）。 */
    private static final java.util.Set<String> PLAN_TOOLS = java.util.Set.of(
            "createPlan", "updatePlanStep", "completePlan");

    /** 在工具执行前检测活跃 plan 的 running step，注入到 AiToolContext。 */
    private static void autoAssociatePlanStep() {
        try {
            String sessionId = AiToolContext.getSessionId();
            String threadId = AiToolContext.getThreadId();
            if (sessionId == null || sessionId.isBlank()) return;

            var session = org.leo.core.session.PuppetNodeSessionContainer.getSession(sessionId);
            if (session == null) return;

            var thread = (threadId != null) ? session.getAiThread(threadId) : session.getActiveThread();
            if (thread == null) return;

            var plan = thread.getCurrentPlan();
            if (plan == null) return;

            var steps = plan.getSteps();
            if (steps == null) return;

            for (int i = 0; i < steps.size(); i++) {
                var step = steps.get(i);
                if (step.getStatus().name().equals("RUNNING")) {
                    AiToolContext.setPlanStepIndex(step.getIndex());
                    AiToolContext.setPlanStepPreApproved(step.isPreApproved());
                    return;
                }
            }
        } catch (Exception ignored) {
            // best-effort，失败不影响工具执行
        }
    }

    /** 在工具执行后将结果自动写入 plan step 的 result 字段。 */
    private static void autoAppendToolResultToPlanStep(
            dev.langchain4j.service.tool.ToolExecution execution) {
        int stepIndex = AiToolContext.getPlanStepIndex();
        if (stepIndex < 0) return;
        if (execution == null) return;

        String toolName = execution.request() != null ? execution.request().name() : null;
        if (toolName == null || PLAN_TOOLS.contains(toolName)) return;

        try {
            String sessionId = AiToolContext.getSessionId();
            String threadId = AiToolContext.getThreadId();
            if (sessionId == null || sessionId.isBlank()) return;

            var session = org.leo.core.session.PuppetNodeSessionContainer.getSession(sessionId);
            if (session == null) return;

            var thread = (threadId != null) ? session.getAiThread(threadId) : session.getActiveThread();
            if (thread == null) return;

            var plan = thread.getCurrentPlan();
            if (plan == null) return;

            var steps = plan.getSteps();
            if (steps == null) return;

            for (var step : steps) {
                if (step.getIndex() == stepIndex) {
                    String summary = buildToolResultSummary(toolName, execution);
                    if (step.getResult() != null && !step.getResult().isBlank()) {
                        step.setResult(step.getResult() + " | " + summary);
                    } else {
                        step.setResult(summary);
                    }
                    // 通知前端 step 更新
                    thread.offerSseEvent("patch", buildPlanStepPatch(plan, step, toolName));
                    break;
                }
            }
        } catch (Exception ignored) {
            // best-effort
        }
    }

    /** 构建工具结果摘要（最多 120 字符）。 */
    private static String buildToolResultSummary(String toolName,
                                                  dev.langchain4j.service.tool.ToolExecution execution) {
        StringBuilder sb = new StringBuilder(toolName);
        if (execution.hasFailed()) {
            sb.append(" 失败");
            String err = execution.result();
            if (err != null && !err.isBlank()) {
                String shortErr = err.length() > 80 ? err.substring(0, 80) + "…" : err;
                sb.append("（").append(shortErr).append("）");
            }
        } else {
            sb.append(" 完成");
            String result = execution.result();
            if (result != null && !result.isBlank()) {
                // 取第一行或前 80 字符
                String firstLine = result.lines().findFirst().orElse("");
                String shortResult = firstLine.length() > 80 ? firstLine.substring(0, 80) + "…" : firstLine;
                sb.append(" → ").append(shortResult);
            }
        }
        return sb.toString();
    }

    /** 构建 plan step patch 事件 payload。 */
    private static java.util.Map<String, Object> buildPlanStepPatch(
            org.leo.core.entity.AiPlan plan, org.leo.core.entity.AiPlanStep step, String toolName) {
        java.util.LinkedHashMap<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("kind", "plan");
        payload.put("planId", plan.getPlanId());
        payload.put("stepIndex", step.getIndex());
        payload.put("status", step.getStatus().name());
        payload.put("result", step.getResult());
        payload.put("toolName", toolName);
        payload.put("timestamp", System.currentTimeMillis());
        return payload;
    }

    // ── Platform Agent ───────────────────────────────────────────────────────

    @Bean
    public PlatformAgent platformAgent(
            DelegatingStreamingChatModel model,
            ChatMemoryProvider memoryProvider,
            PlatformSystemPromptProvider systemPromptProvider,
            PuppetTools puppetTools,
            UserTools userTools,
            TeamTools teamTools,
            PluginTools pluginTools,
            FingerprintTools fingerprintTools,
            DisguiseTools disguiseTools,
            ShellGeneratorTools shellGeneratorTools,
            @Qualifier("platformSkillActivationTools") SkillActivationTools skillActivationTools,
            ExecutorService aiToolExecutor) {

        return AiServices.builder(PlatformAgent.class)
                .streamingChatModel(model)
                .chatMemoryProvider(memoryProvider)
                .systemMessageProvider(systemPromptProvider::getSystemMessage)
                .executeToolsConcurrently(aiToolExecutor)
                .tools(puppetTools, userTools, teamTools,
                        pluginTools, fingerprintTools, disguiseTools,
                        shellGeneratorTools, skillActivationTools)
                .build();
    }
}
