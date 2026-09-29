package org.leo.core.manager;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.leo.core.disguise.JavaBuiltinDisguiseCatalog;
import org.leo.core.entity.Disguise;
import org.leo.core.util.aes.AesUtil;
import org.leo.core.util.json.JsonUtil;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class DisguiseManagerTest {
    @TempDir Path directory;

    @Test
    void loadsOnlyCurrentDisguiseDefinitions() throws Exception {
        String key = "0123456789abcdef";
        Disguise current = JavaBuiltinDisguiseCatalog.createPresets().get(0);
        String currentId = "current_" + UUID.randomUUID();
        String oldId = "old_" + UUID.randomUUID();
        current.setDisguiseId(currentId);
        writeProfile("current.disguise", current.toString(), key);

        Map<?, ?> source = (Map<?, ?>) JsonUtil.fromJsonString(current.toString(), Map.class);
        Map<Object, Object> old = new LinkedHashMap<>(source);
        old.put("disguiseId", oldId);
        old.remove("schemaVersion");
        writeProfile("old.disguise", JsonUtil.toJsonString(old), key);

        DisguiseManager manager = DisguiseManager.getInstance();
        try {
            manager.init(directory.toString(), key);
            assertNotNull(manager.getDisguiseById(currentId));
            assertNull(manager.getDisguiseById(oldId));
        } finally {
            manager.unload(currentId);
            manager.unload(oldId);
        }
    }

    private void writeProfile(String name, String json, String key) throws Exception {
        Files.writeString(directory.resolve(name), AesUtil.encrypt(json, key), StandardCharsets.UTF_8);
    }
}
