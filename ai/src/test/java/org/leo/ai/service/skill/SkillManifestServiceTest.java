package org.leo.ai.service.skill;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillManifestServiceTest {

    private final SkillManifestService service = new SkillManifestService();

    @ParameterizedTest
    @ValueSource(strings = {"\n", "\r\n"})
    void validationAndBodyExtractionUseTheSameFrontmatterBoundary(String newline) {
        String content = String.join(newline, "---", "name: sample-skill", "description: sample",
                "  --- ", "   ", "# Body", "---", "More text", "");

        SkillInspection inspection = service.inspect(
                "agent-node", "sample-skill", content, validManifest(true));

        assertTrue(inspection.valid(), () -> inspection.issues().toString());
        assertEquals(String.join(newline, "# Body", "---", "More text", ""),
                SkillRegistryService.stripFrontmatter(content));
    }

    @Test
    void preservesTheBodyAndValidationIssuesWhenFrontmatterIsMissingOrUnclosed() {
        String plain = "# Body\n";
        String unclosed = "---\nname: sample-skill\n";
        SkillInspection missing = service.inspect("agent-node", "sample-skill", plain, validManifest(true));
        SkillInspection incomplete = service.inspect("agent-node", "sample-skill", unclosed, validManifest(true));

        assertTrue(missing.issues().contains(
                SkillValidationIssue.error("SKILL.md.frontmatter", "缺少 YAML frontmatter")));
        assertTrue(incomplete.issues().contains(
                SkillValidationIssue.error("SKILL.md.frontmatter", "缺少闭合分隔符 ---")));
        assertEquals(plain, SkillRegistryService.stripFrontmatter(plain));
        assertEquals(unclosed, SkillRegistryService.stripFrontmatter(unclosed));
        assertNull(SkillRegistryService.stripFrontmatter(null));
    }

    @Test
    void returnsAnEmptyBodyAfterAClosedHeaderWithNoContent() {
        assertEquals("", SkillRegistryService.stripFrontmatter("---\nname: sample-skill\n---"));
        assertEquals("", SkillRegistryService.stripFrontmatter("---\n---\n  \n"));
    }

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
