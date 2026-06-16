package org.leo.ai.agent;

import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.memory.chat.TokenWindowChatMemory;
import dev.langchain4j.model.TokenCountEstimator;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.service.AiServices;
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
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
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
 *   <li>{@code rawAiToolExecutor}      — 底层固定 12 线程池，承载所有工具执行</li>
 *   <li>{@code aiToolExecutor}         — 主 Agent 工具执行器（限流包装，maxParallelTools=5）</li>
 *   <li>{@code subAgentToolExecutor}   — 子 Agent 工具执行器（限流包装，subMaxParallelTools=3）</li>
 *   <li>{@code subAgentDispatchExecutor} — 子 Agent 调度执行器（独立 cached 池，避免与工具池争抢）</li>
 * </ul>
 */
@Configuration
@EnableAsync
public class AgentConfig {

    private static final int TOOL_EXECUTOR_THREADS = 12;

    // ── 注入依赖 ──────────────────────────────────────────────────────────────

    private final AiAgentProperties agentProps;

    public AgentConfig(AiAgentProperties agentProps) {
        this.agentProps = agentProps;
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

    /**
     * 子 Agent 工具执行器：并发数更低，避免目标侧资源竞争。
     */
    @Bean
    public ExecutorService subAgentToolExecutor(@Qualifier("rawAiToolExecutor") ExecutorService raw) {
        int maxParallel = agentProps.getPuppetNode().getSubMaxParallelTools();
        return new ThrottledExecutorService(raw, maxParallel);
    }

    /**
     * 子 Agent 派发执行器：每个子 Agent 调度占用一个线程（reasoning + 等待内部工具结果）。
     * 必须独立于 {@code rawAiToolExecutor}，否则当多个主 Agent 工具同时派发子 Agent
     * 并在 {@code Future.get()} 阻塞时，子 Agent 自身又需要 {@code rawAiToolExecutor}
     * 的线程跑工具，会出现死锁。
     *
     * <p>线程池策略：核心 0，上限由 {@code subagent.dispatchMaxThreads} 控制（默认 64），
     * 空闲 60s 回收，队列满直接由调用线程兜底执行（避免任务被静默丢弃）。
     * 不用 unbounded cached pool —— 突发流量下 cached pool 会无限创建线程拖垮 JVM。
     */
    @Bean(destroyMethod = "shutdown")
    public ExecutorService subAgentDispatchExecutor() {
        int max = Math.max(1, agentProps.getSubagent().getDispatchMaxThreads());
        AtomicInteger counter = new AtomicInteger(1);
        ThreadFactory factory = r -> {
            Thread t = new Thread(r, "subagent-dispatch-" + counter.getAndIncrement());
            t.setDaemon(true);
            return t;
        };
        return new ThreadPoolExecutor(
                0, max,
                60L, TimeUnit.SECONDS,
                new SynchronousQueue<>(),
                factory,
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
    }

    // ── Token 估算与对话记忆 ─────────────────────────────────────────────────

    /**
     * 轻量级字符 token 估算器，无网络调用。
     * 用于 {@link TokenWindowChatMemory} 的滑动窗口淘汰判定。
     */
    @Bean
    public TokenCountEstimator charBasedTokenEstimator() {
        return new CharBasedTokenEstimator();
    }

    /**
     * 主 Agent 对话记忆：token-aware 滑动窗口，窗口大小由 maxContextTokens 配置控制。
     */
    @Bean
    @Primary
    public ChatMemoryProvider chatMemoryProvider(TokenCountEstimator tokenEstimator) {
        int maxContextTokens = agentProps.getPuppetNode().getMain().getMaxContextTokens();
        return memoryId -> TokenWindowChatMemory.builder()
                .id(memoryId)
                .maxTokens(maxContextTokens, tokenEstimator)
                .build();
    }

    /**
     * 子 Agent 对话记忆：窗口更小（默认 16k token），适合短期任务执行。
     */
    @Bean
    public ChatMemoryProvider subAgentMemoryProvider(TokenCountEstimator tokenEstimator) {
        int maxContextTokens = agentProps.getPuppetNode().getSubMaxContextTokens();
        return memoryId -> TokenWindowChatMemory.builder()
                .id(memoryId)
                .maxTokens(maxContextTokens, tokenEstimator)
                .build();
    }

    // ── 模型 Bean ────────────────────────────────────────────────────────────

    /**
     * 代理流式模型 Bean。Agent 绑定此代理，底层实例可热切换。
     * 启动时使用 fallback 配置构建初始实例；DynamicModelProvider @PostConstruct 会从 DB 覆盖。
     * OpenAI 兼容协议：reasoning_content 通过 returnThinking(true) 启用，模型不支持时自动降级。
     */
    @Bean
    public DelegatingStreamingChatModel delegatingStreamingChatModel() {
        var builder = OpenAiStreamingChatModel.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .modelName(modelName)
                .parallelToolCalls(true)
                .timeout(Duration.ofMinutes(5))
                .returnThinking(thinkingEnabled);
        Integer configuredMaxTokens = parsePositiveInt(maxTokens);
        if (configuredMaxTokens != null) {
            builder.maxTokens(configuredMaxTokens);
        }
        return new DelegatingStreamingChatModel(builder.build());
    }

    /**
     * 代理非流式模型 Bean。辅助服务（摘要、情报提取）注入此 Bean，永不开 reasoning。
     */
    @Bean
    public DelegatingChatModel delegatingChatModel() {
        var builder = OpenAiChatModel.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .modelName(modelName)
                .parallelToolCalls(true)
                .timeout(Duration.ofMinutes(2));
        Integer configuredMaxTokens = parsePositiveInt(maxTokens);
        if (configuredMaxTokens != null) {
            builder.maxTokens(configuredMaxTokens);
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

    // ── 主 Agent ──────────────────────────────────────────────────────────────

    /**
     * 主 Agent：只保留核心工具（~32 方法）+ SubAgentDispatchTools（1 方法）。
     * 专项任务通过 dispatchSubtask(task, category) 统一分发给子 Agent。
     */
    @Bean
    public PuppetNodeAgent puppetNodeAgent(
            DelegatingStreamingChatModel streamingModel,
            DelegatingChatModel chatModel,
            ChatMemoryProvider memoryProvider,
            PuppetNodeSystemPromptProvider systemPromptProvider,
            CommandTools commandTools,
            BasicInfoTools basicInfoTools,
            ProcessTools processTools,
            NetworkInfoTools networkInfoTools,
            ReverseTunnelTools reverseTunnelTools,
            UtilTools utilTools,
            FileTools fileTools,
            SessionTools sessionTools,
            PlanTools planTools,
            @Qualifier("puppetNodeSkillActivationTools") SkillActivationTools skillActivationTools,
            SubAgentDispatchTools subAgentDispatchTools,
            AutoReconAppendService autoReconAppendService,
            ExecutorService aiToolExecutor) {

        return AiServices.builder(PuppetNodeAgent.class)
                .streamingChatModel(streamingModel)
                .chatModel(chatModel)
                .chatMemoryProvider(memoryProvider)
                .systemMessageProvider(systemPromptProvider::getSystemMessage)
                .executeToolsConcurrently(aiToolExecutor)
                // 工具执行前后设置 / 清除 ThreadLocal 上下文，工具方法通过 AiToolContext 读取
                .beforeToolExecution(execution -> {
                    if (execution != null && execution.invocationContext() != null) {
                        AiToolContext.setFromMemoryId(execution.invocationContext().chatMemoryId());
                    }
                })
                .afterToolExecution(execution -> {
                    try {
                        // 工具执行结束后异步分析输出，把侦察情报追加到 reconSummary。
                        // 注意：在 clear() 之前读 sessionId —— ThreadLocal 一会儿就清掉了。
                        triggerAutoReconAppend(execution, autoReconAppendService);
                    } finally {
                        AiToolContext.clear();
                    }
                })
                .tools(commandTools, basicInfoTools, processTools,
                        networkInfoTools, reverseTunnelTools,
                        utilTools, fileTools, sessionTools,
                        planTools, skillActivationTools,
                        subAgentDispatchTools)
                .build();
    }

    /**
     * 在主 Agent 的 afterToolExecution 钩子中触发自动侦察情报提取。
     *
     * <p>跳过下列工具，避免无效分析或反馈循环：
     * <ul>
     *   <li>SessionTools 的 reconSummary 读写工具：分析自己的输出毫无意义</li>
     *   <li>PlanTools：计划/进度本身不是侦察情报</li>
     *   <li>dispatchSubtask：SubAgent 的内部执行已经在子 Agent 内消耗 token，
     *       它返回给主 Agent 的只是"摘要文本"，再走一次提取会反复压缩、价值不高</li>
     * </ul>
     *
     * <p>{@link AutoReconAppendService#analyzeAndAppend} 自身带 {@code @Async} 注解，
     * 配合 {@code @EnableAsync}（见本类顶部）实际异步执行；调用立即返回，不阻塞工具回写。
     * 即便如此仍捕获所有异常，确保旁路分析的任何故障都不会影响主对话流程。
     */
    private static final java.util.Set<String> AUTO_RECON_APPEND_SKIPPED_TOOLS = java.util.Set.of(
            "manage_recon_summary",
            "createPlan", "updatePlan", "getPlan", "deletePlan",
            "dispatchSubtask",
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
            // 任何异常都不能让工具结果回写主对话失败 —— 这里完全是 best-effort 的旁路
            org.slf4j.LoggerFactory.getLogger(AgentConfig.class)
                    .debug("AutoReconAppend 触发失败 tool={} sessionId={}: {}",
                            toolName, sessionId, t.getMessage());
        }
    }

    // ── 子 Agent ──────────────────────────────────────────────────────────────

    /**
     * 子 Agent 通用构建逻辑：所有子 Agent 共享 chatModel、subAgentMemoryProvider、
     * subAgentToolExecutor 与同一对 beforeToolExecution / afterToolExecution hook。
     */
    private <T> T buildSubAgent(
            Class<T> agentClass,
            DelegatingChatModel model,
            ChatMemoryProvider memoryProvider,
            ExecutorService subExecutor,
            String systemPrompt,
            AutoReconAppendService autoReconAppendService,
            Object... tools) {

        return AiServices.builder(agentClass)
                .chatModel(model)
                .chatMemoryProvider(memoryProvider)
                .systemMessageProvider(memoryId -> systemPrompt)
                .executeToolsConcurrently(subExecutor)
                .beforeToolExecution(execution -> {
                    SubAgentDispatchTools.emitInnerToolStart(execution);
                    if (execution != null && execution.invocationContext() != null) {
                        AiToolContext.setFromMemoryId(execution.invocationContext().chatMemoryId());
                    }
                })
                .afterToolExecution(execution -> {
                    try {
                        SubAgentDispatchTools.emitInnerToolDone(execution);
                        triggerAutoReconAppend(execution, autoReconAppendService);
                    } finally {
                        AiToolContext.clear();
                    }
                })
                .tools(tools)
                .build();
    }

    /**
     * 侦察子 Agent：端口扫描、浏览器数据、凭据采集、剪贴板。
     * 非流式，由 SubAgentDispatchTools 同步调用。
     */
    @Bean
    public ReconSubAgent reconSubAgent(
            DelegatingChatModel model,
            @Qualifier("subAgentMemoryProvider") ChatMemoryProvider memoryProvider,
            ScanTools scanTools,
            BrowserDataTools browserDataTools,
            CredentialHarvestTools credentialHarvestTools,
            ClipboardTools clipboardTools,
            UserAccountTools userAccountTools,
            MountDiskTools mountDiskTools,
            DockerContainerTools dockerContainerTools,
            SuidCapabilityTools suidCapabilityTools,
            InstalledSoftwareTools installedSoftwareTools,
            AutoReconAppendService autoReconAppendService,
            @Qualifier("subAgentToolExecutor") ExecutorService subExecutor) {
        return buildSubAgent(ReconSubAgent.class, model, memoryProvider, subExecutor,
                SubAgentPrompts.RECON, autoReconAppendService,
                scanTools, browserDataTools, credentialHarvestTools, clipboardTools,
                userAccountTools, mountDiskTools, dockerContainerTools,
                suidCapabilityTools, installedSoftwareTools);
    }

    /**
     * 持久化子 Agent：计划任务、服务管理、事件日志、Tomcat 内存马、Java 插件。
     * 非流式，由 SubAgentDispatchTools 同步调用。
     */
    @Bean
    public PersistenceSubAgent persistenceSubAgent(
            DelegatingChatModel model,
            @Qualifier("subAgentMemoryProvider") ChatMemoryProvider memoryProvider,
            ScheduledTaskTools scheduledTaskTools,
            ServiceManagerTools serviceManagerTools,
            EventLogTools eventLogTools,
            CatalinaTools catalinaTools,
            JavaPluginTools javaPluginTools,
            AutoReconAppendService autoReconAppendService,
            @Qualifier("subAgentToolExecutor") ExecutorService subExecutor) {
        return buildSubAgent(PersistenceSubAgent.class, model, memoryProvider, subExecutor,
                SubAgentPrompts.PERSISTENCE, autoReconAppendService,
                scheduledTaskTools, serviceManagerTools, eventLogTools,
                catalinaTools, javaPluginTools);
    }

    /**
     * 攻击/利用子 Agent：HTTP 请求/Fuzz、脚本执行、SQL、资源读取。
     * 非流式，由 SubAgentDispatchTools 同步调用。
     */
    @Bean
    public ExploitSubAgent exploitSubAgent(
            DelegatingChatModel model,
            @Qualifier("subAgentMemoryProvider") ChatMemoryProvider memoryProvider,
            HttpRequestTools httpRequestTools,
            ScriptTools scriptTools,
            SqlTools sqlTools,
            ResourceTools resourceTools,
            AutoReconAppendService autoReconAppendService,
            @Qualifier("subAgentToolExecutor") ExecutorService subExecutor) {
        return buildSubAgent(ExploitSubAgent.class, model, memoryProvider, subExecutor,
                SubAgentPrompts.EXPLOIT, autoReconAppendService,
                httpRequestTools, scriptTools, sqlTools, resourceTools);
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
