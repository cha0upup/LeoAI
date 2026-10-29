package org.leo.service.disguise;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.leo.core.repository.session.AtomicFileStore;
import java.io.IOException;
import java.nio.file.Files;
import org.junit.jupiter.api.io.TempDir;
import org.leo.core.config.LeoConfig;
import org.leo.core.disguise.JavaBuiltinDisguiseCatalog;
import org.leo.core.entity.Disguise;
import org.leo.core.entity.User;
import org.leo.core.manager.DisguiseManager;
import org.leo.core.util.aes.AesUtil;
import org.leo.core.util.json.JsonUtil;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertFalse;

class DisguiseServiceTest {
    @TempDir Path directory;
    private final DisguiseManager manager = DisguiseManager.getInstance();
    private final DisguiseService service = new DisguiseService(manager, List.of());

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void failedUpdateOrOverwriteImportRetainsPreviousDiskAndRuntime(boolean importing) throws Exception {
        String key = "0123456789abcdef";
        Disguise original = fixture();
        String id = original.getDisguiseId();
        try (var config = mockStatic(LeoConfig.class)) {
            config.when(LeoConfig::getVfsPath).thenReturn(directory.toString());
            config.when(LeoConfig::getPluginEncryptKey).thenReturn(key);
            try {
                assertEquals("imported", service.importDisguises(upload(original, key),
                        DisguiseService.ConflictPolicy.SKIP, user()).get(0).status());
                Disguise installed = manager.getDisguiseById(id);
                Path target = directory.resolve("disguise/" + id + ".disguise");
                byte[] previous = Files.readAllBytes(target);
                AtomicFileStore failingStore = spy(new AtomicFileStore());
                doAnswer(invocation -> {
                    assertSame(installed, manager.getDisguiseById(id));
                    assertArrayEquals(previous, Files.readAllBytes(target));
                    throw new IOException("fixture write failure");
                }).when(failingStore).writeText(any(), anyString());
                DisguiseService failingService = new DisguiseService(manager, List.of(), failingStore);
                if (importing) {
                    original.setRemark("replacement");
                    var result = failingService.importDisguises(upload(original, key),
                            DisguiseService.ConflictPolicy.OVERWRITE, user());
                    assertEquals("failed", result.get(0).status());
                } else {
                    HashMap<String, Object> update = new HashMap<>();
                    update.put("disguiseId", id);
                    update.put("remark", "replacement");
                    assertThrows(IOException.class, () -> failingService.updateDisguise(update));
                }
                assertSame(installed, manager.getDisguiseById(id));
                assertEquals("original", installed.getRemark());
                assertArrayEquals(previous, Files.readAllBytes(target));
                verify(failingStore).writeText(any(), anyString());
            } finally {
                manager.unload(id);
            }
        }
    }

    @Test
    void firstImportFailureDoesNotPublishAnUnsavedInstance() throws Exception {
        Disguise definition = fixture();
        String id = definition.getDisguiseId();
        String key = "0123456789abcdef";
        AtomicFileStore failingStore = spy(new AtomicFileStore());
        doThrow(new IOException("fixture failure")).when(failingStore).writeText(any(), anyString());
        try (var config = mockStatic(LeoConfig.class)) {
            config.when(LeoConfig::getVfsPath).thenReturn(directory.toString());
            config.when(LeoConfig::getPluginEncryptKey).thenReturn(key);
            try {
                var result = new DisguiseService(manager, List.of(), failingStore).importDisguises(upload(definition, key),
                        DisguiseService.ConflictPolicy.SKIP, user());
                assertEquals("failed", result.get(0).status());
                assertNull(manager.getDisguiseById(id));
                assertFalse(Files.exists(directory.resolve("disguise/" + id + ".disguise")));
            } finally { manager.unload(id); }
        }
    }

