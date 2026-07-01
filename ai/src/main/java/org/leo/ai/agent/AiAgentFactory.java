package org.leo.ai.agent;

import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.service.AiServices;
import org.leo.ai.service.AutoReconAppendService;
import org.leo.ai.tools.platform.DisguiseTools;
import org.leo.ai.tools.platform.FingerprintTools;
import org.leo.ai.tools.platform.PluginTools;
import org.leo.ai.tools.platform.PuppetTools;
import org.leo.ai.tools.platform.ShellGeneratorTools;
import org.leo.ai.tools.platform.SkillActivationTools;
import org.leo.ai.tools.platform.TeamTools;
import org.leo.ai.tools.platform.UserTools;
import org.leo.ai.tools.puppetnode.BasicInfoTools;
import org.leo.ai.tools.puppetnode.BrowserDataTools;
import org.leo.ai.tools.puppetnode.CatalinaTools;
import org.leo.ai.tools.puppetnode.ClipboardTools;
import org.leo.ai.tools.puppetnode.CommandTools;
import org.leo.ai.tools.puppetnode.CredentialHarvestTools;
import org.leo.ai.tools.puppetnode.FileTools;
import org.leo.ai.tools.puppetnode.HttpRequestTools;
import org.leo.ai.tools.puppetnode.JavaPluginTools;
import org.leo.ai.tools.puppetnode.PlanTools;
import org.leo.ai.tools.puppetnode.ResourceTools;
import org.leo.ai.tools.puppetnode.ReverseTunnelTools;
import org.leo.ai.tools.puppetnode.ScanTools;
import org.leo.ai.tools.puppetnode.ScriptTools;
import org.leo.ai.tools.puppetnode.SessionTools;
import org.leo.ai.tools.puppetnode.SqlTools;
import org.leo.ai.tools.puppetnode.UtilTools;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ExecutorService;

/**
 * Agent 构建工厂。
 *
 * <p>服务层按线程/通道构建运行时 Agent 时复用同一套工具、memory、system prompt
 * 和 LangChain4j 原生 AiServices 配置，避免通过全局代理模型临时切换通道。
 */
@Component
public class AiAgentFactory {

    private final ChatMemoryProvider memoryProvider;
    private final PuppetNodeSystemPromptProvider puppetNodeSystemPromptProvider;
    private final PlatformSystemPromptProvider platformSystemPromptProvider;
    private final CommandTools commandTools;
    private final BasicInfoTools basicInfoTools;
    private final ReverseTunnelTools reverseTunnelTools;
    private final UtilTools utilTools;
    private final FileTools fileTools;
    private final ScanTools scanTools;
    private final BrowserDataTools browserDataTools;
    private final CredentialHarvestTools credentialHarvestTools;
    private final ClipboardTools clipboardTools;
    private final CatalinaTools catalinaTools;
    private final JavaPluginTools javaPluginTools;
    private final HttpRequestTools httpRequestTools;
    private final ScriptTools scriptTools;
    private final SqlTools sqlTools;
    private final ResourceTools resourceTools;
    private final SessionTools sessionTools;
    private final PlanTools planTools;
    private final SkillActivationTools puppetNodeSkillActivationTools;
    private final AutoReconAppendService autoReconAppendService;
    private final PuppetTools puppetTools;
    private final UserTools userTools;
    private final TeamTools teamTools;
    private final PluginTools pluginTools;
    private final FingerprintTools fingerprintTools;
    private final DisguiseTools disguiseTools;
    private final ShellGeneratorTools shellGeneratorTools;
    private final SkillActivationTools platformSkillActivationTools;
    private final ExecutorService aiToolExecutor;

    public AiAgentFactory(ChatMemoryProvider memoryProvider,
                          PuppetNodeSystemPromptProvider puppetNodeSystemPromptProvider,
                          PlatformSystemPromptProvider platformSystemPromptProvider,
                          CommandTools commandTools,
                          BasicInfoTools basicInfoTools,
                          ReverseTunnelTools reverseTunnelTools,
                          UtilTools utilTools,
                          FileTools fileTools,
                          ScanTools scanTools,
                          BrowserDataTools browserDataTools,
                          CredentialHarvestTools credentialHarvestTools,
                          ClipboardTools clipboardTools,
                          CatalinaTools catalinaTools,
                          JavaPluginTools javaPluginTools,
                          HttpRequestTools httpRequestTools,
                          ScriptTools scriptTools,
                          SqlTools sqlTools,
                          ResourceTools resourceTools,
                          SessionTools sessionTools,
                          PlanTools planTools,
                          @Qualifier("puppetNodeSkillActivationTools") SkillActivationTools puppetNodeSkillActivationTools,
                          AutoReconAppendService autoReconAppendService,
                          PuppetTools puppetTools,
                          UserTools userTools,
                          TeamTools teamTools,
                          PluginTools pluginTools,
                          FingerprintTools fingerprintTools,
                          DisguiseTools disguiseTools,
                          ShellGeneratorTools shellGeneratorTools,
                          @Qualifier("platformSkillActivationTools") SkillActivationTools platformSkillActivationTools,
                          ExecutorService aiToolExecutor) {
        this.memoryProvider = memoryProvider;
        this.puppetNodeSystemPromptProvider = puppetNodeSystemPromptProvider;
        this.platformSystemPromptProvider = platformSystemPromptProvider;
        this.commandTools = commandTools;
        this.basicInfoTools = basicInfoTools;
        this.reverseTunnelTools = reverseTunnelTools;
        this.utilTools = utilTools;
        this.fileTools = fileTools;
        this.scanTools = scanTools;
        this.browserDataTools = browserDataTools;
        this.credentialHarvestTools = credentialHarvestTools;
        this.clipboardTools = clipboardTools;
        this.catalinaTools = catalinaTools;
        this.javaPluginTools = javaPluginTools;
        this.httpRequestTools = httpRequestTools;
        this.scriptTools = scriptTools;
        this.sqlTools = sqlTools;
        this.resourceTools = resourceTools;
        this.sessionTools = sessionTools;
        this.planTools = planTools;
        this.puppetNodeSkillActivationTools = puppetNodeSkillActivationTools;
        this.autoReconAppendService = autoReconAppendService;
        this.puppetTools = puppetTools;
        this.userTools = userTools;
        this.teamTools = teamTools;
        this.pluginTools = pluginTools;
        this.fingerprintTools = fingerprintTools;
        this.disguiseTools = disguiseTools;
        this.shellGeneratorTools = shellGeneratorTools;
        this.platformSkillActivationTools = platformSkillActivationTools;
        this.aiToolExecutor = aiToolExecutor;
    }

