package org.leo.ai.service;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BuiltinAgentNodeSkillsTest {

    private static final List<String> SKILL_NAMES = List.of(
            "recon-basic-info",
            "analyze-browser-artifacts",
            "hunt-credentials",
            "escalate-linux-privilege",
            "lateral-move-ssh",
            "persistence-linux"
    );

    private static final List<String> FORBIDDEN_CONTENT = List.of(
            "sshpass -p",
            "StrictHostKeyChecking=no",
            "privateKeyBody",
            "bash -i >& /dev/tcp",
            "chmod u+s",
            "ProcessTools",
            "NetworkInfoTools",
            "SuidCapabilityTools",
            "recon-internal-network",
            "exploit-database-post",
            "collect-cloud-metadata"
    );

    private static final List<String> RED_TEAM_SECTIONS = List.of(
            "## 行动目标",
            "## 授权与 ROE",
            "## OPSEC",
            "## 成功与停止条件"
    );

    @Test
    void bundledSkillsHaveValidMetadataAndGuardrails() throws Exception {
        Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
        for (String skillName : SKILL_NAMES) {
            String content = readSkill(skillName);
            Map<String, Object> frontmatter = parseFrontmatter(content, yaml);
            String manifestContent = readManifest(skillName);
            Map<String, Object> manifest = parseYaml(manifestContent, yaml);
            SkillInspection inspection = new SkillManifestService().inspect(
                    "agent-node", skillName, content, manifestContent);

            assertEquals(skillName, frontmatter.get("name"));
            assertTrue(frontmatter.get("description") instanceof String description
                    && !description.isBlank());
            assertEquals(2, frontmatter.size(),
                    () -> skillName + " SKILL.md frontmatter must only contain name/description");
            assertTrue(inspection.valid(),
                    () -> skillName + " manifest invalid: " + inspection.issues());
            assertEquals("operation", manifest.get("domain"));
            assertEquals("builtin", manifest.get("source"));
            assertTrue(manifest.get("platforms") instanceof List<?> platforms
                            && !platforms.isEmpty(),
                    () -> skillName + " must declare platforms");
            assertTrue(content.lines().count() < 200,
                    () -> skillName + " should remain concise");
            for (String section : RED_TEAM_SECTIONS) {
                assertTrue(content.contains(section),
                        () -> skillName + " is missing red-team section: " + section);
            }
            assertTrue(content.contains("摘要") || content.contains("变更台账"),
                    () -> skillName + " must define handoff state");
            for (String forbidden : FORBIDDEN_CONTENT) {
                assertFalse(content.contains(forbidden),
                        () -> skillName + " contains forbidden content: " + forbidden);
            }
        }
    }

    @Test
    void destructivePersistenceSkillIsDisabledByDefault() throws Exception {
        Map<String, Object> manifest = parseYaml(
                readManifest("persistence-linux"),
                new Yaml(new SafeConstructor(new LoaderOptions())));

        assertEquals(Boolean.FALSE, manifest.get("enabled"));
        assertEquals("high", manifest.get("risk"));
        assertEquals("write-destructive", manifest.get("accessMode"));
    }

    private String readSkill(String name) throws Exception {
        String resource = "skills/agent-node/" + name + "/SKILL.md";
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(in, () -> "missing bundled skill: " + resource);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private String readManifest(String name) throws Exception {
        String resource = "skills/agent-node/" + name + "/manifest.yaml";
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(in, () -> "missing bundled manifest: " + resource);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseFrontmatter(String content, Yaml yaml) {
        String[] parts = content.split("(?m)^---\\s*$", 3);
        assertEquals(3, parts.length, "frontmatter must have opening and closing delimiters");
        Object parsed = yaml.load(parts[1]);
        assertTrue(parsed instanceof Map<?, ?>, "frontmatter must be a YAML mapping");
        return (Map<String, Object>) parsed;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseYaml(String content, Yaml yaml) {
        Object parsed = yaml.load(content);
        assertTrue(parsed instanceof Map<?, ?>, "manifest must be a YAML mapping");
        return (Map<String, Object>) parsed;
    }
}