    @Test
    void preparedDefinitionIsPublishedOnlyAfterTheFileReplacementSucceeds() throws Exception {
        Disguise definition = fixture();
        String id = definition.getDisguiseId();
        String key = "0123456789abcdef";
        try (var config = mockStatic(LeoConfig.class)) {
            config.when(LeoConfig::getVfsPath).thenReturn(directory.toString());
            config.when(LeoConfig::getPluginEncryptKey).thenReturn(key);
            try {
                service.importDisguises(upload(definition, key), DisguiseService.ConflictPolicy.SKIP, user());
                Disguise previous = manager.getDisguiseById(id);
                AtomicFileStore files = spy(new AtomicFileStore());
                doAnswer(invocation -> {
                    assertSame(previous, manager.getDisguiseById(id));
                    Object result = invocation.callRealMethod();
                    assertSame(previous, manager.getDisguiseById(id));
                    return result;
                }).when(files).writeText(any(), anyString());
                HashMap<String, Object> update = new HashMap<>();
                update.put("disguiseId", id);
                update.put("remark", "replacement");
                new DisguiseService(manager, List.of(), files).updateDisguise(update);
                assertNotSame(previous, manager.getDisguiseById(id));
                assertEquals("original", previous.getRemark());
                assertEquals("replacement", manager.getDisguiseById(id).getRemark());
                String saved = AesUtil.decrypt(Files.readString(directory.resolve("disguise/" + id + ".disguise")), key);
                assertEquals("replacement", (JsonUtil.fromJsonString(saved, Map.class)).get("remark"));
            } finally { manager.unload(id); }
        }
    }

    @Test
    void runtimePreparationFailureDoesNotCallPersistenceOrReplaceTheOldInstance() throws Exception {
        Disguise installed = fixture();
        String id = installed.getDisguiseId();
        assertTrue(manager.installDisguise(installed));
        Disguise candidate = spy(fixture());
        candidate.setDisguiseId(id);
        doThrow(new IOException("fixture preparation failure")).when(candidate).init();
        DisguiseManager.Persistence persistence = mock(DisguiseManager.Persistence.class);
        try {
            assertThrows(IOException.class, () -> manager.installDisguise(candidate, persistence));
            assertSame(installed, manager.getDisguiseById(id));
            verifyNoInteractions(persistence);
        } finally { manager.unload(id); }
    }

    @Test
    void renameImportsPreserveTheOriginalAndProduceDistinctIds() throws Exception {
        Disguise definition = fixture();
        String key = "0123456789abcdef";
        java.util.List<String> ids = new java.util.ArrayList<>();
        ids.add(definition.getDisguiseId());
        try (var config = mockStatic(LeoConfig.class)) {
            config.when(LeoConfig::getVfsPath).thenReturn(directory.toString());
            config.when(LeoConfig::getPluginEncryptKey).thenReturn(key);
            try {
                service.importDisguises(upload(definition, key), DisguiseService.ConflictPolicy.SKIP, user());
                for (int n = 0; n < 2; n++) {
                    var result = service.importDisguises(upload(definition, key), DisguiseService.ConflictPolicy.RENAME, user());
                    assertEquals("imported", result.get(0).status());
                    ids.add(result.get(0).disguiseId());
                }
                assertEquals(3, new java.util.HashSet<>(ids).size());
                assertEquals("original", manager.getDisguiseById(ids.get(0)).getRemark());
            } finally { ids.forEach(manager::unload); }
        }
    }

    private Disguise fixture() {
        Disguise definition = JavaBuiltinDisguiseCatalog.createPresets().get(0);
        definition.setDisguiseId("fixture_" + UUID.randomUUID());
        definition.setRemark("original");
        return definition;
    }

    @Test
    void skipAndRenameRespectAnExistingFileEvenWhenItWasNotInstalled() throws Exception {
        Disguise definition = fixture();
        String id = definition.getDisguiseId();
        String key = "0123456789abcdef";
        String renamedId = null;
        try (var config = mockStatic(LeoConfig.class)) {
            config.when(LeoConfig::getVfsPath).thenReturn(directory.toString());
            config.when(LeoConfig::getPluginEncryptKey).thenReturn(key);
            Path path = directory.resolve("disguise/" + id + ".disguise");
            Files.createDirectories(path.getParent());
            Files.writeString(path, "corrupt existing definition");
            try {
                assertNull(manager.getDisguiseById(id));
                assertEquals("skipped", service.importDisguises(upload(definition, key),
                        DisguiseService.ConflictPolicy.SKIP, user()).get(0).status());
                var result = service.importDisguises(upload(definition, key), DisguiseService.ConflictPolicy.RENAME, user());
                assertEquals("imported", result.get(0).status());
                renamedId = result.get(0).disguiseId();
                assertEquals("corrupt existing definition", Files.readString(path));
                assertNull(manager.getDisguiseById(id));
            } finally {
                manager.unload(id);
                manager.unload(renamedId);
            }
        }
    }

