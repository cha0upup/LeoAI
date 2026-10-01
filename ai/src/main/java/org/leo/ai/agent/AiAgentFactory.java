package org.leo.ai.agent;

import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.service.AiServices;
import org.leo.ai.service.AutoReconAppendService;
import org.leo.ai.service.AiPlanCoordinator;
import org.leo.core.ai.AiRuntimeState;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ExecutorService;

import static org.leo.ai.agent.AiToolAuthorizationPolicy.AiAgentScope.PLATFORM;
import static org.leo.ai.agent.AiToolAuthorizationPolicy.AiAgentScope.AGENT_NODE;

/**
 * Agent 构建工厂。
 *
 * <p>服务层按线程/通道构建运行时 Agent 时复用同一套工具、memory、system prompt
 * 和 LangChain4j 原生 AiServices 配置，避免通过全局代理模型临时切换通道。
 */
@Component
public class AiAgentFactory {

    private final AiChatMemoryProviderFactory memoryProviderFactory;
    private final NodeAiSystemPromptProvider nodeAiSystemPromptProvider;
    private final PlatformSystemPromptProvider platformSystemPromptProvider;
    private final NodeAiToolBundle nodeAiToolBundle;
    private final PlatformToolBundle platformToolBundle;
    private final AutoReconAppendService autoReconAppendService;
    private final AiToolErrorHandler toolErrorHandler;
    private final AiToolAuthorizationPolicy toolAuthorizationPolicy;
    private final AiToolCatalog toolCatalog;
    private final AiRuntimeResolver runtimeResolver;
    private final AiPlanCoordinator planCoordinator;
    private final ExecutorService nodeAiToolExecutor;
    private final ExecutorService platformToolExecutor;

    public AiAgentFactory(AiChatMemoryProviderFactory memoryProviderFactory,
                          NodeAiSystemPromptProvider nodeAiSystemPromptProvider,
                          PlatformSystemPromptProvider platformSystemPromptProvider,
                          NodeAiToolBundle nodeAiToolBundle,
                          PlatformToolBundle platformToolBundle,
                          AutoReconAppendService autoReconAppendService,
                          AiToolErrorHandler toolErrorHandler,
                          AiToolAuthorizationPolicy toolAuthorizationPolicy,
                          AiToolCatalog toolCatalog,
                          AiRuntimeResolver runtimeResolver,
                          AiPlanCoordinator planCoordinator,
                          @Qualifier("nodeAiToolExecutor")
                          ExecutorService nodeAiToolExecutor,
                          @Qualifier("platformAiToolExecutor")
                          ExecutorService platformToolExecutor) {
        this.memoryProviderFactory = memoryProviderFactory;
        this.nodeAiSystemPromptProvider = nodeAiSystemPromptProvider;
        this.platformSystemPromptProvider = platformSystemPromptProvider;
        this.nodeAiToolBundle = nodeAiToolBundle;
        this.platformToolBundle = platformToolBundle;
        this.autoReconAppendService = autoReconAppendService;
        this.toolErrorHandler = toolErrorHandler;
        this.toolAuthorizationPolicy = toolAuthorizationPolicy;
        this.toolCatalog = toolCatalog;
        this.runtimeResolver = runtimeResolver;
        this.planCoordinator = planCoordinator;
        this.nodeAiToolExecutor = nodeAiToolExecutor;
        this.platformToolExecutor = platformToolExecutor;
    }

    public NodeAiAgent createNodeAiAgent(StreamingChatModel streamingModel,
                                                 boolean enableTools,
                                                 int modelContextWindowTokens) {
        int toolSchemaTokens = enableTools
                ? toolCatalog.estimateSchemaTokens(nodeAiToolBundle.tools()) : 0;
        var builder = AiServices.builder(NodeAiAgent.class)
                .streamingChatModel(streamingModel)
                .chatMemoryProvider(memoryProviderFactory.createNodeAiProvider(
                        modelContextWindowTokens, toolSchemaTokens))
                .systemMessageProvider(nodeAiSystemPromptProvider::getSystemMessage)
                .executeToolsConcurrently(nodeAiToolExecutor)
                .toolArgumentsErrorHandler(
                        toolErrorHandler::handleArguments)
                .toolExecutionErrorHandler(
                        toolErrorHandler::handleExecution)
                .hallucinatedToolNameStrategy(
                        toolErrorHandler::handleUnknownTool)
                .beforeToolExecution(execution -> {
                    prepareToolExecution(AGENT_NODE, execution);
                })
                .afterToolExecution(execution -> {
                    try {
                        triggerAutoReconAppend(execution);
                        appendToolResultToPlan(execution);
                        if (execution != null
                                && !AiToolErrorHandler.isErrorResult(execution)) {
                            toolErrorHandler.recordSuccess(
                                    execution.invocationContext().chatMemoryId(),
                                    execution.request().name());
                        }
                    } finally {
                        AiToolContext.clear();
                    }
                });
        if (enableTools) {
            builder.toolProvider(toolAuthorizationPolicy.toolProvider(
                    AGENT_NODE, nodeAiToolBundle.tools().toArray()));
        }
        return builder.build();
    }

