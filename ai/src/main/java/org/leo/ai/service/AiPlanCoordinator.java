package org.leo.ai.service;

import org.leo.ai.agent.AiToolException;
import org.leo.core.ai.AiRuntimeState;
import org.leo.core.entity.AiPlan;
import org.leo.core.entity.AiPlanStep;
import org.leo.core.entity.AiStepStatus;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Platform/Agent 共用的计划状态机与事件发布器。 */
@Component
public class AiPlanCoordinator {

    private static final int MAX_PLAN_STEPS = 32;

    public AiPlan create(AiRuntimeState runtime, String title, String goal,
                         List<AiPlanStepInput> steps, long stepTimeoutMs) {
        AiPlan plan = new AiPlan(title, goal, normalizeSteps(steps));
        if (stepTimeoutMs > 0) plan.setStepTimeoutMs(stepTimeoutMs);
        runtime.addPlan(plan);
        emitPlan(runtime, plan, true);
        return plan;
    }

    public AiPlan update(AiRuntimeState runtime, int stepIndex,
                         String action, String resultText) {
        AiPlan plan = requirePlan(runtime);
        String normalizedAction = action == null ? "" : action.trim();
        String text = resultText == null || resultText.isBlank()
                ? null : resultText.trim();
        boolean updated = switch (normalizedAction) {
            case "start" -> plan.startStep(stepIndex);
            case "complete" -> plan.completeStep(stepIndex, text);
            case "fail" -> plan.failStep(stepIndex, text);
            case "skip" -> plan.skipStep(stepIndex, text);
            default -> throw AiToolException.modelCorrectable(
                    "INVALID_ACTION",
                    "无效的 action：" + action + "，可选值：start | complete | fail | skip",
                    "将 action 改为 start、complete、fail 或 skip。");
        };
        if (!updated) throw transitionError(plan, stepIndex, normalizedAction, text);
        emitPlanStep(runtime, plan, stepIndex, normalizedAction, text, null);
        emitPlan(runtime, plan, false);
        return plan;
    }

    public AiPlan complete(AiRuntimeState runtime, String finalSummary) {
        AiPlan plan = requirePlan(runtime);
        try {
            plan.complete(finalSummary);
        } catch (IllegalStateException error) {
            throw AiToolException.modelCorrectable(
                    "PLAN_STEPS_ACTIVE", error.getMessage(),
                    "先将所有 PENDING/IN_PROGRESS 步骤完成、失败或跳过，再结束计划。");
        }
        emitPlan(runtime, plan, false);
        return plan;
    }

    public int checkTimeouts(AiRuntimeState runtime) {
        AiPlan plan = runtime != null ? runtime.getCurrentPlan() : null;
        if (plan == null) return 0;
        int count = plan.checkStepTimeouts();
        if (count > 0) emitPlan(runtime, plan, false);
        return count;
    }

    public int inProgressStepIndex(AiRuntimeState runtime) {
        AiPlan plan = runtime != null ? runtime.getCurrentPlan() : null;
        if (plan == null || plan.getSteps() == null) return -1;
        for (AiPlanStep step : plan.getSteps()) {
            if (step.getStatus() == AiStepStatus.IN_PROGRESS) return step.getIndex();
        }
        return -1;
    }

    public void appendToolResult(AiRuntimeState runtime, int stepIndex,
                                 String toolName, String summary) {
        AiPlan plan = runtime != null ? runtime.getCurrentPlan() : null;
        AiPlanStep step = plan != null ? plan.getStep(stepIndex) : null;
        if (step == null || summary == null || summary.isBlank()) return;
        step.setResult(step.getResult() != null && !step.getResult().isBlank()
                ? step.getResult() + " | " + summary : summary);
        emitPlanStep(runtime, plan, stepIndex, "tool_result", summary, toolName);
    }

    private static AiPlan requirePlan(AiRuntimeState runtime) {
        AiPlan plan = runtime != null ? runtime.getCurrentPlan() : null;
        if (plan == null) {
            throw AiToolException.modelCorrectable(
                    "PLAN_NOT_FOUND", "当前没有可操作的计划。",
                    "先调用 createPlan 创建计划，再操作计划步骤。");
        }
        return plan;
    }

