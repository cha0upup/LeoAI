package org.leo.ai.tools.common;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.leo.ai.agent.AiRuntimeResolver;
import org.leo.ai.agent.AiToolException;
import org.leo.ai.agent.AiToolKind;
import org.leo.ai.agent.AiToolOperation;
import org.leo.ai.agent.AiToolPolicy;
import org.leo.ai.service.AiPlanCoordinator;
import org.leo.ai.service.AiPlanStepInput;
import org.leo.core.ai.AiRuntimeState;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Platform AI 与 Node AI 共用的计划控制工具。 */
@Component
@AiToolPolicy(
        kind = AiToolKind.CONTROL,
        operation = AiToolOperation.WRITE,
        exclusive = true, business = false)
public class PlanTools {

    private final AiRuntimeResolver runtimeResolver;
    private final AiPlanCoordinator coordinator;

    public PlanTools(AiRuntimeResolver runtimeResolver,
                     AiPlanCoordinator coordinator) {
        this.runtimeResolver = runtimeResolver;
        this.coordinator = coordinator;
    }

    @Tool("创建当前任务的结构化执行计划。仅定义步骤；实际状态由服务端根据执行结果推进。")
    public Map<String, Object> createPlan(
            @P("计划标题") String title,
            @P("任务目标") String goal,
            @P("步骤对象列表：description、toolHint、parallel、successCriteria、maxRetries、dependsOn")
            List<AiPlanStepInput> steps,
            @P(value = "单步骤超时毫秒；0 表示不启用", required = false,
                    defaultValue = "0") long stepTimeoutMs) {
        return success(coordinator.create(requireRuntime(), title, goal, steps, stepTimeoutMs));
    }

    @Tool("更新计划步骤。action：start、complete、fail、skip。")
    public Map<String, Object> updatePlanStep(
            @P("步骤序号，从 0 开始") int stepIndex,
            @P("start | complete | fail | skip") String action,
            @P(value = "结果证据或失败/跳过原因；start 时可省略", required = false)
            String resultText) {
        return success(coordinator.update(requireRuntime(), stepIndex, action, resultText));
    }

    @Tool("结束当前计划并记录最终结论。")
    public Map<String, Object> completePlan(@P("最终结论") String finalSummary) {
        return success(coordinator.complete(requireRuntime(), finalSummary));
    }

    private AiRuntimeState requireRuntime() {
        AiRuntimeState runtime = runtimeResolver.resolveCurrent();
        if (runtime == null) {
            throw AiToolException.userActionRequired(
                    "SESSION_OR_THREAD_UNAVAILABLE",
                    "当前 Agent 运行时不存在，无法操作计划。",
                    "停止调用并提示用户重新打开或创建 AI 会话。");
        }
        return runtime;
    }

    private static Map<String, Object> success(Object plan) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("plan", plan);
        return result;
    }
}
