package org.leo.service.fingerprint;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.leo.core.config.LeoConfig;
import org.leo.core.entity.User;
import org.leo.core.repository.session.AtomicFileStore;
import org.leo.core.util.json.JsonUtil;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mockStatic;

class FingerprintManageServiceTest {
    @TempDir Path directory;
    private final FingerprintManageService service = new FingerprintManageService();
    private final AtomicFileStore store = new AtomicFileStore();
    private final Map<String, Object> rule = Map.of("requests", List.of(Map.of("uri", "/custom")),
            "match", Map.of("field", "body", "value", "marker"),
            "version", Map.of("field", "headers", "prefix", "demo/"));
    private final Map<String, Object> cleanInfo = Map.of("version", "any", "author", "tester",
            "description", "Detection method", "remark", "Rule note");

    @Test
    void savePersistsCurrentRuleMetadata() throws Exception {
        try (var config = mockStatic(LeoConfig.class)) {
            config.when(LeoConfig::getVfsPath).thenReturn(directory.toString());
            HashMap<String, Object> definition = sourceDefinition();
            service.saveFingerprint(definition, user());
            assertClean(stored());
            assertTrue(((Map<?, ?>) definition.get("info")).containsKey("vulnerabilities"));
        }
    }

    @Test
    void importsCurrentJsonWithoutUnrelatedMetadata() throws Exception {
        try (var config = mockStatic(LeoConfig.class)) {
            config.when(LeoConfig::getVfsPath).thenReturn(directory.toString());
            byte[] content = JsonUtil.toJsonString(sourceDefinition()).getBytes(StandardCharsets.UTF_8);
            var results = service.importFingerprints(new MockMultipartFile("file", "current.json",
                    "application/json", content), FingerprintManageService.ConflictPolicy.SKIP, user());
            assertEquals("imported", results.get(0).status());
            assertClean(stored());
        }
    }

    @Test
    void currentReadsAndBothExportsOnlyExposeRuleMetadata() throws Exception {
        try (var config = mockStatic(LeoConfig.class)) {
            config.when(LeoConfig::getVfsPath).thenReturn(directory.toString());
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
    }

    @Test
    void rejectsOldTopLevelVersionInsteadOfMigratingIt() throws Exception {
        try (var config = mockStatic(LeoConfig.class)) {
            config.when(LeoConfig::getVfsPath).thenReturn(directory.toString());
            HashMap<String, Object> old = sourceDefinition();
            old.remove("info");
            old.put("version", "any");
            assertEquals("缺少必需参数: info.version",
                    assertThrows(IllegalArgumentException.class, () -> service.saveFingerprint(old, user())).getMessage());

            byte[] content = JsonUtil.toJsonString(old).getBytes(StandardCharsets.UTF_8);
            var results = service.importFingerprints(new MockMultipartFile("file", "old.json",
                    "application/json", content), FingerprintManageService.ConflictPolicy.SKIP, user());
            assertEquals("failed", results.get(0).status());
        }
    }

    @Test
    void rejectsStoredRecordsWithoutCurrentIdentity() throws Exception {
        try (var config = mockStatic(LeoConfig.class)) {
            config.when(LeoConfig::getVfsPath).thenReturn(directory.toString());
            HashMap<String, Object> old = sourceDefinition();
            old.remove("fingerprintId");
            store.writeJson(directory.resolve("fingerprint/demo_any.json").toFile(), old);
            assertThrows(IllegalArgumentException.class, () -> service.getFingerprintById("demo_any"));
            assertTrue(service.listFingerprints().isEmpty());
        }
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
