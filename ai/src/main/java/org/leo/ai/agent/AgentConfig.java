package org.leo.ai.agent;

import dev.langchain4j.memory.chat.TokenWindowChatMemory;
import dev.langchain4j.model.TokenCountEstimator;
import org.leo.ai.channel.DelegatingChatModel;
import org.leo.ai.config.AiAgentProperties;
import org.leo.ai.runtime.AiTurnTelemetryRegistry;
import org.leo.ai.service.SkillRegistryService;
import org.leo.ai.thread.AiConversationStoreService;
import org.leo.ai.tools.platform.SkillActivationTools;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * LangChain4j Agent 配置。
 *
 * <p>会话 Agent 由 {@link AiAgentFactory} 按所选模型创建；摘要等辅助服务通过
 * {@link DelegatingChatModel} 使用可热切换的默认模型。
 *
 * <p>线程池架构：
 * <ul>
 *   <li>{@code rawAiToolExecutor} — 底层固定 12 线程池，承载所有工具执行</li>
 *   <li>{@code puppetNodeAiToolExecutor} — Puppet Agent 独立限流器</li>
 *   <li>{@code platformAiToolExecutor} — Platform Agent 独立限流器</li>
 * </ul>
 *
 * <p>所有工具直接附着到主 Agent，无子 Agent 调度层。
 * 纯 OS 命令包装工具已移除，统一通过 exec 工具替代。
 */
@Configuration
@EnableAsync
public class AgentConfig {

    private static final int TOOL_EXECUTOR_THREADS = 12;
    private static final int TOOL_BOUNDARY_THREADS = 16;

    // ── 注入依赖 ──────────────────────────────────────────────────────────────

    private final AiAgentProperties agentProps;

    public AgentConfig(AiAgentProperties agentProps) {
        this.agentProps = agentProps;
    }

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

    /** 独立保护池，避免工具外层并发池等待自身子任务造成线程池饥饿。 */
    @Bean(name = "aiToolBoundaryExecutor", destroyMethod = "shutdown")
    public ExecutorService aiToolBoundaryExecutor() {
        AtomicInteger counter = new AtomicInteger(1);
        return Executors.newFixedThreadPool(TOOL_BOUNDARY_THREADS, runnable -> {
            Thread thread = new Thread(runnable,
                    "ai-tool-boundary-" + counter.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * Puppet Agent 工具执行器。
     */
    @Bean("puppetNodeAiToolExecutor")
    public ExecutorService puppetNodeAiToolExecutor(
            @Qualifier("rawAiToolExecutor") ExecutorService raw) {
        int maxParallel = agentProps.getPuppetNode().getMain().getMaxParallelTools();
        return new ThrottledExecutorService(raw, maxParallel);
    }

    /** Platform Agent 工具执行器。 */
    @Bean("platformAiToolExecutor")
    public ExecutorService platformAiToolExecutor(
            @Qualifier("rawAiToolExecutor") ExecutorService raw) {
        int maxParallel = agentProps.getPlatform().getMain().getMaxParallelTools();
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
            DelegatingChatModel chatModel,
            TokenCountEstimator tokenEstimator,
            AiConversationStoreService conversationStore,
            AiTurnTelemetryRegistry telemetryRegistry) {
        return new ContextCompressionService(
                chatModel, tokenEstimator, conversationStore, telemetryRegistry);
    }

    // ── 模型 Bean ────────────────────────────────────────────────────────────

    /**
     * 代理非流式模型 Bean。辅助服务（摘要、情报提取）注入此 Bean。
     */
    @Bean
    public DelegatingChatModel delegatingChatModel() {
        return new DelegatingChatModel();
    }

    // ── Skill 工具 Bean ───────────────────────────────────────────────────────

    @Bean
    public SkillActivationTools puppetNodeSkillActivationTools(
            SkillRegistryService skillRegistry,
            AgentRuntimeResolver runtimeResolver) {
        return new SkillActivationTools(skillRegistry,
                SkillRegistryService.SCOPE_PUPPET_NODE, runtimeResolver);
    }

    @Bean
    public SkillActivationTools platformSkillActivationTools(
            SkillRegistryService skillRegistry,
            AgentRuntimeResolver runtimeResolver) {
        return new SkillActivationTools(skillRegistry,
                SkillRegistryService.SCOPE_PLATFORM, runtimeResolver);
    }

}
