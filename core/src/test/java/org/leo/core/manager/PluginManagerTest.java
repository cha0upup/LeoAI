package org.leo.core.manager;

import org.junit.jupiter.api.Test;
import org.leo.core.entity.Plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PluginManagerTest {

    @Test
    void normalizesRuntimeWhenRegisteringPlugins() {
        PluginManager manager = PluginManager.getInstance();
        Plugin javaPlugin = plugin("test-java-runtime", "java", null);
        Plugin phpPlugin = plugin("test-php-runtime", "php", null);
        Plugin explicitPlugin = plugin("test-explicit-runtime", "java", " PHP ");

        try {
            manager.inStallPlugin(javaPlugin);
            manager.inStallPlugin(phpPlugin);
            manager.inStallPlugin(explicitPlugin);

            assertEquals("java", javaPlugin.getRuntime());
            assertEquals("php", phpPlugin.getRuntime());
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
