package org.leo.ai.tools.platform;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.leo.ai.service.SkillRegistryService;

/**
 * Skill 激活工具：让 AI 在运行时按需读取 VFS 中指定 skill 的完整指令（SKILL.md 正文）。
 *
 * <p>遵循 langchain4j-skills / Agent Skills 规范，工具名统一为 {@code activate_skill}。
 *
 * <p>scope 在构造时由 {@link org.leo.ai.agent.AgentConfig} 硬编码注入，AI 无需也无法传入错误的 scope。
 * 通过 {@code @Bean} 分别创建两个实例注册到 PuppetNodeAgent 和 PlatformAgent。
 */
public class SkillActivationTools {

    private final SkillRegistryService skillRegistry;
    private final String scope;

    public SkillActivationTools(SkillRegistryService skillRegistry, String scope) {
        this.skillRegistry = skillRegistry;
        this.scope         = scope;
    }

    @Tool(name = "activate_skill",
          value = "激活并读取指定 skill 的完整执行指令（SKILL.md 全文）。"
                + "执行任何 skill 前必须先调用此工具获取指令，不要凭记忆或推测执行 skill。")
    public String activateSkill(
            @P("skill 名称，如 recon-basic-info") String name) {

        if (name == null || name.isBlank())
            throw new IllegalArgumentException("name 不能为空");

        String content = skillRegistry.getSkillContent(scope, name.trim());
        if (content == null) {
            throw new IllegalStateException("未找到 skill：name=" + name + "。请确认名称是否正确（区分大小写）。");
        }
        // 剥掉 frontmatter（enabled / description / tags 等元数据），只把正文交给 AI
        return SkillRegistryService.stripFrontmatter(content);
    }
}
