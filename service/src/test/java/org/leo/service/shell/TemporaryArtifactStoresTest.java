package org.leo.service.shell;

import org.junit.jupiter.api.Test;
import org.leo.jmg.generation.CoreArtifact;

import static org.junit.jupiter.api.Assertions.*;

class TemporaryArtifactStoresTest {

    @Test
    void rejectsEmptyInputsAndTreatsBlankOrUnknownIdsAsMisses() {
        CoreArtifactStore cores = new CoreArtifactStore();
        WebShellWrapperTemplateStore templates = new WebShellWrapperTemplateStore();
        assertThrows(IllegalArgumentException.class, () -> cores.put(null));
        assertThrows(IllegalArgumentException.class, () -> templates.put(null, "fixture", "fixture"));
        assertThrows(IllegalArgumentException.class, () -> templates.put("  ", "fixture", "fixture"));
        for (String id : new String[]{null, "", "  ", "missing"}) {
            assertNull(cores.get(id));
            assertNull(templates.get(id));
        }
    }

    @Test
    void storesKeepTypedDataAndIndependent256EntryInsertionBounds() {
        CoreArtifactStore cores = new CoreArtifactStore();
        WebShellWrapperTemplateStore templates = new WebShellWrapperTemplateStore();
        CoreArtifact artifact = new CoreArtifact("fixture.Artifact", new byte[]{1}, null, null, null, 0);
        String firstCore = cores.put(artifact);
        String firstTemplate = templates.put("first", "fixture", "fixture");
        for (int i = 1; i < 256; i++) {
            cores.put(artifact);
            templates.put("template-" + i, "fixture", "fixture");
        }
        assertSame(artifact, cores.get(" " + firstCore + " "));
        assertNotNull(templates.get(firstTemplate));
        String lastCore = cores.put(artifact);
        String lastTemplate = templates.put("  template  ", "fixture-type", "fixture-protocol");
        assertNull(cores.get(firstCore));
        assertNull(templates.get(firstTemplate));
        assertSame(artifact, cores.get(lastCore));
        var entry = templates.get(" " + lastTemplate + " ");
        assertEquals("  template  ", entry.getTemplate());
        assertEquals("fixture-type", entry.getArtifactType());
        assertEquals("fixture-protocol", entry.getProtocol());
        assertNull(templates.get(lastCore));
        assertNull(cores.get(lastTemplate));
        assertNull(new CoreArtifactStore().get(lastCore));
        assertNull(new WebShellWrapperTemplateStore().get(lastTemplate));
    }
}