    private User user() {
        User user = new User();
        user.setUserId("tester");
        return user;
    }

    private MockMultipartFile upload(Disguise definition, String key) throws Exception {
        return new MockMultipartFile("file", "fixture.disguise", "application/octet-stream",
                AesUtil.encrypt(definition.toString(), key).getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void rejectsOldSaveMetadataAndCommaSeparatedRuntimes() throws Exception {
        HashMap<String, Object> params = new HashMap<>();
        params.put("disguiseName", "example");
        params.put("trafficEncodeBody", "public byte[] encodeTraffic(byte[] bytes) { return bytes; }");
        params.put("trafficDecodeBody", "public byte[] decodeTraffic(byte[] bytes) { return bytes; }");
        params.put("headers", "{}");
        params.put("supportedRuntimes", List.of("java"));
        assertThrows(IllegalArgumentException.class, () -> service.addDisguise(params, "tester"));

        params.put("schemaVersion", 3);
        params.put("protocolVersion", 3);
        params.put("supportedRuntimes", "java,php");
        assertThrows(IllegalArgumentException.class, () -> service.addDisguise(params, "tester"));
    }

    @Test
    void rejectsOldImportsBeforeInstallingThem() throws Exception {
        String key = "0123456789abcdef";
        String oldId = "old_" + UUID.randomUUID();
        Disguise old = JavaBuiltinDisguiseCatalog.createPresets().get(0);
        old.setDisguiseId(oldId);
        Map<?, ?> source = JsonUtil.fromJsonString(old.toString(), Map.class);
        Map<Object, Object> definition = new LinkedHashMap<>(source);
        definition.put("protocolVersion", 2);
        byte[] bytes = AesUtil.encrypt(JsonUtil.toJsonString(definition), key).getBytes(StandardCharsets.UTF_8);
        User user = new User();
        user.setUserId("tester");

        try (var config = mockStatic(LeoConfig.class)) {
            config.when(LeoConfig::getPluginEncryptKey).thenReturn(key);
            config.when(LeoConfig::getVfsPath).thenReturn(directory.toString());
            var result = service.importDisguises(new MockMultipartFile("file", "old.disguise",
                    "application/octet-stream", bytes), DisguiseService.ConflictPolicy.SKIP, user);
            assertEquals("failed", result.get(0).status());
            assertNull(manager.getDisguiseById(oldId));

            definition.put("protocolVersion", 3);
            byte[] currentBytes = AesUtil.encrypt(JsonUtil.toJsonString(definition), key)
                    .getBytes(StandardCharsets.UTF_8);
            try {
                var currentResult = service.importDisguises(new MockMultipartFile("file", "current.disguise",
                        "application/octet-stream", currentBytes), DisguiseService.ConflictPolicy.SKIP, user);
                assertEquals("imported", currentResult.get(0).status());
                assertEquals(3, manager.getDisguiseById(oldId).getProtocolVersion());
            } finally {
                manager.unload(oldId);
            }
        }
    }

    @Test
    void rejectedOldUpdateLeavesInstalledDefinitionIntact() throws Exception {
        Disguise current = JavaBuiltinDisguiseCatalog.createPresets().get(0);
        String id = "current_" + UUID.randomUUID();
        current.setDisguiseId(id);
        assertTrue(manager.installDisguise(current));
        try {
            HashMap<String, Object> params = new HashMap<>();
            params.put("disguiseId", id);
            params.put("protocolVersion", 2);
            assertThrows(IllegalArgumentException.class, () -> service.updateDisguise(params));
            assertEquals(3, manager.getDisguiseById(id).getProtocolVersion());
        } finally {
            manager.unload(id);
        }
    }
}
