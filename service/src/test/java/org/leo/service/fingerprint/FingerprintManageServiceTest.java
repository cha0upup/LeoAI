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
    void bothSaveEntrypointsOnlyPersistRuleMetadata() throws Exception {
        try (var config = mockStatic(LeoConfig.class)) {
            config.when(LeoConfig::getVfsPath).thenReturn(directory.toString());
            HashMap<String, Object> legacy = legacyDefinition();
            service.saveFingerprint(legacy, user());
            assertClean(stored());
            assertTrue(((Map<?, ?>) legacy.get("info")).containsKey("vulnerabilities"));

            service.saveFingerprint("tester", "demo", JsonUtil.toJsonString(rule),
                    JsonUtil.toJsonString(legacy.get("info")), "[\"web\"]", null);
            assertClean(stored());
        }
    }

    @Test
    void importingLegacyJsonDropsUnrelatedMetadata() throws Exception {
        try (var config = mockStatic(LeoConfig.class)) {
            config.when(LeoConfig::getVfsPath).thenReturn(directory.toString());
            byte[] content = JsonUtil.toJsonString(legacyDefinition()).getBytes(StandardCharsets.UTF_8);
            var results = service.importFingerprints(new MockMultipartFile("file", "legacy.json",
                    "application/json", content), FingerprintManageService.ConflictPolicy.SKIP, user());
            assertEquals("imported", results.get(0).status());
            assertClean(stored());
        }
    }

    @Test
    void legacyReadsAndBothExportsOnlyExposeRuleMetadata() throws Exception {
        try (var config = mockStatic(LeoConfig.class)) {
            config.when(LeoConfig::getVfsPath).thenReturn(directory.toString());
            store.writeJson(directory.resolve("fingerprint/demo_any.json").toFile(), legacyDefinition());
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

    private HashMap<String, Object> legacyDefinition() {
        Map<String, Object> info = new HashMap<>(cleanInfo);
        info.put("vulnerabilities", List.of(Map.of("title", "legacy issue")));
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
