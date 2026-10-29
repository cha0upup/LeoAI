package org.leo.web.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.leo.core.config.LeoConfig;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class RuntimeConfigurationTest {
    @TempDir Path temp;
    private final String previousVfsPath = LeoConfig.getVfsPath();
    private final String previousKey = LeoConfig.getPluginEncryptKey();

    @AfterEach
    void restore() {
        ReflectionTestUtils.setField(LeoConfig.class, "VFS_PATH", previousVfsPath);
        ReflectionTestUtils.setField(LeoConfig.class, "PLUGIN_ENCRYPT_KEY", previousKey);
    }

    @Test
    void webConfigurationResolvesThePluginKeyEnvironmentSetting() {
        runner().withPropertyValues("LEO_PLUGIN_ENCRYPT_KEY=fixture-environment-key")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    var config = context.getBean(LeoConfig.class);
                    assertEquals("fixture-environment-key", config.getConfiguredPluginEncryptKey());
                    assertEquals(config.getConfiguredPluginEncryptKey(), LeoConfig.getPluginEncryptKey());
                    assertEquals(temp.resolve("vfs").toString(), config.getConfiguredVfsPath());
                });
    }

    @Test
    void explicitPluginKeyOverridesTheEnvironmentSetting() {
        runner().withPropertyValues("LEO_PLUGIN_ENCRYPT_KEY=fixture-environment-key",
                        "PluginEncryptKey=fixture-explicit-key")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertEquals("fixture-explicit-key", context.getBean(LeoConfig.class).getConfiguredPluginEncryptKey());
                });
    }

    @Test
    void theApplicationContainsOnlyTheWebRuntimeConfiguration() throws Exception {
        var resources = java.util.Collections.list(getClass().getClassLoader().getResources("application.properties"));
        assertEquals(1, resources.size(), resources.toString());
    }

    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withUserConfiguration(LeoConfig.class)
                .withPropertyValues("VfsPath=" + temp.resolve("vfs"));
    }
}
