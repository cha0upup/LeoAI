package org.leo.ai.tools.puppetnode;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.leo.ai.agent.AiToolContext;
import org.leo.core.entity.AiPlan;
import org.leo.core.entity.AiPlanStep;
import org.leo.core.entity.AiStepStatus;
import org.leo.core.session.AiThread;
import org.leo.core.session.PuppetNodeSession;
import org.leo.core.session.PuppetNodeSessionContainer;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 计划工具。用于让 AI 在执行前显式创建步骤计划，并在执行过程中推进步骤状态。
 *
 * <p>所有变更都会通过当前线程的 SSE 队列向前端发出 {@code plan} 事件，
 * 让过程面板可以实时展示"我将先做什么、当前推进到哪一步"。
 *
 * <p>sessionId 与 threadId 通过 {@link AiToolContext} ThreadLocal 自动注入。
 */
@Component
public class PlanTools {

    @Tool("""
            创建当前对话的执行计划。steps 传入步骤列表，每个步骤对象建议包含：
            description, toolHint, parallel, successCriteria, maxRetries, dependsOn。
            stepTimeoutMs 可选，单步骤超时时间（毫秒），超过此时间的步骤将被自动标记为失败，0 表示不启用。
            适合在开始执行前先明确"先做什么、后做什么"。
            """)
    public Map<String, Object> createPlan(
            @P("计划标题") String title,
            @P("任务目标") String goal,
            @P("步骤列表（对象数组）") List<Map<String, Object>> steps,
            @P("步骤超时毫秒，0 表示不启用（可选，默认 0）") long stepTimeoutMs) {
        AiThread thread = resolveThread();
        if (thread == null) {
            throw new IllegalStateException("会话或线程不存在，无法创建计划");
        }
        AiPlan plan = new AiPlan(title, goal, normalizeSteps(steps));
        if (stepTimeoutMs > 0) {
            plan.setStepTimeoutMs(stepTimeoutMs);
        }
        thread.addPlan(plan);
        emitPlan(thread, plan, true);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("plan", plan);
        return result;
    }

    @Tool("""
            更新当前计划中指定步骤的状态。
            action 取值：start（标记执行中）、complete（标记完成）、fail（标记失败）、skip（标记跳过）。
            resultText 在 complete/fail/skip 时填写结果摘要或原因；start 时可不传。
            stepIndex 从 0 开始。
            """)
    public Map<String, Object> updatePlanStep(
            @P("步骤序号，从 0 开始") int stepIndex,
            @P("操作：start | complete | fail | skip") String action,
            @P("结果摘要或原因（complete/fail/skip 时填写，start 时可留空）") String resultText) {
        AiThread thread = resolveThread();
        if (thread == null) {
            throw new IllegalStateException("会话或线程不存在，无法更新计划");
        }
        AiPlan plan = thread.getCurrentPlan();
        if (plan == null) {
            throw new IllegalStateException("当前没有可更新的计划");
        }

        boolean ok;
        String text = (resultText == null || resultText.isBlank()) ? null : resultText.trim();
        switch (action == null ? "" : action.trim()) {
            case "start"    -> ok = plan.startStep(stepIndex);
            case "complete" -> ok = plan.completeStep(stepIndex, text);
            case "fail"     -> ok = plan.failStep(stepIndex, text);
            case "skip"     -> ok = plan.skipStep(stepIndex, text);
            default         -> { throw new IllegalArgumentException("无效的 action：" + action + "，可选值：start | complete | fail | skip"); }
        }
        if (!ok) {
            if ("start".equals(action) && plan.getSteps().stream().anyMatch(s -> s.getIndex() == stepIndex)) {
                throw new IllegalStateException("步骤 " + stepIndex + " 的依赖步骤尚未完成，无法启动。请先完成依赖步骤。");
            }
            throw new IllegalStateException("未找到指定步骤: " + stepIndex);
        }

        emitPlanStep(thread, plan, stepIndex, action, text);
        emitPlan(thread, plan, false);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("plan", plan);
        return result;
    }

    @Tool("结束当前计划并写入最终结论。")
    public Map<String, Object> completePlan(
            @P("最终结论") String finalSummary) {
        AiThread thread = resolveThread();
        if (thread == null) {
            throw new IllegalStateException("会话或线程不存在，无法完成计划");
        }
        AiPlan plan = thread.getCurrentPlan();
        if (plan == null) {
            throw new IllegalStateException("当前没有可完成的计划");
        }
        plan.complete(finalSummary);
        emitPlan(thread, plan, false);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("plan", plan);
        return result;
    }

    // ── 计划预批准（供 REST API 调用） ──────────────────────────────────────

    /**
     * 预批准指定步骤（非 @Tool 方法，仅供 REST 控制器调用）。
     */
    public Map<String, Object> preApproveStep(String sessionId, String threadId, int stepIndex) {
        AiThread thread = resolveThreadByIds(sessionId, threadId);
        Map<String, Object> result = new LinkedHashMap<>();
        if (thread == null) return fail(result, "会话或线程不存在");
        AiPlan plan = thread.getCurrentPlan();
        if (plan == null) return fail(result, "当前没有活跃计划");
        int totalSteps = plan.getSteps() == null ? 0 : plan.getSteps().size();
        if (stepIndex < 0 || stepIndex >= totalSteps) {
            return fail(result, String.format("步骤序号越界：传入 %d，当前计划共 %d 步", stepIndex, totalSteps));
        }
        if (!plan.setStepPreApproved(stepIndex, true)) {
            return fail(result, "未找到步骤: " + stepIndex);
        }
        emitPlan(thread, plan, false);
        result.put("success", true);
        result.put("stepIndex", stepIndex);
        result.put("preApproved", true);
        return result;
    }

