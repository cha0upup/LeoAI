package org.leo.ai.service.skill;

import java.util.List;

/** 单个 skill 的描述符与健康检查结果。 */
public record SkillInspection(
        String scope,
        String name,
        boolean valid,
        SkillDescriptor descriptor,
        List<SkillValidationIssue> issues
) {
    public SkillInspection {
        issues = issues == null ? List.of() : List.copyOf(issues);
    }
}
