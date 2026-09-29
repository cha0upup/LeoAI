package org.leo.ai.tools.platform;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.leo.core.config.LeoConfig;
import org.leo.service.fingerprint.FingerprintManageService;
import org.leo.service.fingerprint.FingerprintManageService.FingerprintNotFoundException;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mockStatic;

class FingerprintToolsTest {
    @TempDir Path directory;

    @Test
    void savesAndDeletesUsingCurrentFingerprintContract() throws Exception {
        FingerprintManageService service = new FingerprintManageService();
        FingerprintTools tools = new FingerprintTools(service);
        String rule = "{\"requests\":[{\"uri\":\"/\"}],\"match\":{\"field\":\"body\",\"value\":\"marker\"}}";

        try (var config = mockStatic(LeoConfig.class)) {
            config.when(LeoConfig::getVfsPath).thenReturn(directory.toString());
            Map<String, Object> saved = tools.saveFingerprint("tester", "demo", rule,
                    "{\"version\":\"2.0\",\"author\":\"tester\"}", "[\"web\"]");
            assertEquals("demo_2_0", saved.get("fingerprintId"));
            Map<String, Object> content = service.getFingerprintById("demo_2_0");
            assertEquals("2.0", ((Map<?, ?>) content.get("info")).get("version"));
            assertEquals("http", content.get("protocol"));

            assertThrows(IllegalArgumentException.class,
                    () -> tools.saveFingerprint("tester", "demo", rule, "{}", null));
            assertEquals("deleted", tools.deleteFingerprint("tester", "demo_2_0").get("status"));
            assertThrows(FingerprintNotFoundException.class, () -> service.getFingerprintById("demo_2_0"));
        }
    }
}