    /**
     * 预批准所有待执行步骤（非 @Tool 方法，仅供 REST 控制器调用）。
     */
    public Map<String, Object> preApproveAllSteps(String sessionId, String threadId) {
        AiThread thread = resolveThreadByIds(sessionId, threadId);
        Map<String, Object> result = new LinkedHashMap<>();
        if (thread == null) return fail(result, "会话或线程不存在");
        AiPlan plan = thread.getCurrentPlan();
        if (plan == null) return fail(result, "当前没有活跃计划");
        int count = plan.preApproveAllPending();
        emitPlan(thread, plan, false);
        result.put("success", true);
        result.put("preApprovedCount", count);
        return result;
    }

    // ── 内部工具方法 ──────────────────────────────────────────────────────────

    /**
     * 通过 {@link AiToolContext} ThreadLocal 解析当前线程（@Tool 方法专用）。
     */
    private AiThread resolveThread() {
        String sessionId = AiToolContext.getSessionId();
        String threadId  = AiToolContext.getThreadId();
        if (sessionId == null || sessionId.isBlank()) return null;
        PuppetNodeSession session = PuppetNodeSessionContainer.getSession(sessionId);
        if (session == null) return null;
        if (threadId != null && !threadId.isBlank()) {
            AiThread thread = session.getAiThread(threadId);
            if (thread != null) return thread;
        }
        return session.getActiveThread();
    }

    private AiThread resolveThreadByIds(String sessionId, String threadId) {
        if (sessionId == null || sessionId.isBlank()) return null;
        PuppetNodeSession session = PuppetNodeSessionContainer.getSession(sessionId);
        if (session == null) return null;
        if (threadId != null && !threadId.isBlank()) {
            AiThread thread = session.getAiThread(threadId);
            if (thread != null) return thread;
        }
        return session.getActiveThread();
    }

    /**
     * 发送计划事件。
     * {@code create=true} 时发送 {@code node} 事件（在任务树中创建计划节点）；
     * {@code create=false} 时发送 {@code patch} 事件（更新已有计划节点）。
     */
    private void emitPlan(AiThread thread, AiPlan plan, boolean create) {
        if (thread == null || plan == null) return;
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("kind", "plan");
        payload.put("planId", plan.getPlanId());
        payload.put("title", plan.getTitle());
        payload.put("goal", plan.getGoal());
        payload.put("status", plan.getStatus() != null ? plan.getStatus().name() : "PLANNING");
        payload.put("steps", plan.getSteps());
        if (plan.getFinalSummary() != null) payload.put("finalSummary", plan.getFinalSummary());
        thread.offerSseEvent(create ? "node" : "patch", payload);
    }

    private void emitPlanStep(AiThread thread, AiPlan plan, int stepIndex, String action, String text) {
        if (thread == null || plan == null) return;
        AiPlanStep step = plan.getSteps().stream()
                .filter(s -> s.getIndex() == stepIndex)
                .findFirst()
                .orElse(null);
        if (step == null) return;

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("kind", "plan");
        payload.put("planId", plan.getPlanId());
        payload.put("stepIndex", step.getIndex());
        payload.put("action", action);
        payload.put("status", step.getStatus() != null ? step.getStatus().name() : AiStepStatus.PENDING.name());
        payload.put("description", step.getDescription());
        payload.put("toolHint", step.getToolHint());
        payload.put("parallel", step.isParallel());
        payload.put("successCriteria", step.getSuccessCriteria());
        payload.put("result", step.getResult());
        payload.put("reason", step.getReason());
        payload.put("text", text);
        if (step.getStartedAt() > 0) {
            payload.put("startedAt", step.getStartedAt());
        }
        if (step.getCompletedAt() > 0) {
            payload.put("completedAt", step.getCompletedAt());
            if (step.getStartedAt() > 0) {
                payload.put("durationMs", step.getCompletedAt() - step.getStartedAt());
            }
        }
        payload.put("timestamp", System.currentTimeMillis());
        thread.offerSseEvent("patch", payload);
    }

    private List<AiPlanStep> normalizeSteps(List<Map<String, Object>> steps) {
        List<AiPlanStep> list = new ArrayList<>();
        if (steps == null || steps.isEmpty()) return list;
        for (int i = 0; i < steps.size(); i++) {
            Map<String, Object> raw = steps.get(i);
            if (raw == null) continue;
            String description = text(raw.get("description"));
            if (description.isBlank()) continue;
            String toolHint = text(raw.get("toolHint"));
            boolean parallel = bool(raw.get("parallel"));
            String successCriteria = text(raw.get("successCriteria"));
            int maxRetries = number(raw.get("maxRetries"), 1);
            List<Integer> dependsOn = numberList(raw.get("dependsOn"));
            int index = number(raw.get("index"), i);
            list.add(new AiPlanStep(index, description, toolHint, parallel, successCriteria, maxRetries, dependsOn));
        }
        return list;
    }

    private static Map<String, Object> fail(Map<String, Object> result, String message) {
        result.put("success", false);
        result.put("message", message);
        return result;
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static boolean bool(Object value) {
        return value instanceof Boolean b ? b : Boolean.parseBoolean(String.valueOf(value));
    }

    private static int number(Object value, int fallback) {
        if (value instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (Exception e) {
            return fallback;
        }
    }

    private static List<Integer> numberList(Object value) {
        List<Integer> result = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object item : list) {
                try {
                    result.add(Integer.parseInt(String.valueOf(item)));
                } catch (Exception ignored) {
                }
            }
        }
        return result;
    }
}
