package org.leo.core.init;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.leo.core.repository.session.AtomicFileStore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FingerprintSeedInitializerTest {
    @TempDir Path directory;

    @Test
    void seedsMissingRulesWithoutRewritingExistingFiles() throws Exception {
        AtomicFileStore store = new AtomicFileStore();
        Map<String, Object> rule = Map.of("requests", List.of(Map.of("uri", "/custom")),
                "match", Map.of("field", "body", "value", "custom-marker"),
                "version", Map.of("field", "headers", "prefix", "custom/"));
        Map<String, Object> info = Map.of("version", "any", "author", "custom-author",
                "description", "Custom detection", "remark", "Keep this note",
                "customNote", "user metadata");
        Path fingerprints = directory.resolve("fingerprint");
        for (String id : List.of("nacos_any", "custom_any")) {
            store.writeJson(fingerprints.resolve(id + ".json").toFile(), Map.of(
                    "fingerprintId", id, "name", id, "protocol", "http", "tags", List.of("custom"),
                    "rule", rule, "info", info, "externalNotes", "unrelated metadata"));
        }
        Path broken = fingerprints.resolve("broken.json");
        Files.writeString(broken, "not json");

        {
            VFSInitializer vfs = mock(VFSInitializer.class);
            when(vfs.getRoot()).thenReturn(directory);
            FingerprintSeedInitializer initializer = new FingerprintSeedInitializer(vfs, new SeedResourceStore());
            initializer.run();
            for (String id : List.of("nacos_any", "custom_any")) {
                Map<String, Object> stored = store.readJsonMap(fingerprints.resolve(id + ".json").toFile());
                assertEquals(rule, stored.get("rule"));
                assertEquals(id, stored.get("fingerprintId"));
                assertEquals(List.of("custom"), stored.get("tags"));
                assertEquals(info, stored.get("info"));
                assertEquals("unrelated metadata", stored.get("externalNotes"));
            }
            String first = Files.readString(fingerprints.resolve("nacos_any.json"));
            initializer.run();
            assertEquals(first, Files.readString(fingerprints.resolve("nacos_any.json")));
            assertEquals("not json", Files.readString(broken));
            assertTrue(Files.exists(fingerprints.resolve("shiro_1_x.json")));
            Map<String, Object> geoserver = store.readJsonMap(fingerprints.resolve("geoserver_any.json").toFile());
            assertEquals("geoserver_any", geoserver.get("fingerprintId"));
            assertEquals("geoserver", geoserver.get("name"));
            assertEquals("http", geoserver.get("protocol"));
        }
    }

    @Test
    void seedWriteFailureIsFatalRatherThanReportedAsSuccessfulStartup() throws Exception {
        var vfs = mock(VFSInitializer.class);
        when(vfs.getRoot()).thenReturn(directory);
        var store = mock(SeedResourceStore.class);
        when(store.sync(any(), anyString(), any(), eq(false))).thenThrow(new java.io.IOException("fixture"));
        assertThrows(java.io.IOException.class, () -> new FingerprintSeedInitializer(vfs, store).run());
    }
}
