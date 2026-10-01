package org.leo.ai.tools.common;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.leo.ai.agent.AiToolKind;
import org.leo.ai.agent.AiToolOperation;
import org.leo.ai.agent.AiToolPolicy;
import org.leo.ai.service.AiUserInputService;
import org.leo.core.entity.AiUserInputOption;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/** Platform AI 与 Node AI 共用的结构化提问工具。 */
@Component
@AiToolPolicy(
        kind = AiToolKind.CONTROL,
        operation = AiToolOperation.WRITE,
        terminal = true, exclusive = true, business = false)
public class UserInputTools {

    private final AiUserInputService service;

    public UserInputTools(AiUserInputService service) {
        this.service = service;
    }

    @Tool(name = "request_user_input", value = """
            当缺少会显著改变结果的用户意图，或执行高风险动作前需要明确确认时，
            创建结构化问题并暂停原任务。调用后不得继续调用其他工具，本轮应立即结束。
            问题卡片会由系统自动展示；调用成功后不要复述问题、选项、问题 ID、有效期，
            不要再输出“已发送卡片”“等待回答”等自然语言。
            能通过只读工具查明的信息、低风险且可逆的常规选择不要询问。
            CLARIFICATION 问题始终提供自定义输入框，即使同时提供了结构化选项；
            CONFIRMATION 只能使用明确的同意/拒绝选项，不提供自由输入。
            CONFIRMATION 必须传入准确的 toolName 和完整 argumentsJson，参数改变后重新确认。
            """)
    public Map<String, Object> requestUserInput(
            @P(value = "CLARIFICATION 或 CONFIRMATION；默认 CLARIFICATION",
                    required = false, defaultValue = "CLARIFICATION") String type,
            @P("一个具体、可直接回答的问题") String prompt,
            @P(value = "最多 4 个选项对象，每个对象包含 label（展示文本）、value（稳定提交值）和 intent（业务语义）；只有允许自由输入时可省略",
                    required = false) List<AiUserInputOption> options,
            @P(value = "是否允许在问题卡片内自定义输入；CLARIFICATION 始终启用，CONFIRMATION 始终禁用",
                    required = false, defaultValue = "false") Boolean allowFreeText,
            @P(value = "确认摘要；CONFIRMATION 必须包含操作、风险、可能后果和回滚方式，普通澄清可省略",
                    required = false) String actionSummary,
            @P(value = "确认后计划调用的准确工具名；普通澄清可省略", required = false) String toolName,
            @P(value = "确认后计划调用的完整参数 JSON；普通澄清可省略", required = false) String argumentsJson,
            @P(value = "LOW、MEDIUM、HIGH 或 CRITICAL；省略时按问题类型推导", required = false) String risk,
            @P(value = "有效期秒数；省略或 0 使用默认 24 小时",
                    required = false, defaultValue = "0") Long expiresInSeconds) {
        return service.request(type, prompt, options, allowFreeText,
                actionSummary, toolName, argumentsJson, risk, expiresInSeconds);
    }
}
