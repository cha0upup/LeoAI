package org.leo.ai.agent;

import dev.langchain4j.skills.Skills;
import org.leo.ai.service.LeoSkillsProvider;
import org.leo.ai.service.SkillRegistryService;
import org.springframework.stereotype.Component;

/**
 * Platform Agent 的动态 System Prompt 提供者。
 *
 * <p>通过 {@code AgentConfig} 中的 {@code .systemMessageProvider(this::getSystemMessage)}
 * 以方法引用形式注册到 AiServices。
 *
 * <p>Skills 列表通过 {@link LeoSkillsProvider#getSkills(String)} 动态读取，
 * 并使用 {@link Skills#formatAvailableSkills()} 标准格式化，符合 Agent Skills 规范。
 */
@Component
public class PlatformSystemPromptProvider {

    private final LeoSkillsProvider skillsProvider;

    public PlatformSystemPromptProvider(LeoSkillsProvider skillsProvider) {
        this.skillsProvider = skillsProvider;
    }

    public String getSystemMessage(Object memoryId) {
        return HEADER + buildSkillsSection() + FOOTER;
    }

    // ── 静态部分 ──────────────────────────────────────────────────────────────

    private static final String HEADER = """
            你是一名专业的 WebShell 管理平台AI，服务对象是渗透测试工程师或安全研究人员。

            你的职责是管理平台侧资源，包括用户、团队、Puppet、Disguise、插件和指纹。

            ════════════════════════════════════════
            【核心原则】
            ════════════════════════════════════════

            1. 直接调用工具完成任务，不把"我将执行"当成已经完成。
            2. 每次回答区分事实、推断和下一步建议。
            3. 涉及新增、修改、删除前，先确认目标是否存在，避免误操作。
            4. 对相互独立的工具调用优先并发执行；存在前后依赖的操作保持串行。

            ReAct 循环：
            - THINK：先在脑中快速判断当前信息缺口和下一步。
            - TOOL：只有在真实需要时才发出工具调用，独立读操作可并发。
            - OBSERVE：工具返回后只根据真实结果继续判断，不要提前编造结论。
            - ANALYZE：用简短自然语言概括刚得到的事实、异常点和影响。
            - NEXT_ACTION：立即决定下一轮工具或结束条件，继续推进直到任务完成。

            只在真实状态变化时输出简短过渡语，不要使用固定模板。

            执行过程由系统根据模型原生流式思考和工具调用自动展示，不要输出 XML/JSON 过程标记。

            """;

    private static final String FOOTER = """

            遇到对应场景时主动套用相应 skill 的工作流程，执行前先调用 activate_skill 获取完整指令，不要凭空生成方案。

            ════════════════════════════════════════
            【最终输出格式】
            ════════════════════════════════════════

            **结果**：直接说明操作结果（成功/失败/部分完成）。
            **关键对象**：涉及的资源标识（userId、teamId、puppetId 等）。
            **已执行**：简要说明实际执行了哪些步骤。
            **下一步建议**：1~2 条具体的后续建议。
            """;

    // ── 动态部分 ──────────────────────────────────────────────────────────────

    private String buildSkillsSection() {
        Skills skills = skillsProvider.getSkills(SkillRegistryService.SCOPE_PLATFORM);
        StringBuilder sb = new StringBuilder();
        sb.append("════════════════════════════════════════\n");
        sb.append("【可用 Skills】\n");
        sb.append("════════════════════════════════════════\n\n");
        String formatted = skills.formatAvailableSkills();
        if (formatted == null || formatted.isBlank()) {
            sb.append("（当前暂无可用 skill）\n");
        } else {
            sb.append(formatted).append("\n");
        }
        return sb.toString();
    }
}
