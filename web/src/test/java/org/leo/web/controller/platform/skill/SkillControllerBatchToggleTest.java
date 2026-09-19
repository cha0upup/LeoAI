package org.leo.web.controller.platform.skill;

import org.junit.jupiter.api.Test;
import org.leo.ai.service.SkillInspection;

import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;

class SkillControllerBatchToggleTest extends SkillControllerTestSupport {

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
    void batchDeleteDeduplicatesNamesAndInvalidatesBothCachesOnce() throws Exception {
        writeSkill("delete-one", "published", true, false);
        writeSkill("delete-two", "published", false, false);
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
        assertFalse(Files.exists(skillDir("delete-one")));
        assertFalse(Files.exists(skillDir("delete-two")));
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

    @Test
    void batchToggleValidatesEntireRequestBeforeWriting() throws Exception {
        writeSkill("keep-disabled", "published", false, false);
        List<List<?>> invalidNames = List.of(List.of("keep-disabled", "../outside"),
                List.of("keep-disabled", 1), List.of("keep-disabled", ""), List.of());
        for (List<?> names : invalidNames) {
            assertThrows(IllegalArgumentException.class, () -> management.toggleBatch("puppet-node", names, true));
            assertEquals(400, controller.toggleBatch(new HashMap<>(Map.of(
                    "scope", "puppet-node", "names", names, "enabled", true))).get("code"));
        }
        assertEquals(400, controller.toggleBatch(new HashMap<>(Map.of(
                "scope", "unknown", "names", List.of("keep-disabled"), "enabled", true))).get("code"));
        assertFalse(registry.isSkillEnabled("puppet-node", "keep-disabled"));
        verify(provider, never()).invalidate();
    }

    @Test
    void batchToggleDeduplicatesAndEnforcesSameLimitAsDelete() throws Exception {
        writeSkill("toggle-me", "published", false, false);
        var result = management.toggleBatch(" puppet-node ", List.of(" toggle-me ", "toggle-me"), true);
        assertEquals(1, result.requested());
        assertEquals(1, result.changed());
        verify(provider).invalidate();

        List<String> oversized = java.util.stream.IntStream.rangeClosed(0, 500)
                .mapToObj(i -> "skill-" + i).toList();
        assertThrows(IllegalArgumentException.class, () -> management.toggleBatch("puppet-node", oversized, true));
        assertThrows(IllegalArgumentException.class, () -> management.deleteBatch("puppet-node", oversized));
    }

    @Test
    void metadataSaveValidatesBeforeWriteAndRefreshesCatalog() throws Exception {
        writeSkill("editable", "published", true, false);
        Path metadata = tempDir.resolve("skills/puppet-node/editable/SKILL.md");
        String original = Files.readString(metadata);
        provider.getFormattedSkills("puppet-node", null);
        clearInvocations(registry, provider);
        HashMap<String, Object> params = new HashMap<>(Map.of(
                "scope", " puppet-node ", "name", " editable ", "path", "./SKILL.md", "content", "invalid"));

        assertEquals(400, controller.saveFile(params).get("code"));
        assertEquals(original, Files.readString(metadata));
        verify(provider, never()).invalidate();
        params.put("content", original.replace("test skill", "updated description"));
        assertEquals(200, controller.saveFile(params).get("code"));
        assertTrue(Files.readString(metadata).contains("updated description"));
        assertTrue(provider.getFormattedSkills("puppet-node", null).contains("updated description"));
        verify(registry).invalidate();
        verify(provider).invalidate();
    }

    @Test
    void fileMutationsPreserveEncodingMetadataProtectionAndCacheInvalidation() throws Exception {
        writeSkill("editable", "published", true, false);
        Path skillDir = tempDir.resolve("skills/puppet-node/editable");
        HashMap<String, Object> params = new HashMap<>(Map.of(
                "scope", "puppet-node", "name", "editable", "path", "assets/data.bin",
                "content", "AAEC", "encoding", "base64"));
        assertEquals(200, controller.saveFile(params).get("code"));
        org.junit.jupiter.api.Assertions.assertArrayEquals(new byte[]{0, 1, 2}, Files.readAllBytes(skillDir.resolve("assets/data.bin")));
        params.put("from", "assets/data.bin");
        params.put("to", "assets/renamed.bin");
        assertEquals(200, controller.moveFile(params).get("code"));
        assertFalse(Files.exists(skillDir.resolve("assets/data.bin")));
        params.put("path", "assets/renamed.bin");
        assertEquals(200, controller.deleteFile(params).get("code"));
        assertFalse(Files.exists(skillDir.resolve("assets/renamed.bin")));

        for (String path : List.of("SKILL.md", "./manifest.yaml")) {
            params.put("path", path);
            params.put("from", path);
            assertEquals(400, controller.deleteFile(params).get("code"));
            assertEquals(400, controller.moveFile(params).get("code"));
        }
        assertTrue(Files.exists(skillDir.resolve("SKILL.md")));
        assertTrue(Files.exists(skillDir.resolve("manifest.yaml")));
        verify(registry, times(3)).invalidate();
        verify(provider, times(3)).invalidate();
    }

    @Test
    void invalidFilePathsAndMissingSkillsHaveNoWriteSideEffects() throws Exception {
        writeSkill("editable", "published", true, false);
        HashMap<String, Object> params = new HashMap<>(Map.of(
                "scope", "puppet-node", "name", "editable", "path", "../outside.txt", "content", "text"));
        assertEquals(400, controller.saveFile(params).get("code"));
        assertEquals(400, controller.deleteFile(params).get("code"));
        params.put("from", "SKILL.md");
        params.put("to", "../outside.txt");
        assertEquals(400, controller.moveFile(params).get("code"));
        assertFalse(Files.exists(tempDir.resolve("skills/puppet-node/outside.txt")));
        params.put("name", "missing");
        params.put("path", "new.txt");
        assertEquals(404, controller.saveFile(params).get("code"));
        assertFalse(Files.exists(tempDir.resolve("skills/puppet-node/missing")));
        verify(provider, never()).invalidate();
    }

    @Test
    void failedWriteReleasesSharedLockAndAllowsRetry() throws Exception {
        writeSkill("editable", "published", true, false);
        var lock = operationLock.lockFor("puppet-node", "editable");
        doAnswer(invocation -> {
            assertTrue(lock.isHeldByCurrentThread());
            throw new IOException("disk unavailable");
        }).doCallRealMethod().when(fileService).writeFile(any(Path.class), anyString(), anyString(), anyString());
        HashMap<String, Object> params = new HashMap<>(Map.of(
                "scope", "puppet-node", "name", "editable", "path", "notes.txt", "content", "text"));

        assertEquals(500, controller.saveFile(params).get("code"));
        assertFalse(lock.isLocked());
        verify(provider, never()).invalidate();
        assertEquals(200, controller.saveFile(params).get("code"));
        assertFalse(lock.isLocked());
        verify(provider).invalidate();
    }
}
