package org.leo.service.fingerprint;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.leo.core.config.LeoConfig;
import org.leo.core.entity.User;
import org.leo.core.infrastructure.persistence.session.AtomicFileStore;
import org.leo.core.infrastructure.persistence.session.FileStoreException;
import org.leo.core.util.json.JsonUtil;
import org.leo.service.importing.ImportConflictPolicy;
import org.mockito.MockedStatic;
import org.springframework.core.io.ByteArrayResource;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FingerprintManageServiceTest {
    @TempDir Path directory;
    private MockedStatic<LeoConfig> vfsConfig;
    private final FingerprintManageService service = new FingerprintManageService();
    private final AtomicFileStore store = new AtomicFileStore();
    private final Map<String, Object> rule = Map.of("requests", List.of(Map.of("uri", "/custom")),
            "match", Map.of("field", "body", "value", "marker"),
            "version", Map.of("field", "headers", "prefix", "demo/"));
    private final Map<String, Object> cleanInfo = Map.of("version", "any", "author", "tester",
            "description", "Detection method", "remark", "Rule note");

    @BeforeEach
    void configureVfs() {
        vfsConfig = mockStatic(LeoConfig.class);
        vfsConfig.when(LeoConfig::getVfsPath).thenReturn(directory.toString());
    }

    @AfterEach
    void releaseVfs() {
        vfsConfig.close();
    }

    @Test
    void savePersistsCurrentRuleMetadata() throws Exception {
        HashMap<String, Object> definition = sourceDefinition();
        service.saveFingerprint(definition, user());
        assertClean(stored());
        assertTrue(((Map<?, ?>) definition.get("info")).containsKey("vulnerabilities"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"rules.json", "RULES.JSON"})
    void importsCurrentJsonWithoutUnrelatedMetadataAndClosesInput(String filename) throws Exception {
        byte[] content = JsonUtil.toJsonString(sourceDefinition()).getBytes(StandardCharsets.UTF_8);
        var input = spy(new ByteArrayInputStream(content));
        var results = service.importFingerprints(filename, () -> input, ImportConflictPolicy.SKIP, user());
        assertEquals("imported", results.get(0).status());
        assertClean(stored());
        verify(input).close();
    }

    @Test
    void currentReadsAndBothExportsOnlyExposeRuleMetadata() throws Exception {
        store.writeJson(directory.resolve("fingerprint/demo_any.json").toFile(), sourceDefinition());
        assertClean(service.getFingerprintById("demo_any"));
        assertEquals(cleanInfo, service.listFingerprints().get(0).get("info"));
        assertClean(parse(service.exportFingerprint("demo_any")));
        try (var zip = new ZipInputStream(new ByteArrayInputStream(service.exportFingerprintsZip(List.of("demo_any"))))) {
            assertEquals("demo_any.json", zip.getNextEntry().getName());
            assertClean(parse(zip.readAllBytes()));
            assertNull(zip.getNextEntry());
        }
    }

    @Test
    void rejectsOldTopLevelVersionInsteadOfMigratingIt() throws Exception {
        HashMap<String, Object> old = sourceDefinition();
        old.remove("info");
        old.put("version", "any");
        assertEquals("缺少必需参数: info.version",
                assertThrows(IllegalArgumentException.class, () -> service.saveFingerprint(old, user())).getMessage());

        byte[] content = JsonUtil.toJsonString(old).getBytes(StandardCharsets.UTF_8);
        var results = service.importFingerprints("rules.json", new ByteArrayResource(content), ImportConflictPolicy.SKIP, user());
        assertEquals("failed", results.get(0).status());
    }

    @Test
    void rejectsStoredRecordsWithoutCurrentIdentity() throws Exception {
        HashMap<String, Object> old = sourceDefinition();
        old.remove("fingerprintId");
        store.writeJson(directory.resolve("fingerprint/demo_any.json").toFile(), old);
        assertThrows(IllegalArgumentException.class, () -> service.getFingerprintById("demo_any"));
        assertTrue(service.listFingerprints().isEmpty());
    }

    @Test
    void failedSaveAndOverwriteImportLeaveThePreviousDefinitionIntact() throws Exception {
        service.saveFingerprint(sourceDefinition(), user());
        Path path = directory.resolve("fingerprint/demo_any.json");
        byte[] previous = Files.readAllBytes(path);
        AtomicFileStore files = spy(new AtomicFileStore());
        doThrow(new IOException("fixture persistence failure")).when(files).writeJson(any(), anyMap());
        FingerprintManageService failing = new FingerprintManageService(files);
        HashMap<String, Object> replacement = sourceDefinition();
        replacement.put("tags", List.of("replacement"));
        assertThrows(IOException.class, () -> failing.saveFingerprint(replacement, user()));
        var results = failing.importFingerprints("rules.json", upload(replacement), ImportConflictPolicy.OVERWRITE, user());
        assertEquals("failed", results.get(0).status());
        assertArrayEquals(previous, Files.readAllBytes(path));
        assertEquals(List.of("web"), service.getFingerprintById("demo_any").get("tags"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "null", "[]", "{broken"})
    void corruptStoredJsonIsNotReportedAsMissingOrAnEmptySuccessfulList(String content) throws Exception {
        Path path = directory.resolve("fingerprint/demo_any.json");
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
        assertEquals(FileStoreException.Reason.INVALID_CONTENT,
                assertThrows(FileStoreException.class, () -> service.getFingerprintById("demo_any")).getReason());
        assertThrows(FileStoreException.class, service::listFingerprints);
        assertEquals(content, Files.readString(path));
    }

    @Test
    void missingFilesAndUnreadablePathsHaveDifferentOutcomes() throws Exception {
        assertThrows(FingerprintManageService.FingerprintNotFoundException.class,
                () -> service.getFingerprintById("missing"));
        Path path = directory.resolve("fingerprint/demo_any.json");
        Files.createDirectories(path);
        assertEquals(FileStoreException.Reason.READ_FAILED,
                assertThrows(FileStoreException.class, () -> service.getFingerprintById("demo_any")).getReason());
    }

    @Test
    void skipAndRenameImportsProtectEvenACorruptExistingFile() throws Exception {
        Path original = directory.resolve("fingerprint/demo_any.json");
        Files.createDirectories(original.getParent());
        Files.writeString(original, "{broken");
        for (String policy : new String[]{null, "unknown", " rename "}) {
            var skipped = service.importFingerprints("rules.json", upload(sourceDefinition()), ImportConflictPolicy.parse(policy), user());
            assertEquals("skipped", skipped.get(0).status());
        }
        List<String> ids = new ArrayList<>();
        for (int n = 0; n < 2; n++) {
            var renamed = service.importFingerprints("rules.json", upload(sourceDefinition()), ImportConflictPolicy.parse("rename"), user());
            assertEquals("renamed", renamed.get(0).status());
            ids.add(renamed.get(0).fingerprintId());
            assertNotNull(service.getFingerprintById(ids.get(n)));
        }
        assertNotEquals(ids.get(0), ids.get(1));
        assertEquals("{broken", Files.readString(original));
        assertEquals("overwritten", service.importFingerprints("rules.json", upload(sourceDefinition()),
                ImportConflictPolicy.parse("OVERWRITE"), user()).get(0).status());
        assertClean(service.getFingerprintById("demo_any"));
    }

    @Test
    void zipImportsReportBadEntriesInsteadOfSilentlyOmittingThem() throws Exception {
        var bytes = new java.io.ByteArrayOutputStream();
        try (var zip = new java.util.zip.ZipOutputStream(bytes)) {
            for (String name : List.of("bad.json", "good.json")) {
                zip.putNextEntry(new java.util.zip.ZipEntry(name));
                zip.write((name.equals("bad.json") ? "{broken" : JsonUtil.toJsonString(sourceDefinition()))
                        .getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        var results = service.importFingerprints("rules.zip", new ByteArrayResource(bytes.toByteArray()),
                ImportConflictPolicy.SKIP, user());
        assertEquals(2, results.size());
        assertEquals(1, results.stream().filter(result -> "failed".equals(result.status()) && "bad.json".equals(result.name())).count());
        assertEquals(1, results.stream().filter(result -> "imported".equals(result.status())).count());
        assertClean(stored());
    }

    @Test
    void invalidJsonArrayEntriesAreReportedBeforeAnyRuleIsPersisted() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> service.importFingerprints("rules.json",
                upload(List.of(sourceDefinition(), "bad entry")), ImportConflictPolicy.SKIP, user()));
        assertFalse(Files.exists(directory.resolve("fingerprint/demo_any.json")));
    }

    @Test
    void invalidUtf8UploadsAreRejectedInsteadOfSilentlyChangingTheirContents() throws Exception {
        byte[] prefix = "{\"name\":\"".getBytes(StandardCharsets.UTF_8);
        byte[] invalid = java.util.Arrays.copyOf(prefix, prefix.length + 4);
        invalid[prefix.length] = (byte) 0xC3;
        invalid[prefix.length + 1] = (byte) 0x28;
        invalid[prefix.length + 2] = '"';
        invalid[prefix.length + 3] = '}';
        assertThrows(IllegalArgumentException.class, () -> service.importFingerprints("rules.json",
                new ByteArrayResource(invalid),
                ImportConflictPolicy.SKIP, user()));
        assertFalse(Files.exists(directory.resolve("fingerprint")));
    }

    @Test
    @org.junit.jupiter.api.Timeout(30)
    void concurrentSkipImportsDoNotOverwriteTheFirstCommittedDefinition() throws Exception {
        var executor = Executors.newFixedThreadPool(6);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> results = new ArrayList<>();
        try {
            for (int n = 0; n < 12; n++) {
                int index = n;
                results.add(executor.submit(() -> {
                    // Mockito 静态桩按线程隔离，工作线程需要独立配置。
                    try (var config = mockStatic(LeoConfig.class)) {
                        config.when(LeoConfig::getVfsPath).thenReturn(directory.toString());
                        HashMap<String, Object> definition = sourceDefinition();
                        definition.put("tags", List.of("writer-" + index));
                        start.await();
                        return service.importFingerprints("rules.json", upload(definition), ImportConflictPolicy.SKIP, user()).get(0).status();
                    }
                }));
            }
            start.countDown();
            List<String> statuses = new ArrayList<>();
            for (Future<String> result : results) statuses.add(result.get(20, TimeUnit.SECONDS));
            assertEquals(1, statuses.stream().filter("imported"::equals).count());
            assertEquals(11, statuses.stream().filter("skipped"::equals).count());
            try (var files = Files.list(directory.resolve("fingerprint"))) {
                assertEquals(List.of("demo_any.json"), files.map(path -> path.getFileName().toString()).toList());
            }
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private ByteArrayResource upload(Object definition) {
        return new ByteArrayResource(JsonUtil.toJsonString(definition).getBytes(StandardCharsets.UTF_8));
    }

    private HashMap<String, Object> sourceDefinition() {
        Map<String, Object> info = new HashMap<>(cleanInfo);
        info.put("vulnerabilities", List.of(Map.of("title", "unrelated issue")));
        info.put("externalNotes", "unrelated metadata");
        return new HashMap<>(Map.of("fingerprintId", "demo_any", "name", "demo", "protocol", "http",
                "tags", List.of("web"), "info", info, "rule", rule, "externalNotes", "unrelated metadata"));
    }

    private Map<?, ?> stored() {
        return store.readJsonMap(directory.resolve("fingerprint/demo_any.json").toFile());
    }

    private Map<?, ?> parse(byte[] content) {
        return (Map<?, ?>) JsonUtil.fromJsonString(new String(content, StandardCharsets.UTF_8), Object.class);
    }

    private void assertClean(Map<?, ?> definition) {
        assertEquals(cleanInfo, definition.get("info"));
        assertEquals(rule, definition.get("rule"));
        assertEquals("demo_any", definition.get("fingerprintId"));
        assertEquals(List.of("web"), definition.get("tags"));
        assertFalse(definition.containsKey("externalNotes"));
    }

    private User user() {
        User user = new User();
        user.setUserId("tester");
        return user;
    }
}
