package org.leo.ai.tools.platform;

import org.junit.jupiter.api.Test;
import org.leo.core.entity.Plugin;
import org.leo.core.manager.PluginManager;

import java.util.Arrays;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PluginToolsTest {
    private final PluginManager manager = mock(PluginManager.class);
    private final PluginTools tools = new PluginTools(manager);

    private Plugin plugin() {
        Plugin plugin = new Plugin();
        plugin.setPluginId("fixture");
        plugin.setPluginName("Fixture plugin");
        plugin.setPluginType("java");
        plugin.setBytecode(new byte[]{1, 2, 3});
        return plugin;
    }

    @Test
    void listsMetadataWithoutBytecodeAndSkipsNullEntries() {
        when(manager.getPlugins()).thenReturn(Arrays.asList(null, plugin()));

        var rows = tools.listPlugins(null);

        assertEquals(1, rows.size());
        assertEquals("fixture", rows.get(0).get("pluginId"));
        assertEquals("Fixture plugin", rows.get(0).get("pluginName"));
        assertFalse(rows.get(0).containsKey("bytecodeBase64"));
        verify(manager).getPlugins();
    }

    @Test
    void filtersByTrimmedTypeAndHandlesAnEmptyCatalog() {
        when(manager.getPluginsByType("java")).thenReturn(List.of(plugin()));
        assertEquals(1, tools.listPlugins(" java ").size());
        verify(manager).getPluginsByType("java");
        when(manager.getPluginsByType("java")).thenReturn(null);
        assertTrue(tools.listPlugins("java").isEmpty());
    }

    @Test
    void returnsBytecodeInDetailsOnlyWhenExplicitlyRequested() {
        Plugin plugin = plugin();
        when(manager.getPluginById("fixture")).thenReturn(plugin);

        assertFalse(tools.getPluginById("fixture", null).containsKey("bytecodeBase64"));
        assertFalse(tools.getPluginById("fixture", false).containsKey("bytecodeBase64"));
        assertEquals(Base64.getEncoder().encodeToString(plugin.getBytecode()),
                tools.getPluginById("fixture", true).get("bytecodeBase64"));
    }
}
