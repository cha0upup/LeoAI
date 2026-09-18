package org.leo.web.controller.platform.skill;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.leo.ai.service.LeoSkillsProvider;
import org.leo.ai.service.SkillExportService;
import org.leo.ai.service.SkillFileService;
import org.leo.ai.service.SkillManifestService;
import org.leo.ai.service.SkillOperationLock;
import org.leo.ai.service.SkillRegistryService;
import org.leo.core.config.LeoConfig;
import org.leo.web.service.SkillManagementService;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SkillControllerArchiveTest {

    @TempDir Path tempDir;
    private String previousVfsPath;
    private SkillManifestService manifests;
    private SkillRegistryService registry;
    private LeoSkillsProvider provider;
    private SkillOperationLock locks;
    private SkillExportService archives;
    private SkillManagementService management;
    private SkillController controller;

    @BeforeEach
    void setUp() {
        previousVfsPath = LeoConfig.getVfsPath();
        ReflectionTestUtils.setField(LeoConfig.class, "VFS_PATH", tempDir.toString());
        manifests = new SkillManifestService();
        registry = spy(new SkillRegistryService(manifests));
        provider = spy(new LeoSkillsProvider(registry));
        configure(spy(new SkillOperationLock()));
    }

    private void configure(SkillOperationLock operationLock) {
        locks = operationLock;
        archives = spy(new SkillExportService(manifests, locks));
        SkillFileService files = new SkillFileService();
        management = new SkillManagementService(registry, provider, manifests, files, archives, locks);
        controller = new SkillController(registry, files, management);
    }

    @AfterEach
    void restoreConfig() {
        ReflectionTestUtils.setField(LeoConfig.class, "VFS_PATH", previousVfsPath);
    }

    @Test
    void singleExportRoundTripsMetadataAndBinaryResources() throws Exception {
        writeSkill("alpha", "original");
        Files.createDirectories(skillDir("alpha").resolve("assets"));
        Files.write(skillDir("alpha").resolve("assets/data.bin"), new byte[]{0, 1, -1});
        var exported = controller.exportSkill(" puppet-node ", " alpha ");

        assertEquals(200, exported.getStatusCode().value());
        assertEquals("application/zip", exported.getHeaders().getContentType().toString());
        assertEquals("alpha.skill", exported.getHeaders().getContentDisposition().getFilename());
        Map<String, byte[]> entries = unzip(exported.getBody());
        assertEquals(Set.of("SKILL.md", "manifest.yaml", "assets/data.bin"), entries.keySet());
        assertArrayEquals(new byte[]{0, 1, -1}, entries.get("assets/data.bin"));

        assertTrue(management.delete("puppet-node", "alpha").succeeded());
        assertTrue(registry.listAllSkills("puppet-node").isEmpty());
        clearInvocations(registry, provider);
        var imported = controller.importSkills(upload(exported.getBody()), "puppet-node", "alpha", null);
        assertEquals(200, imported.get("code"));
        assertEquals("imported", result(imported, "alpha").get("status"));
        assertArrayEquals(new byte[]{0, 1, -1}, Files.readAllBytes(skillDir("alpha").resolve("assets/data.bin")));
        var descriptor = manifests.inspect(skillDir("alpha"), "puppet-node").descriptor();
        assertEquals("draft", descriptor.status());
        assertEquals("imported", descriptor.source());
        assertFalse(descriptor.enabled());
        assertEquals(1, registry.listAllSkills("puppet-node").size());
        assertFalse(provider.getFormattedSkills("puppet-node", null).contains("alpha"));
        verify(registry).invalidate();
        verify(provider).invalidate();
    }

    @Test
    void batchExportDeduplicatesAndPreservesDirectoryLayout() throws Exception {
        writeSkill("alpha", "first");
        writeSkill("beta", "second");
        var response = controller.exportSkillsBatch(batch(" beta ", "alpha", "beta", "missing", "../outside", 7));

        assertEquals(200, response.getStatusCode().value());
        assertTrue(response.getHeaders().getContentDisposition().getFilename().startsWith("skills_puppet-node_"));
        assertEquals(Set.of("alpha/SKILL.md", "alpha/manifest.yaml", "beta/SKILL.md", "beta/manifest.yaml"),
                unzip(response.getBody()).keySet());
        assertFalse(locks.lockFor("puppet-node", "alpha").isLocked());
        assertFalse(locks.lockFor("puppet-node", "beta").isLocked());
    }

    @Test
    void archiveErrorsKeepExistingResponseShapeAndReleaseLocks() throws Exception {
        assertEquals(400, controller.exportSkill("unknown", "alpha").getStatusCode().value());
        assertEquals(400, controller.exportSkill("puppet-node", "../outside").getStatusCode().value());
        assertEquals(404, controller.exportSkill("puppet-node", "missing").getStatusCode().value());
        assertNull(controller.exportSkill("puppet-node", "missing").getBody());
        assertEquals(400, controller.exportSkillsBatch(batch("missing", "../outside")).getStatusCode().value());
        writeSkill("alpha", "first");
        doThrow(new IOException("read failed")).when(archives).exportSkill(any(), any());
        var response = controller.exportSkill("puppet-node", "alpha");
        assertEquals(500, response.getStatusCode().value());
        assertEquals("导出失败：read failed", new String(response.getBody(), StandardCharsets.UTF_8));
        assertFalse(locks.lockFor("puppet-node", "alpha").isLocked());
    }

    @Test
    void batchImportReportsInvalidEntriesWithoutEnablingImportedSkills() throws Exception {
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("alpha/SKILL.md", skill("alpha", "valid"));
        entries.put("alpha/manifest.yaml", manifest("alpha"));
        entries.put("broken/SKILL.md", skill("broken", "missing manifest"));
        var response = controller.importSkills(upload(zip(entries)), "puppet-node", null, "skip");

        assertEquals(200, response.get("code"));
        assertEquals("imported", result(response, "alpha").get("status"));
        assertEquals("failed", result(response, "broken").get("status"));
        assertFalse(Files.exists(skillDir("broken")));
        assertFalse(registry.isSkillEnabled("puppet-node", "alpha"));
    }

    @Test
    void malformedArchiveNeverWritesOutsideStaging() throws Exception {
        var response = controller.importSkills(upload(zip(Map.of("../outside.txt", "bad"))),
                "puppet-node", null, "overwrite");
        assertEquals(400, response.get("code"));
        assertFalse(Files.exists(tempDir.resolve("skills/puppet-node")));
        verify(locks, never()).lockFor(anyString(), anyString());
        assertEquals(400, controller.importSkills(null, "puppet-node", "alpha", null).get("code"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"skip", "overwrite"})
    void importChecksConflictsAfterAcquiringTheSharedLock(String policy) throws Exception {
        ReentrantLock targetLock = locks.lockFor("puppet-node", "alpha");
        CountDownLatch attempted = new CountDownLatch(1);
        doAnswer(invocation -> {
            attempted.countDown();
            return targetLock;
        }).when(locks).lockFor("puppet-node", "alpha");
        var executor = Executors.newSingleThreadExecutor();
        targetLock.lock();
        try {
            var pending = executor.submit(() -> controller.importSkills(
                    upload(zip(Map.of("SKILL.md", skill("alpha", "imported"), "manifest.yaml", manifest("alpha")))),
                    "puppet-node", "alpha", policy));
            assertTrue(attempted.await(5, TimeUnit.SECONDS));
            assertFalse(pending.isDone());
            // Another writer creates the target while import is waiting for its lock.
            assertTrue(management.save("puppet-node", "alpha", skill("alpha", "concurrent"), manifest("alpha")).succeeded());
            targetLock.unlock();
            var response = pending.get(5, TimeUnit.SECONDS);
            assertEquals(200, response.get("code"));
            assertEquals(policy.equals("skip") ? "skipped" : "overwritten", result(response, "alpha").get("status"));
            assertEquals(skill("alpha", policy.equals("skip") ? "concurrent" : "imported"),
                    Files.readString(skillDir("alpha").resolve("SKILL.md")));
            assertFalse(targetLock.isLocked());
        } finally {
            if (targetLock.isHeldByCurrentThread()) targetLock.unlock();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void importFailureAfterCommitStillInvalidatesBothCaches() throws Exception {
        writeSkill("alpha", "old");
        assertTrue(provider.getFormattedSkills("puppet-node", null).contains("alpha"));
        clearInvocations(registry, provider);
        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw new IOException("cleanup failed");
        }).when(archives).importSkills(any(), any(), any(), any());

        var response = controller.importSkills(upload(zip(Map.of(
                "SKILL.md", skill("alpha", "updated"), "manifest.yaml", manifest("alpha")))),
                "puppet-node", "alpha", "overwrite");
        assertEquals(500, response.get("code"));
        assertTrue(Files.readString(skillDir("alpha").resolve("SKILL.md")).contains("updated"));
        assertFalse(provider.getFormattedSkills("puppet-node", null).contains("alpha"));
        verify(registry).invalidate();
        verify(provider).invalidate();
        assertFalse(locks.lockFor("puppet-node", "alpha").isLocked());
    }

    @Test
    void exportRechecksExistenceAfterAConcurrentDelete() throws Exception {
        writeSkill("alpha", "old");
        ReentrantLock targetLock = locks.lockFor("puppet-node", "alpha");
        CountDownLatch attempted = new CountDownLatch(1);
        doAnswer(invocation -> {
            attempted.countDown();
            return targetLock;
        }).when(locks).lockFor("puppet-node", "alpha");
        var executor = Executors.newSingleThreadExecutor();
        targetLock.lock();
        try {
            var pending = executor.submit(() -> controller.exportSkill("puppet-node", "alpha"));
            assertTrue(attempted.await(5, TimeUnit.SECONDS));
            assertTrue(management.delete("puppet-node", "alpha").succeeded());
            targetLock.unlock();
            assertEquals(404, pending.get(5, TimeUnit.SECONDS).getStatusCode().value());
        } finally {
            if (targetLock.isHeldByCurrentThread()) targetLock.unlock();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void overlappingExportsWithOppositeRequestOrdersCompleteWithoutDeadlock() throws Exception {
        writeSkill("alpha", "first");
        writeSkill("beta", "second");
        CountDownLatch firstLocks = new CountDownLatch(2);
        Map<Thread, List<String>> acquired = new ConcurrentHashMap<>();
        configure(new SkillOperationLock() {
            private final Map<String, ReentrantLock> boundedLocks = new ConcurrentHashMap<>();

            @Override
            public ReentrantLock lockFor(String scope, String name) {
                return boundedLocks.computeIfAbsent(name, ignored -> new ReentrantLock() {
                    @Override
                    public void lock() {
                        try {
                            if (!tryLock(5, TimeUnit.SECONDS)) throw new IllegalStateException("lock order deadlock");
                            List<String> sequence = acquired.computeIfAbsent(Thread.currentThread(), key -> new ArrayList<>());
                            sequence.add(name);
                            if (sequence.size() == 1) {
                                firstLocks.countDown();
                                // With opposite ordering both first locks would be held before either second lock.
                                firstLocks.await(1, TimeUnit.SECONDS);
                            }
                        } catch (InterruptedException e) {
                            if (isHeldByCurrentThread()) unlock();
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(e);
                        }
                    }
                });
            }
        });
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> controller.exportSkillsBatch(batch("beta", "alpha")));
            var second = executor.submit(() -> controller.exportSkillsBatch(batch("alpha", "beta")));
            assertEquals(4, unzip(first.get(8, TimeUnit.SECONDS).getBody()).size());
            assertEquals(4, unzip(second.get(8, TimeUnit.SECONDS).getBody()).size());
            assertEquals(2, acquired.size());
            acquired.values().forEach(order -> assertEquals(List.of("alpha", "beta"), order));
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private Path skillDir(String name) {
        return tempDir.resolve("skills/puppet-node").resolve(name);
    }

    private void writeSkill(String name, String body) throws IOException {
        Files.createDirectories(skillDir(name));
        Files.writeString(skillDir(name).resolve("SKILL.md"), skill(name, body));
        Files.writeString(skillDir(name).resolve("manifest.yaml"), manifest(name));
    }

    private static String skill(String name, String body) {
        return "---\nname: " + name + "\ndescription: archive test\n---\n\n" + body + "\n";
    }

    private static String manifest(String name) {
        return """
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
                status: published
                source: custom
                owner: test
                enabled: true
                """.formatted(name, name);
    }

    private static HashMap<String, Object> batch(Object... names) {
        return new HashMap<>(Map.of("scope", "puppet-node", "names", List.of(names)));
    }

    private static MockMultipartFile upload(byte[] bytes) {
        return new MockMultipartFile("file", "skills.zip", "application/zip", bytes);
    }

    private static byte[] zip(Map<String, String> entries) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            for (var entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return output.toByteArray();
    }

    private static Map<String, byte[]> unzip(byte[] bytes) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) entries.put(entry.getName(), zip.readAllBytes());
        }
        return entries;
    }

    private static Map<?, ?> result(Map<String, Object> response, String name) {
        List<?> results = (List<?>) ((Map<?, ?>) response.get("data")).get("results");
        return results.stream().map(value -> (Map<?, ?>) value)
                .filter(value -> name.equals(value.get("originalName"))).findFirst().orElseThrow();
    }
}
