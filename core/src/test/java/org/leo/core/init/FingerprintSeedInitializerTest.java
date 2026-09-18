package org.leo.core.init;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.leo.core.config.LeoConfig;
import org.leo.core.repository.session.AtomicFileStore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mockStatic;

class FingerprintSeedInitializerTest {
    @TempDir Path directory;

    @Test
    void cleansExistingMetadataWithoutReplacingCustomizedRules() throws Exception {
        AtomicFileStore store = new AtomicFileStore();
        Map<String, Object> rule = Map.of("requests", List.of(Map.of("uri", "/custom")),
                "match", Map.of("field", "body", "value", "custom-marker"),
                "version", Map.of("field", "headers", "prefix", "custom/"));
        Map<String, Object> info = Map.of("version", "any", "author", "custom-author",
                "description", "Custom detection", "remark", "Keep this note",
                "vulnerabilities", List.of(Map.of("title", "legacy issue")));
        Path fingerprints = directory.resolve("fingerprint");
        for (String id : List.of("nacos_any", "custom_any")) {
            store.writeJson(fingerprints.resolve(id + ".json").toFile(), Map.of(
                    "fingerprintId", id, "name", id, "protocol", "http", "tags", List.of("custom"),
                    "rule", rule, "info", info, "externalNotes", "unrelated metadata"));
        }
        Path broken = fingerprints.resolve("broken.json");
        Files.writeString(broken, "not json");

        try (var config = mockStatic(LeoConfig.class)) {
            config.when(LeoConfig::getVfsPath).thenReturn(directory.toString());
            FingerprintSeedInitializer initializer = new FingerprintSeedInitializer();
            initializer.run();
            for (String id : List.of("nacos_any", "custom_any")) {
                Map<String, Object> stored = store.readJsonMap(fingerprints.resolve(id + ".json").toFile());
                assertEquals(rule, stored.get("rule"));
                assertEquals(id, stored.get("fingerprintId"));
                assertEquals(List.of("custom"), stored.get("tags"));
                assertEquals(Map.of("version", "any", "author", "custom-author",
                        "description", "Custom detection", "remark", "Keep this note"), stored.get("info"));
                assertFalse(stored.containsKey("externalNotes"));
            }
            String first = Files.readString(fingerprints.resolve("nacos_any.json"));
            initializer.run();
            assertEquals(first, Files.readString(fingerprints.resolve("nacos_any.json")));
            assertEquals("not json", Files.readString(broken));
            assertTrue(Files.exists(fingerprints.resolve("shiro_1_x.json")));
        }
    }
}
