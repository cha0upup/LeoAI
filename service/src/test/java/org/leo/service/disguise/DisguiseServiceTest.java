package org.leo.service.disguise;

import org.junit.jupiter.api.Test;
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
import static org.mockito.Mockito.mockStatic;

class DisguiseServiceTest {
    @TempDir Path directory;
    private final DisguiseManager manager = DisguiseManager.getInstance();
    private final DisguiseService service = new DisguiseService(manager, List.of());

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
        Map<?, ?> source = (Map<?, ?>) JsonUtil.fromJsonString(old.toString(), Map.class);
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