    private static AiToolException transitionError(
            AiPlan plan, int stepIndex, String action, String text) {
        AiPlanStep step = plan.getStep(stepIndex);
        if (step == null) {
            return AiToolException.modelCorrectable(
                    "PLAN_STEP_NOT_FOUND", "未找到指定步骤: " + stepIndex,
                    "检查当前计划中的步骤索引后重新调用。");
        }
        if ("start".equals(action)) {
            if (step.getStatus() == AiStepStatus.FAILED && !step.canStart()) {
                return AiToolException.modelCorrectable(
                        "PLAN_RETRY_EXHAUSTED", "步骤 " + stepIndex + " 已达到最大重试次数。",
                        "不要再次启动；请结束失败计划并在最终结论中说明原因。");
            }
            return AiToolException.modelCorrectable(
                    "PLAN_DEPENDENCY_INCOMPLETE",
                    "步骤 " + stepIndex + " 的依赖步骤尚未成功完成，无法启动。",
                    "dependsOn 中的步骤必须为 COMPLETED；失败或跳过不视为满足依赖。");
        }
        if ("complete".equals(action)
                && step.getSuccessCriteria() != null
                && !step.getSuccessCriteria().isBlank()
                && (text == null || text.isBlank())) {
            return AiToolException.modelCorrectable(
                    "PLAN_SUCCESS_EVIDENCE_REQUIRED",
                    "步骤 " + stepIndex + " 定义了成功标准，完成时必须提供结果摘要。",
                    "在 resultText 中写明满足 successCriteria 的实际证据。");
        }
        return AiToolException.modelCorrectable(
                "PLAN_INVALID_TRANSITION",
                "步骤 " + stepIndex + " 当前状态为 " + step.getStatus()
                        + "，不能执行 " + action + "。",
                "先按 PENDING → IN_PROGRESS → COMPLETED/FAILED 的顺序更新；skip 可用于放弃未完成步骤。");
    }

    private static void emitPlan(AiRuntimeState runtime, AiPlan plan, boolean create) {
        if (runtime == null || plan == null) return;
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("kind", "plan");
        payload.put("planId", plan.getPlanId());
        payload.put("title", plan.getTitle());
        payload.put("goal", plan.getGoal());
        payload.put("status", plan.getStatus().name());
        payload.put("steps", plan.getSteps());
        if (plan.getFinalSummary() != null) payload.put("finalSummary", plan.getFinalSummary());
        runtime.offerSseEvent(create ? "node" : "patch", payload);
    }

    private static void emitPlanStep(AiRuntimeState runtime, AiPlan plan,
                                     int stepIndex, String action, String text,
                                     String toolName) {
        AiPlanStep step = plan.getStep(stepIndex);
        if (step == null) return;
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("kind", "plan");
        payload.put("planId", plan.getPlanId());
        payload.put("stepIndex", step.getIndex());
        payload.put("action", action);
        payload.put("status", step.getStatus().name());
        payload.put("description", step.getDescription());
        payload.put("toolHint", step.getToolHint());
        payload.put("parallel", step.isParallel());
        payload.put("successCriteria", step.getSuccessCriteria());
        payload.put("result", step.getResult());
        payload.put("reason", step.getReason());
        payload.put("text", text);
        if (toolName != null) payload.put("toolName", toolName);
        if (step.getStartedAt() > 0) payload.put("startedAt", step.getStartedAt());
        if (step.getCompletedAt() > 0) {
            payload.put("completedAt", step.getCompletedAt());
            if (step.getStartedAt() > 0) {
                payload.put("durationMs", step.getCompletedAt() - step.getStartedAt());
            }
        }
        payload.put("timestamp", System.currentTimeMillis());
        runtime.offerSseEvent("patch", payload);
    }

    private static List<AiPlanStep> normalizeSteps(List<AiPlanStepInput> steps) {
        List<AiPlanStep> result = new ArrayList<>();
        if (steps == null) return result;
        if (steps.size() > MAX_PLAN_STEPS) {
            throw AiToolException.modelCorrectable(
                    "PLAN_TOO_LARGE",
                    "计划步骤不能超过 " + MAX_PLAN_STEPS + " 个。",
                    "合并过细步骤，只保留可独立验证的关键阶段。");
        }
        for (AiPlanStepInput raw : steps) {
            if (raw == null) continue;
            String description = text(raw.getDescription());
            if (description.isBlank()) continue;
            int index = result.size();
            List<Integer> dependsOn = raw.getDependsOn() == null
                    ? List.of() : List.copyOf(raw.getDependsOn());
            if (dependsOn.stream().anyMatch(dependency ->
                    dependency < 0 || dependency >= index)) {
                throw AiToolException.modelCorrectable(
                        "PLAN_INVALID_DEPENDENCY",
                        "步骤 " + index + " 只能依赖它之前已存在的步骤。",
                        "将 dependsOn 改为小于当前步骤序号的非负整数。");
            }
            result.add(new AiPlanStep(
                    index, description,
                    text(raw.getToolHint()), Boolean.TRUE.equals(raw.getParallel()),
                    text(raw.getSuccessCriteria()),
                    raw.getMaxRetries() != null ? raw.getMaxRetries() : 1, dependsOn));
        }
        return result;
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

}
