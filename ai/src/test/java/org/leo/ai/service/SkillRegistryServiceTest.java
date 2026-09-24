package org.leo.ai.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.leo.core.config.LeoConfig;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillRegistryServiceTest {

    @TempDir
    Path tempDir;

    private SkillRegistryService registry;
    private String previousVfsPath;

    @BeforeEach
    void setUp() {
        previousVfsPath = LeoConfig.getVfsPath();
        ReflectionTestUtils.setField(LeoConfig.class, "VFS_PATH", tempDir.toString());
        registry = new SkillRegistryService(new SkillManifestService());
    }

    @AfterEach
    void tearDown() {
        ReflectionTestUtils.setField(LeoConfig.class, "VFS_PATH", previousVfsPath);
    }

    @Test
    void enabledContentIsReadableButDisabledContentCannotBeActivated() throws Exception {
        writeSkill("puppet-node", "enabled-skill", true, "enabled body");
        writeSkill("puppet-node", "disabled-skill", false, "disabled body");

        assertTrue(registry.isSkillEnabled("puppet-node", "enabled-skill"));
        assertFalse(registry.isSkillEnabled("puppet-node", "disabled-skill"));
        assertEquals("enabled body\n", SkillRegistryService.stripFrontmatter(
                registry.getEnabledSkillContent("puppet-node", "enabled-skill")));
        assertNull(registry.getEnabledSkillContent("puppet-node", "disabled-skill"));
        assertTrue(registry.getSkillContent("puppet-node", "disabled-skill")
                .contains("disabled body"));
    }

    @Test
    void rejectsPathTraversalAndNestedNames() throws Exception {
        writeSkill("platform", "platform-only", true, "platform body");

        assertFalse(SkillRegistryService.isValidSkillName("../platform/platform-only"));
        assertFalse(SkillRegistryService.isValidSkillName("nested/skill"));
        assertFalse(SkillRegistryService.isValidSkillName("."));
        assertNull(registry.getSkillContent("puppet-node", "../platform/platform-only"));
        assertNull(registry.getSkillContent("puppet-node", "nested/skill"));
    }

    @Test
    void activationReadsCurrentEnabledFlagWithoutWaitingForMetadataCache() throws Exception {
        writeSkill("puppet-node", "mutable-skill", true, "first body");
        assertTrue(registry.isSkillEnabled("puppet-node", "mutable-skill"));

        writeSkill("puppet-node", "mutable-skill", false, "second body");

        assertFalse(registry.isSkillEnabled("puppet-node", "mutable-skill"));
        assertNull(registry.getEnabledSkillContent("puppet-node", "mutable-skill"));
    }

    @Test
    void activationFailsClosedForInvalidOrMismatchedFrontmatter() throws Exception {
        Path skillDir = tempDir.resolve("skills/puppet-node/mismatched-skill");
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"), """
                ---
                name: another-skill
                description: mismatch
                enabled: true
                ---
                body
                """);
        Files.writeString(skillDir.resolve("manifest.yaml"), manifest(
                "mismatched-skill", "puppet-node", true));

        assertFalse(registry.isSkillEnabled("puppet-node", "mismatched-skill"));
        assertNull(registry.getEnabledSkillContent("puppet-node", "mismatched-skill"));
    }

    @Test
    void activationFailsClosedForCatalogLevelErrors() throws Exception {
        writeSkill("puppet-node", "first-skill", true, "first body");
        writeSkill("puppet-node", "second-skill", true, "second body");
        Path secondManifest = tempDir.resolve("skills/puppet-node/second-skill/manifest.yaml");
        Files.writeString(secondManifest, Files.readString(secondManifest)
                .replace("leo.test.second-skill", "leo.test.first-skill"));

        registry.invalidate();

        assertNull(registry.getEnabledSkillContent("puppet-node", "first-skill"));
        assertNull(registry.getEnabledSkillContent("puppet-node", "second-skill"));
        assertNull(registry.getDescriptor("puppet-node", "first-skill"));
    }

    private void writeSkill(String scope, String name, boolean enabled, String body) throws Exception {
        Path skillDir = tempDir.resolve("skills").resolve(scope).resolve(name);
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"), """
                ---
                name: %s
                description: test skill
                ---

                %s
                """.formatted(name, body));
        Files.writeString(skillDir.resolve("manifest.yaml"), manifest(name, scope, enabled));
    }

    private String manifest(String name, String scope, boolean enabled) {
        return """
                schemaVersion: 1
                id: leo.test.%s
                name: %s
                version: 1.0.0
                scope: %s
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
                """.formatted(name, name, scope, enabled);
    }
}
