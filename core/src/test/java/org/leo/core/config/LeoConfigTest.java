package org.leo.core.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LeoConfigTest {
    private final String previous = LeoConfig.getVfsPath();
    private final String previousKey = LeoConfig.getPluginEncryptKey();

    @AfterEach
    void restore() {
        ReflectionTestUtils.setField(LeoConfig.class, "VFS_PATH", previous);
        ReflectionTestUtils.setField(LeoConfig.class, "PLUGIN_ENCRYPT_KEY", previousKey);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" \t ", "root", "data/../root", "/tmp/leo/../fixture"})
    void allAccessorsUseOneNormalizedAbsolutePath(String configured) {
        LeoConfig config = new LeoConfig();
        ReflectionTestUtils.setField(config, "vfsPath", configured);
        config.init();
        String expected = Path.of(configured == null || configured.isBlank() ? "root" : configured)
                .toAbsolutePath().normalize().toString();
        assertEquals(expected, config.getConfiguredVfsPath());
        assertEquals(expected, LeoConfig.getVfsPath());
    }
}
