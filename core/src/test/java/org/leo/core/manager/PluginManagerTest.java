package org.leo.core.manager;

import org.junit.jupiter.api.Test;
import org.leo.core.config.LeoConfig;
import org.leo.core.entity.Plugin;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class PluginManagerTest {

    @Test
    void requiresExplicitRuntimeWhenRegisteringPlugins() {
        PluginManager manager = PluginManager.getInstance();
        Plugin javaPlugin = plugin("test-java-runtime", "java", null);
        Plugin phpPlugin = plugin("test-php-runtime", "php", null);
        Plugin explicitPlugin = plugin("test-explicit-runtime", "java", " PHP ");

        try {
            assertThrows(IllegalStateException.class, () -> manager.installPlugin(javaPlugin));
            assertThrows(IllegalStateException.class, () -> manager.installPlugin(phpPlugin));
            manager.installPlugin(explicitPlugin);

            assertNull(manager.getPluginById(javaPlugin.getPluginId()));
            assertNull(manager.getPluginById(phpPlugin.getPluginId()));
            assertEquals("php", explicitPlugin.getRuntime());
        } finally {
            manager.unload(javaPlugin.getPluginId());
            manager.unload(phpPlugin.getPluginId());
            manager.unload(explicitPlugin.getPluginId());
        }
    }

    @Test
    void loadsBundledPluginsWithCurrentRuntimeMetadata() {
        PluginManager manager = PluginManager.getInstance();
        Path pluginDirectory = Path.of("").toAbsolutePath();
        while (pluginDirectory != null
                && !Files.isDirectory(pluginDirectory.resolve("root/plugin"))) {
            pluginDirectory = pluginDirectory.getParent();
        }
        assertNotNull(pluginDirectory);
        Path directory = pluginDirectory.resolve("root/plugin");
        try (Stream<Path> files = Files.list(directory)) {
            assertEquals(4, files.count());
        } catch (Exception error) {
            throw new AssertionError(error);
        }
        manager.init(directory.toString(),
                LeoConfig.DEFAULT_PLUGIN_ENCRYPT_KEY);

        try {
            assertEquals(4, manager.getPluginAsList().size());
            assertTrue(manager.getPluginAsList().stream()
                    .allMatch(plugin -> "java".equals(plugin.resolveRuntime())));
        } finally {
            manager.getPluginAsList().forEach(plugin -> manager.unload(plugin.getPluginId()));
        }
    }

    private static Plugin plugin(String id, String type, String runtime) {
        Plugin plugin = new Plugin();
        plugin.setPluginId(id);
        plugin.setPluginType(type);
        plugin.setRuntime(runtime);
        return plugin;
    }
}