    /**
     * 创建平台 Agent，并按运行入口追加可选工具。
     *
     * <p>桥接 Node AI 的工具位于 web 模块，不能反向成为 ai 模块的固定依赖，
     * 因此由 web 模块的 Agent 注册表在构建线程运行时时注入。
     */
    public PlatformAiAgent createPlatformAiAgent(StreamingChatModel streamingModel,
                                             boolean enableTools,
                                             int modelContextWindowTokens,
                                             Object additionalTools) {
        List<Object> tools = platformToolBundle.toolsWith(additionalTools);
        int toolSchemaTokens = enableTools
                ? toolCatalog.estimateSchemaTokens(tools) : 0;
        var builder = AiServices.builder(PlatformAiAgent.class)
                .streamingChatModel(streamingModel)
                .chatMemoryProvider(memoryProviderFactory.createPlatformProvider(
                        modelContextWindowTokens, toolSchemaTokens))
                .systemMessageProvider(platformSystemPromptProvider::getSystemMessage)
                .executeToolsConcurrently(platformToolExecutor)
                .toolArgumentsErrorHandler(
                        toolErrorHandler::handleArguments)
                .toolExecutionErrorHandler(
                        toolErrorHandler::handleExecution)
                .hallucinatedToolNameStrategy(
                        toolErrorHandler::handleUnknownTool)
                .beforeToolExecution(execution -> {
                    prepareToolExecution(PLATFORM, execution);
                })
                .afterToolExecution(execution -> {
                    try {
                        appendToolResultToPlan(execution);
                        if (execution != null
                                && !AiToolErrorHandler.isErrorResult(execution)) {
                            toolErrorHandler.recordSuccess(
                                    execution.invocationContext().chatMemoryId(),
                                    execution.request().name());
                        }
                    } finally {
                        AiToolContext.clear();
                    }
                });
        if (enableTools) {
            builder.toolProvider(toolAuthorizationPolicy.toolProvider(
                    PLATFORM, tools.toArray()));
        }
        return builder.build();
    }

    private void triggerAutoReconAppend(dev.langchain4j.service.tool.ToolExecution execution) {
        if (execution == null
                || AiToolErrorHandler.isErrorResult(execution)) return;
        String toolName = execution.request() != null ? execution.request().name() : null;
        AiToolDescriptor descriptor = toolCatalog.get(toolName);
        if (toolName == null || !descriptor.business()) return;

        String sessionId = AiToolContext.getSessionId();
        if (sessionId == null || sessionId.isBlank()) return;

        String result = resultForInternalUse(execution);
        if (result == null || result.isBlank()) return;

        try {
            autoReconAppendService.analyzeAndAppend(sessionId, toolName, result);
        } catch (Throwable t) {
            org.slf4j.LoggerFactory.getLogger(AiAgentFactory.class)
                    .debug("AutoReconAppend 触发失败 tool={} sessionId={}: {}",
                            toolName, sessionId, t.getMessage());
        }
    }

    private void prepareToolExecution(
            AiToolAuthorizationPolicy.AiAgentScope scope,
            dev.langchain4j.service.tool.BeforeToolExecution execution) {
        if (execution == null || execution.invocationContext() == null) return;
        toolAuthorizationPolicy.bindContext(
                scope, execution.invocationContext().chatMemoryId());
        AiToolDescriptor descriptor = toolCatalog.get(execution.request().name());
        AiToolContext.setToolDescriptor(descriptor);
        try {
            AiRuntimeState runtime = runtimeResolver.resolveCurrent();
            planCoordinator.checkTimeouts(runtime);
            if (descriptor.business()) {
                int stepIndex = planCoordinator.inProgressStepIndex(runtime);
                if (stepIndex >= 0) AiToolContext.setPlanStepIndex(stepIndex);
            }
        } catch (Exception ignored) {
            // 计划关联为 best-effort，不阻断工具调用。
        }
    }

    private void appendToolResultToPlan(
            dev.langchain4j.service.tool.ToolExecution execution) {
        int stepIndex = AiToolContext.getPlanStepIndex();
        if (stepIndex < 0 || execution == null) return;

        String toolName = execution.request() != null ? execution.request().name() : null;
        if (toolName == null || !toolCatalog.get(toolName).business()) return;

        try {
            planCoordinator.appendToolResult(
                    runtimeResolver.resolveCurrent(), stepIndex, toolName,
                    buildToolResultSummary(toolName, execution));
        } catch (Exception ignored) {
            // best-effort
        }
    }

    private static String buildToolResultSummary(String toolName,
                                                  dev.langchain4j.service.tool.ToolExecution execution) {
        StringBuilder sb = new StringBuilder(toolName);
        if (AiToolErrorHandler.isErrorResult(execution)) {
            sb.append(" 失败");
            String err = execution.result();
            if (err != null && !err.isBlank()) {
                String shortErr = err.length() > 80 ? err.substring(0, 80) + "…" : err;
                sb.append("（").append(shortErr).append("）");
            }
        } else {
            sb.append(" 完成");
            String result = resultForInternalUse(execution);
            if (result != null && !result.isBlank()) {
                String firstLine = result.lines().findFirst().orElse("");
                String shortResult = firstLine.length() > 80 ? firstLine.substring(0, 80) + "…" : firstLine;
                sb.append(" → ").append(shortResult);
            }
        }
        return sb.toString();
    }

    private static String resultForInternalUse(
            dev.langchain4j.service.tool.ToolExecution execution) {
        if (execution == null) return null;
        Object raw = execution.resultObject();
        if (raw instanceof String text) return text;
        if (raw != null) {
            try {
                return org.leo.core.util.json.JsonUtil.toJsonString(raw);
            } catch (RuntimeException ignored) {
                return String.valueOf(raw);
            }
        }
        return execution.result();
    }

}
