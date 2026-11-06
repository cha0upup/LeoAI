package org.leo.ai.service.skill;

import java.util.List;

/**
 * 通过严格校验后的 Skill 描述符。
 *
 * <p>{@code SKILL.md} 只负责模型触发信息和执行指令；本对象中的字段全部来自
 * {@code manifest.yaml}，供目录治理、检索、风险提示和后续执行策略使用。
 */
public record SkillDescriptor(
        int schemaVersion,
        String id,
        String name,
        String version,
        String scope,
        String description,
        String domain,
        String category,
        String mode,
        List<String> tactics,
        List<String> techniques,
        List<String> platforms,
        List<String> targets,
        String pack,
        String risk,
        String accessMode,
        String status,
        String source,
        String owner,
        boolean enabled,
        List<String> requiredTools,
        List<String> requiredSkills,
        List<String> requiredFacts,
        List<String> produces,
        List<String> next
) {
    public SkillDescriptor {
        tactics = immutable(tactics);
        techniques = immutable(techniques);
        platforms = immutable(platforms);
        targets = immutable(targets);
        requiredTools = immutable(requiredTools);
        requiredSkills = immutable(requiredSkills);
        requiredFacts = immutable(requiredFacts);
        produces = immutable(produces);
        next = immutable(next);
    }

    public boolean isPublishedAndEnabled() {
        return enabled && "published".equals(status);
    }

    public boolean requiresExplicitApproval() {
        return "high".equals(risk)
                || "critical".equals(risk)
                || "active-login".equals(accessMode)
                || "write".equals(accessMode)
                || "write-destructive".equals(accessMode)
                || "destructive".equals(accessMode);
    }

    private static List<String> immutable(List<String> values) {
        return values == null ? List.of() : List.copyOf(values);
    }
}
