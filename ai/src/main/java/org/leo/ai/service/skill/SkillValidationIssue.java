package org.leo.ai.service.skill;

/** Skill manifest 或 SKILL.md 元数据的校验问题。 */
public record SkillValidationIssue(Severity severity, String field, String message) {
    public enum Severity {
        ERROR,
        WARNING
    }

    public static SkillValidationIssue error(String field, String message) {
        return new SkillValidationIssue(Severity.ERROR, field, message);
    }

    public static SkillValidationIssue warning(String field, String message) {
        return new SkillValidationIssue(Severity.WARNING, field, message);
    }
}
