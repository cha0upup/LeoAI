package org.leo.core.component;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import static org.leo.core.component.ComponentTestSupport.setField;

class FileComponentTest {

    @TempDir
    Path tempDir;

    @Test
    void listsFilesWithUnifiedStringAction() throws Exception {
        Files.writeString(tempDir.resolve("sample.txt"), "sample", StandardCharsets.UTF_8);

        HashMap results = invoke("list");

        assertEquals(200, results.get("code"));
        assertEquals(1, results.get("count"));
    }

    @Test
    void listsFilesWhenActionArrivesAsUtf8Bytes() throws Exception {
        HashMap results = invoke("list".getBytes(StandardCharsets.UTF_8));

        assertEquals(200, results.get("code"));
        assertEquals(0, results.get("count"));
    }

    @Test
    void returnsExplicitFileSystemProfile() throws Exception {
        HashMap results = invoke("profile");

        assertEquals(200, results.get("code"));
        assertEquals(FileSystems.getDefault().getSeparator(), results.get("separator"));
    }

    @Test
    void overwritesDestinationThroughRollbackCapableMove() throws Exception {
        Path source = tempDir.resolve("source.part");
        Path destination = tempDir.resolve("destination.txt");
        Files.writeString(source, "new-value", StandardCharsets.UTF_8);
        Files.writeString(destination, "old-value", StandardCharsets.UTF_8);

        HashMap results = invoke("move", Map.of(
                "path", source.toString().getBytes(StandardCharsets.UTF_8),
                "newPath", destination.toString().getBytes(StandardCharsets.UTF_8),
                "conflictStrategy", "overwrite".getBytes(StandardCharsets.UTF_8)
        ));

        assertEquals(200, results.get("code"));
        assertEquals("new-value", Files.readString(destination, StandardCharsets.UTF_8));
        assertFalse(Files.exists(source));
        try (var entries = Files.list(tempDir)) {
            assertEquals(0L, entries.filter(path -> path.getFileName().toString().contains(".leo-backup-")).count());
        }
    }

    private HashMap invoke(Object action) throws Exception {
        return invoke(action, Map.of(
                "path", tempDir.toString().getBytes(StandardCharsets.UTF_8)
        ));
    }

    private HashMap invoke(Object action, Map<String, Object> values) throws Exception {
        FileComponent component = new FileComponent();
        HashMap params = new HashMap();
        params.put("action", action);
        params.putAll(values);
        HashMap results = new HashMap();
        setField(component, "params", params);
        setField(component, "results", results);
        component.invoke();
        return results;
    }
}
