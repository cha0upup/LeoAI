package org.leo.ai.service.skill;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillManifestServiceTest {

    private final SkillManifestService service = new SkillManifestService();

    @Test
    void rejectsManagementFieldsInSkillFrontmatter() {
        SkillInspection inspection = service.inspect(
                "agent-node", "sample-skill", """
                        ---
                        name: sample-skill
                        description: sample
                        enabled: true
                        ---
                        body
                        """, validManifest(true));

        assertFalse(inspection.valid());
        assertTrue(inspection.issues().stream()
                .anyMatch(issue -> issue.field().equals("SKILL.md.frontmatter.enabled")));
    }

    @Test
    void rejectsDirectoryNamesOutsideSkillNamingConvention() {
        SkillInspection inspection = service.inspect(
                "agent-node", "Bad_Skill",
                """
                        ---
                        name: Bad_Skill
                        description: test
                        ---
                        body
                        """,
                validManifest(false)
                        .replace("leo.test.sample-skill", "leo.test.bad-skill")
                        .replace("name: sample-skill", "name: Bad_Skill"));

        assertFalse(inspection.valid());
        assertTrue(inspection.issues().stream()
                .anyMatch(issue -> issue.field().equals("skill.name")));
    }

    @Test
    void draftCannotBeEnabled() {
        SkillInspection inspection = service.inspect(
                "agent-node", "sample-skill", skillContent(),
                validManifest(true).replace("status: published", "status: draft"));

        assertFalse(inspection.valid());
        assertTrue(inspection.issues().stream()
                .anyMatch(issue -> issue.field().equals("manifest.enabled")));
    }

    private String skillContent() {
        return """
                ---
                name: sample-skill
                description: sample
                ---
                body
                """;
    }

    private String validManifest(boolean enabled) {
        return """
                schemaVersion: 1
                id: leo.test.sample-skill
                name: sample-skill
                version: 1.0.0
                scope: agent-node
                domain: operation
                category: discovery
                mode: assess
                platforms: [linux]
                targets: [host]
                pack: test
                risk: low
                accessMode: read-only
                status: published
                source: custom
                owner: test
                enabled: %s
                """.formatted(enabled);
    }
}
