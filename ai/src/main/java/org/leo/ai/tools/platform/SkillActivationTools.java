package org.leo.ai.tools.platform;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.leo.ai.agent.AgentRuntimeResolver;
import org.leo.ai.agent.AiToolException;
import org.leo.ai.agent.PlatformSkillAccessPolicy;
import org.leo.ai.service.SkillDescriptor;
import org.leo.ai.service.SkillRegistryService;
import org.leo.core.ai.AiRuntimeState;

/**
 * Skill 激活工具：让 AI 在运行时按需读取 VFS 中指定 skill 的完整指令（SKILL.md 正文）。
 *
 * <p>遵循 langchain4j-skills / Agent Skills 规范，工具名统一为 {@code activate_skill}。
 *
 * <p>scope 在构造时由 {@link org.leo.ai.agent.AgentConfig} 硬编码注入，AI 无需也无法传入错误的 scope。
 * 通过 {@code @Bean} 分别创建两个实例注册到 PuppetNodeAgent 和 PlatformAgent。
 */
@org.leo.ai.agent.AiToolPolicy(
        kind = org.leo.ai.agent.AiToolKind.CONTEXT,
        operation = org.leo.ai.agent.AiToolOperation.READ_ONLY,
        parallelizable = true, business = false)
public class SkillActivationTools {

    private final SkillRegistryService skillRegistry;
    private final String scope;
    private final AgentRuntimeResolver runtimeResolver;

    public SkillActivationTools(SkillRegistryService skillRegistry, String scope,
                                AgentRuntimeResolver runtimeResolver) {
        this.skillRegistry = skillRegistry;
        this.scope         = scope;
        this.runtimeResolver = runtimeResolver;
    }

    @Tool(name = "activate_skill",
          value = "激活并读取指定 skill 的完整执行指令（SKILL.md 全文）。"
                + "执行任何 skill 前必须先调用此工具获取指令，不要凭记忆或推测执行 skill。")
    public String activateSkill(
            @P("skill 名称，如 recon-basic-info") String name) {

        if (name == null || name.isBlank()) {
            throw AiToolException.modelCorrectable(
                    "MISSING_REQUIRED_ARGUMENT",
                    "skill 名称 name 不能为空。",
                    "从当前系统提示提供的 Skill 列表中选择名称后重新调用。");
        }

        String normalizedName = name.trim();
        if (!SkillRegistryService.isValidSkillName(normalizedName)) {
            throw AiToolException.modelCorrectable(
                    "INVALID_SKILL_NAME",
                    "skill 名称格式非法：name=" + name + "。",
                    "只能使用当前 Skill 列表中的单个名称；不要传入路径、点目录或路径分隔符。");
        }

        String content = skillRegistry.getEnabledSkillContent(scope, normalizedName);
        if (content == null) {
            if (skillRegistry.getSkillContent(scope, normalizedName) != null) {
                throw AiToolException.modelCorrectable(
                        "SKILL_UNAVAILABLE",
                        "skill 已禁用或元数据无效，不能激活：name=" + normalizedName + "。",
                        "从当前系统提示列出的已启用 Skill 中重新选择；不要绕过禁用状态或猜测隐藏 Skill。");
            }
            throw AiToolException.modelCorrectable(
                    "SKILL_NOT_FOUND",
                    "未找到 skill：name=" + normalizedName + "。",
                    "检查当前可用 Skill 名称并注意大小写；不要猜测不存在的 Skill。");
        }
        SkillDescriptor descriptor = skillRegistry.getDescriptor(scope, normalizedName);
        if (descriptor == null) {
            throw AiToolException.modelCorrectable(
                    "SKILL_METADATA_INVALID",
                    "skill manifest 校验失败，不能激活：name=" + normalizedName + "。",
                    "停止执行该 skill，并让管理员通过 Skill 健康检查修复 manifest。");
        }
        AiRuntimeState runtime = runtimeResolver.resolveCurrent();
        if (SkillRegistryService.SCOPE_PLATFORM.equals(scope)
                && !PlatformSkillAccessPolicy.mayUse(runtime, descriptor.requiredTools())) {
            throw new SecurityException("当前身份无权激活该 skill: " + normalizedName);
        }
        if (runtime != null) runtime.activateSkill(normalizedName);

        String policy = """
                <skill_execution_policy>
                id: %s
                version: %s
                category: %s
                mode: %s
                risk: %s
                accessMode: %s
                requiresExplicitApproval: %s
                requiredTools: %s
                requiredSkills: %s
                requiredFacts: %s
                说明：启用表示能力可被发现，不代表本次任务已获授权。所有操作仍须遵守当前身份权限、ROE、目标范围和工具执行边界。
                </skill_execution_policy>

                """.formatted(
                descriptor.id(), descriptor.version(), descriptor.category(), descriptor.mode(),
                descriptor.risk(), descriptor.accessMode(), descriptor.requiresExplicitApproval(),
                descriptor.requiredTools(), descriptor.requiredSkills(),
                descriptor.requiredFacts());
        return policy + SkillRegistryService.stripFrontmatter(content);
    }
}