    public PuppetNodeAgent createPuppetNodeAgent(StreamingChatModel streamingModel, ChatModel chatModel) {
        return createPuppetNodeAgent(streamingModel, chatModel, true);
    }

    public PuppetNodeAgent createPuppetNodeAgent(StreamingChatModel streamingModel,
                                                 ChatModel chatModel,
                                                 boolean enableTools) {
        var builder = AiServices.builder(PuppetNodeAgent.class)
                .streamingChatModel(streamingModel)
                .chatModel(chatModel)
                .chatMemoryProvider(memoryProvider)
                .systemMessageProvider(puppetNodeSystemPromptProvider::getSystemMessage)
                .executeToolsConcurrently(aiToolExecutor)
                .beforeToolExecution(execution -> {
                    if (execution != null && execution.invocationContext() != null) {
                        AiToolContext.setFromMemoryId(execution.invocationContext().chatMemoryId());
                    }
                    autoAssociatePlanStep();
                })
                .afterToolExecution(execution -> {
                    try {
                        triggerAutoReconAppend(execution);
                        autoAppendToolResultToPlanStep(execution);
                    } finally {
                        AiToolContext.clear();
                    }
                });
        if (enableTools) {
            builder.tools(commandTools, basicInfoTools,
                    reverseTunnelTools,
                    utilTools, fileTools,
                    scanTools, browserDataTools, credentialHarvestTools, clipboardTools,
                    catalinaTools, javaPluginTools,
                    httpRequestTools, scriptTools, sqlTools, resourceTools,
                    sessionTools, planTools, puppetNodeSkillActivationTools);
        }
        return builder.build();
    }

    public PlatformAgent createPlatformAgent(StreamingChatModel streamingModel) {
        return createPlatformAgent(streamingModel, true);
    }

    public PlatformAgent createPlatformAgent(StreamingChatModel streamingModel, boolean enableTools) {
        var builder = AiServices.builder(PlatformAgent.class)
                .streamingChatModel(streamingModel)
                .chatMemoryProvider(memoryProvider)
                .systemMessageProvider(platformSystemPromptProvider::getSystemMessage)
                .executeToolsConcurrently(aiToolExecutor);
        if (enableTools) {
            builder.tools(puppetTools, userTools, teamTools,
                    pluginTools, fingerprintTools, disguiseTools,
                    shellGeneratorTools, platformSkillActivationTools);
        }
        return builder.build();
    }

    private static final java.util.Set<String> AUTO_RECON_APPEND_SKIPPED_TOOLS = java.util.Set.of(
            "manage_recon_summary",
            "createPlan", "updatePlan", "getPlan", "deletePlan",
            "activate_skill"
    );

    private void triggerAutoReconAppend(dev.langchain4j.service.tool.ToolExecution execution) {
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
            org.slf4j.LoggerFactory.getLogger(AiAgentFactory.class)
                    .debug("AutoReconAppend 触发失败 tool={} sessionId={}: {}",
                            toolName, sessionId, t.getMessage());
        }
    }

    private static final java.util.Set<String> PLAN_TOOLS = java.util.Set.of(
            "createPlan", "updatePlanStep", "completePlan");

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
                    thread.offerSseEvent("patch", buildPlanStepPatch(plan, step, toolName));
                    break;
                }
            }
        } catch (Exception ignored) {
            // best-effort
        }
    }

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
                String firstLine = result.lines().findFirst().orElse("");
                String shortResult = firstLine.length() > 80 ? firstLine.substring(0, 80) + "…" : firstLine;
                sb.append(" → ").append(shortResult);
            }
        }
        return sb.toString();
    }

    private static Map<String, Object> buildPlanStepPatch(
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
}
