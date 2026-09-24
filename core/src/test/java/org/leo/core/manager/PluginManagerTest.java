package org.leo.core.manager;

import org.junit.jupiter.api.Test;
import org.leo.core.entity.Plugin;

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

    private static Plugin plugin(String id, String type, String runtime) {
        Plugin plugin = new Plugin();
        plugin.setPluginId(id);
        plugin.setPluginType(type);
        plugin.setRuntime(runtime);
        return plugin;
    }
}
