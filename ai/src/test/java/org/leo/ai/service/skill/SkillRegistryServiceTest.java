package org.leo.ai.service.skill;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

class SkillRegistryServiceTest {

    @TempDir
    Path tempDir;

    private SkillRegistryService registry;
    private final SkillManifestService manifests = spy(new SkillManifestService());
    private final AtomicLong clock = new AtomicLong();

    @BeforeEach
    void setUp() {
        registry = new SkillRegistryService(manifests, () -> tempDir, clock::get);
    }

    @Test
    void enabledContentIsReadableButDisabledContentCannotBeActivated() throws Exception {
        writeSkill("agent-node", "enabled-skill", true, "enabled body");
        writeSkill("agent-node", "disabled-skill", false, "disabled body");

        assertEquals("enabled body\n", SkillRegistryService.stripFrontmatter(
                registry.getEnabledSkillContent("agent-node", "enabled-skill")));
        assertNull(registry.getEnabledSkillContent("agent-node", "disabled-skill"));
        assertTrue(registry.getSkillContent("agent-node", "disabled-skill")
                .contains("disabled body"));
    }

    @Test
    void formattedIndexKeepsPermissionFiltersLocalToEachCall() throws Exception {
        writeSkill("platform", "visible-skill", true, "visible body");
        writeSkill("platform", "restricted-skill", true, "restricted body");
        LeoSkillsProvider provider = new LeoSkillsProvider(registry);
        String allSkills = provider.getFormattedSkills("platform");

        String filtered = provider.getFormattedSkills("platform",
                skill -> "visible-skill".equals(skill.getName()));

        assertTrue(filtered.contains("visible-skill"));
        assertFalse(filtered.contains("restricted-skill"));
        assertEquals("", provider.getFormattedSkills("platform", skill -> false));
        assertEquals(allSkills, provider.getFormattedSkills("platform"));
        assertTrue(allSkills.contains("restricted-skill"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"agent-node", "platform"})
    void formattedIndexReflectsRegistryRefreshWithoutSeparateInvalidation(String scope) throws Exception {
        writeSkill(scope, "mutable-skill", true, "first body");
        LeoSkillsProvider provider = new LeoSkillsProvider(registry);
        assertTrue(provider.getFormattedSkills(scope).contains("mutable-skill"));

        writeSkill(scope, "mutable-skill", false, "second body");
        writeSkill(scope, "new-skill", true, "new body");
        registry.invalidate();

        String index = provider.getFormattedSkills(scope);
        assertFalse(index.contains("mutable-skill"));
        assertTrue(index.contains("new-skill"));
    }

    @Test
    void invalidatingDuringAScanCannotCacheItsOutdatedResult() throws Exception {
        writeSkill("platform", "mutable-skill", true, "body");
        Path skillDir = tempDir.resolve("skills/platform/mutable-skill");
        AtomicBoolean invalidateOnce = new AtomicBoolean();
        doAnswer(call -> {
            SkillInspection inspected = (SkillInspection) call.callRealMethod();
            if (invalidateOnce.compareAndSet(false, true)) {
                writeSkill("platform", "mutable-skill", false, "body");
                registry.invalidate();
            }
            return inspected;
        }).when(manifests).inspect(skillDir, "platform");

        assertTrue(registry.listAllSkills("platform").get(0).isEnabled());

        assertFalse(registry.listAllSkills("platform").get(0).isEnabled());
        assertFalse(registry.health("platform").get(0).descriptor().enabled());
    }

    @ParameterizedTest
    @CsvSource({"29999, false", "30000, false", "30001, true"})
    void metadataAndHealthShareTheSameExpiry(long elapsedMillis, boolean expired) throws Exception {
        writeSkill("platform", "mutable-skill", true, "body");
        assertTrue(registry.listAllSkills("platform").get(0).isEnabled());

        writeSkill("platform", "mutable-skill", false, "body");
        clock.set(elapsedMillis);

        assertEquals(!expired, registry.listAllSkills("platform").get(0).isEnabled());
        assertEquals(!expired, registry.health("platform").get(0).descriptor().enabled());
        verify(manifests, times(expired ? 2 : 1))
                .inspect(tempDir.resolve("skills/platform/mutable-skill"), "platform");
    }

    @Test
    void concurrentMetadataAndHealthQueriesShareOneScan() throws Exception {
        writeSkill("platform", "shared-skill", true, "body");
        Path skillDir = tempDir.resolve("skills/platform/shared-skill");
        CountDownLatch scanning = new CountDownLatch(1);
        CountDownLatch releaseScan = new CountDownLatch(1);
        doAnswer(call -> {
            SkillInspection inspected = (SkillInspection) call.callRealMethod();
            scanning.countDown();
            assertTrue(releaseScan.await(5, TimeUnit.SECONDS));
            return inspected;
        }).when(manifests).inspect(skillDir, "platform");
        var workers = Executors.newFixedThreadPool(6);
        try {
            List<Future<String>> results = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                boolean healthQuery = i % 2 == 0;
                results.add(workers.submit(() -> healthQuery
                        ? registry.health("platform").get(0).name()
                        : registry.listAllSkills("platform").get(0).getName()));
            }
            assertTrue(scanning.await(5, TimeUnit.SECONDS));
            releaseScan.countDown();
            for (Future<String> result : results) {
                assertEquals("shared-skill", result.get(5, TimeUnit.SECONDS));
            }
            verify(manifests).inspect(skillDir, "platform");
        } finally {
            releaseScan.countDown();
            workers.shutdownNow();
        }
    }

    @Test
    void rejectsPathTraversalAndNestedNames() throws Exception {
        writeSkill("platform", "platform-only", true, "platform body");

        assertFalse(SkillRegistryService.isValidSkillName("../platform/platform-only"));
        assertFalse(SkillRegistryService.isValidSkillName("nested/skill"));
        assertFalse(SkillRegistryService.isValidSkillName("."));
        assertNull(registry.getSkillContent("agent-node", "../platform/platform-only"));
        assertNull(registry.getSkillContent("agent-node", "nested/skill"));
    }

    @Test
    void activationReadsCurrentEnabledFlagWithoutWaitingForMetadataCache() throws Exception {
        writeSkill("agent-node", "mutable-skill", true, "first body");
        assertNotNull(registry.getEnabledSkillContent("agent-node", "mutable-skill"));

        writeSkill("agent-node", "mutable-skill", false, "second body");

        assertNull(registry.getEnabledSkillContent("agent-node", "mutable-skill"));
    }

    @Test
    void activationFailsClosedForInvalidOrMismatchedFrontmatter() throws Exception {
        Path skillDir = tempDir.resolve("skills/agent-node/mismatched-skill");
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
                "mismatched-skill", "agent-node", true));

        assertNull(registry.getEnabledSkillContent("agent-node", "mismatched-skill"));
    }

    @Test
    void activationFailsClosedForCatalogLevelErrors() throws Exception {
        writeSkill("agent-node", "first-skill", true, "first body");
        writeSkill("agent-node", "second-skill", true, "second body");
        Path secondManifest = tempDir.resolve("skills/agent-node/second-skill/manifest.yaml");
        Files.writeString(secondManifest, Files.readString(secondManifest)
                .replace("leo.test.second-skill", "leo.test.first-skill"));

        registry.invalidate();

        assertNull(registry.getEnabledSkillContent("agent-node", "first-skill"));
        assertNull(registry.getEnabledSkillContent("agent-node", "second-skill"));
        assertNull(registry.getDescriptor("agent-node", "first-skill"));
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
