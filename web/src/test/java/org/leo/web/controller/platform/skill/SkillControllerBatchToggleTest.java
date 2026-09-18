package org.leo.web.controller.platform.skill;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.leo.ai.service.LeoSkillsProvider;
import org.leo.ai.service.SkillExportService;
import org.leo.ai.service.SkillFileService;
import org.leo.ai.service.SkillInspection;
import org.leo.ai.service.SkillManifestService;
import org.leo.ai.service.SkillRegistryService;
import org.leo.core.config.LeoConfig;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class SkillControllerBatchToggleTest {

    @TempDir
    Path tempDir;

    private String previousVfsPath;
    private SkillRegistryService registry;
    private SkillManifestService manifestService;
    private LeoSkillsProvider provider;
    private SkillController controller;

    @BeforeEach
    void setUp() {
        previousVfsPath = LeoConfig.getVfsPath();
        ReflectionTestUtils.setField(LeoConfig.class, "VFS_PATH", tempDir.toString());
        manifestService = new SkillManifestService();
        registry = spy(new SkillRegistryService(manifestService));
        provider = spy(new LeoSkillsProvider(registry));
        controller = new SkillController(registry, provider, new SkillFileService(),
                new SkillExportService(manifestService), manifestService);
    }

    @AfterEach
    void tearDown() {
        ReflectionTestUtils.setField(LeoConfig.class, "VFS_PATH", previousVfsPath);
    }

    @Test
    void batchEnableReturnsPerItemResultsWithoutBypassingValidation() throws Exception {
        writeSkill("published-skill", "published", false, false);
        writeSkill("draft-skill", "draft", false, false);

        HashMap<String, Object> response = controller.toggleBatch(new HashMap<>(Map.of(
                "scope", "puppet-node",
                "names", List.of("published-skill", "draft-skill", "missing-skill"),
                "enabled", true)));

        assertEquals(200, response.get("code"));
        Map<?, ?> data = (Map<?, ?>) response.get("data");
        assertEquals(3, data.get("requested"));
        assertEquals(1, data.get("changed"));
        assertEquals(0, data.get("unchanged"));
        assertEquals(2, data.get("failed"));
        assertTrue(registry.isSkillEnabled("puppet-node", "published-skill"));
        assertFalse(registry.isSkillEnabled("puppet-node", "draft-skill"));
    }

    @Test
    void singleToggleRejectsInvalidScopeAsBadRequest() {
        HashMap<String, Object> response = controller.toggle(new HashMap<>(Map.of(
                "scope", "invalid-scope",
                "name", "valid-name",
                "enabled", true)));

        assertEquals(400, response.get("code"));
    }

    @Test
    void singleToggleInvalidatesProviderIndex() throws Exception {
        writeSkill("toggle-skill", "published", false, false);
        LeoSkillsProvider provider = new LeoSkillsProvider(registry);
        controller = new SkillController(registry, provider, new SkillFileService(),
                new SkillExportService(manifestService), manifestService);

        assertFalse(provider.getFormattedSkills("puppet-node", null).contains("toggle-skill"));
        HashMap<String, Object> response = controller.toggle(new HashMap<>(Map.of(
                "scope", "puppet-node",
                "name", "toggle-skill",
                "enabled", true)));

        assertEquals(200, response.get("code"));
        assertTrue(provider.getFormattedSkills("puppet-node", null).contains("toggle-skill"));
    }

    @Test
    void batchDisableCanFailClosedAnOtherwiseInvalidSkill() throws Exception {
        writeSkill("invalid-skill", "published", true, true);

        HashMap<String, Object> response = controller.toggleBatch(new HashMap<>(Map.of(
                "scope", "puppet-node",
                "names", List.of("invalid-skill"),
                "enabled", false)));

        assertEquals(200, response.get("code"));
        Map<?, ?> data = (Map<?, ?>) response.get("data");
        assertEquals(1, data.get("changed"));
        Path skillDir = tempDir.resolve("skills/puppet-node/invalid-skill");
        SkillInspection inspection = manifestService.inspect(skillDir, "puppet-node");
        assertFalse(inspection.valid());
        assertFalse(inspection.descriptor().enabled());
    }

    @Test
    void batchDeleteReportsPartialSuccessPerItem() throws Exception {
        writeSkill("delete-me", "published", false, false);

        HashMap<String, Object> response = controller.deleteBatch(new HashMap<>(Map.of(
                "scope", "puppet-node",
                "names", List.of("delete-me", "missing-skill"))));

        assertEquals(200, response.get("code"));
        Map<?, ?> data = (Map<?, ?>) response.get("data");
        assertEquals(2, data.get("requested"));
        assertEquals(1, data.get("deleted"));
        assertEquals(1, data.get("changed"));
        assertEquals(1, data.get("failed"));
        assertFalse(Files.exists(tempDir.resolve("skills/puppet-node/delete-me")));
    }

    @Test
    void batchDeleteDeduplicatesNamesAndInvalidatesBothCachesOnce() throws Exception {
        writeSkill("delete-one", "published", true, false);
        writeSkill("delete-two", "published", true, false);
        assertTrue(provider.getFormattedSkills("puppet-node", null).contains("delete-one"));
        clearInvocations(registry, provider);

        HashMap<String, Object> response = controller.deleteBatch(new HashMap<>(Map.of(
                "scope", " puppet-node ",
                "names", List.of(" delete-one ", "delete-one", "delete-two", "missing-skill"))));

        assertEquals(200, response.get("code"));
        Map<?, ?> data = (Map<?, ?>) response.get("data");
        assertEquals("puppet-node", data.get("scope"));
        assertEquals(3, data.get("requested"));
        assertEquals(2, data.get("deleted"));
        assertEquals(2, data.get("changed"));
        assertEquals(0, data.get("unchanged"));
        assertEquals(1, data.get("failed"));
        List<?> results = (List<?>) data.get("results");
        assertEquals(Map.of("name", "delete-one", "status", "deleted", "message", "Skill 已删除"), results.get(0));
        assertEquals("failed", ((Map<?, ?>) results.get(2)).get("status"));
        verify(registry, times(1)).invalidate();
        verify(provider, times(1)).invalidate();
        assertFalse(provider.getFormattedSkills("puppet-node", null).contains("delete-one"));
        assertTrue(registry.listAllSkills("puppet-node").isEmpty());
    }

    @Test
    void batchDeleteValidatesEntireRequestBeforeDeletingAnySkill() throws Exception {
        writeSkill("keep-me", "published", true, false);

        for (List<?> names : List.of(List.of("keep-me", "../outside"), List.of("keep-me", 1))) {
            HashMap<String, Object> response = controller.deleteBatch(new HashMap<>(Map.of(
                    "scope", "puppet-node", "names", names)));
            assertEquals(400, response.get("code"));
            assertTrue(Files.exists(tempDir.resolve("skills/puppet-node/keep-me")));
        }
        HashMap<String, Object> invalidScope = controller.deleteBatch(new HashMap<>(Map.of(
                "scope", "invalid-scope", "names", List.of("keep-me"))));
        assertEquals(400, invalidScope.get("code"));
        assertTrue(Files.exists(tempDir.resolve("skills/puppet-node/keep-me")));
    }

    @Test
    void singleDeleteInvalidatesBothCachesAndKeepsMissingSkillResponse() throws Exception {
        writeSkill("delete-me", "published", true, false);
        assertTrue(provider.getFormattedSkills("puppet-node", null).contains("delete-me"));
        clearInvocations(registry, provider);
        HashMap<String, Object> params = new HashMap<>(Map.of("scope", "puppet-node", "name", "delete-me"));

        assertEquals(200, controller.delete(params).get("code"));
        assertEquals(404, controller.delete(params).get("code"));
        verify(registry, times(1)).invalidate();
        verify(provider, times(1)).invalidate();
        assertFalse(provider.getFormattedSkills("puppet-node", null).contains("delete-me"));
    }

    @Test
    void healthSeparatesWarningsFromErrorsAndHealthySkills() throws Exception {
        writeSkill("healthy-skill", "published", true, false);
        writeSkill("risky-skill", "published", true, false);
        Path riskyManifest = tempDir.resolve("skills/puppet-node/risky-skill/manifest.yaml");
        Files.writeString(riskyManifest,
                Files.readString(riskyManifest).replace("risk: low", "risk: high"));

        HashMap<String, Object> response = controller.health("puppet-node");

        assertEquals(200, response.get("code"));
        Map<?, ?> data = (Map<?, ?>) response.get("data");
        assertEquals(2L, data.get("valid"));
        assertEquals(0L, data.get("invalid"));
        assertEquals(1L, data.get("warning"));
        assertEquals(1L, data.get("healthy"));
    }

    private void writeSkill(String name, String status, boolean enabled,
                            boolean addUnknownField) throws Exception {
        Path skillDir = tempDir.resolve("skills/puppet-node").resolve(name);
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"), """
                ---
                name: %s
                description: test skill
                ---

                body
                """.formatted(name));
        String manifest = """
                schemaVersion: 1
                id: leo.test.%s
                name: %s
                version: 1.0.0
                scope: puppet-node
                domain: operation
                category: discovery
                mode: assess
                platforms: [linux]
                targets: [host]
                risk: low
                accessMode: read-only
                status: %s
                source: custom
                owner: test
                enabled: %s
                """.formatted(name, name, status, enabled);
        if (addUnknownField) manifest += "unknownField: true\n";
        Files.writeString(skillDir.resolve("manifest.yaml"), manifest);
    }
}
