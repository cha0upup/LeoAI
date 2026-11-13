package org.leo.web.adapter.ai;

import org.junit.jupiter.api.Test;
import org.leo.jmg.generation.CoreArtifact;
import org.leo.service.shell.WebShellWrapperTemplateStore;
import org.leo.service.shell.ShellResultStore;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TemporaryArtifactStoresTest {

    @Test
    void rejectsEmptyInputsAndTreatsBlankOrUnknownIdsAsMisses() {
        CoreArtifactStore cores = new CoreArtifactStore();
        WebShellWrapperTemplateStore templates = new WebShellWrapperTemplateStore();
        ShellResultStore results = new ShellResultStore();
        assertThrows(IllegalArgumentException.class, () -> cores.put(null));
        assertThrows(IllegalArgumentException.class, () -> results.put(null, null));
        assertThrows(IllegalArgumentException.class, () -> templates.put(null, "fixture", "fixture"));
        assertThrows(IllegalArgumentException.class, () -> templates.put("  ", "fixture", "fixture"));
        for (String id : new String[]{null, "", "  ", "missing"}) {
            assertNull(cores.get(id));
            assertNull(templates.get(id));
            assertNull(results.get(id));
        }
    }

    @Test
    void storesKeepTypedDataAndIndependent256EntryInsertionBounds() {
        CoreArtifactStore cores = new CoreArtifactStore();
        WebShellWrapperTemplateStore templates = new WebShellWrapperTemplateStore();
        ShellResultStore results = new ShellResultStore();
        CoreArtifact artifact = new CoreArtifact("fixture.Artifact", new byte[]{1}, null, null, null, 0);
        String firstCore = cores.put(artifact);
        String firstTemplate = templates.put("first", "fixture", "fixture");
        String firstResult = results.put("first", null);
        for (int i = 1; i < 256; i++) {
            cores.put(artifact);
            templates.put("template-" + i, "fixture", "fixture");
            results.put("result-" + i, null);
        }
        assertSame(artifact, cores.get(" " + firstCore + " "));
        assertNotNull(templates.get(firstTemplate));
        assertNotNull(results.get(firstResult));
        String lastCore = cores.put(artifact);
        String lastTemplate = templates.put("  template  ", "fixture-type", "fixture-protocol");
        String lastResult = results.put("result", Map.of("protocol", "http"));
        assertNull(cores.get(firstCore));
        assertNull(templates.get(firstTemplate));
        assertNull(results.get(firstResult));
        assertEquals("result", results.get(" " + lastResult + " ").content());
        assertEquals(Map.of("protocol", "http"), results.get(lastResult).meta());
        assertSame(artifact, cores.get(lastCore));
        var entry = templates.get(" " + lastTemplate + " ");
        assertEquals("  template  ", entry.getTemplate());
        assertEquals("fixture-type", entry.getArtifactType());
        assertEquals("fixture-protocol", entry.getProtocol());
        assertNull(templates.get(lastCore));
        assertNull(cores.get(lastTemplate));
        assertNull(new CoreArtifactStore().get(lastCore));
        assertNull(new WebShellWrapperTemplateStore().get(lastTemplate));
        assertNull(results.get(lastCore));
        assertNull(new ShellResultStore().get(lastResult));
    }

    @Test
    void resultMetadataKeepsNullableValuesAndDoesNotAliasTheCallersMap() {
        ShellResultStore results = new ShellResultStore();
        Map<String, Object> meta = new HashMap<>();
        meta.put("protocol", "http");
        meta.put("optional", null);
        String id = results.put("content", meta);
        meta.put("protocol", "changed");

        var result = results.get(id);
        assertEquals("http", result.meta().get("protocol"));
        assertTrue(result.meta().containsKey("optional"));
        assertNull(result.meta().get("optional"));
        assertThrows(UnsupportedOperationException.class, () -> result.meta().put("protocol", "changed"));
    }
}
