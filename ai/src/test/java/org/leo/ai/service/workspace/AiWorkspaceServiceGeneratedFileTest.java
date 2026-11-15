package org.leo.ai.service.workspace;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.leo.ai.config.AiWorkspaceProperties;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiWorkspaceServiceGeneratedFileTest {

    @TempDir
    Path tempDir;

    private AiWorkspaceService service;
    private AiWorkspaceService.Workspace workspace;

    @BeforeEach
    void setUp() {
        AiWorkspaceProperties properties = new AiWorkspaceProperties();
        service = new AiWorkspaceService(properties, tempDir::resolve);
        workspace = service.open("user1", "0123456789abcdef0123456789abcdef");
    }

    @Test
    void importsBinaryFileAtomicallyAndReusesIdenticalContent() throws Exception {
        byte[] bytes = new byte[]{0, 1, 2, 3, 4, (byte) 255};
        Path source = tempDir.resolve("source.sqlite");
        Files.write(source, bytes);

        Map<String, Object> first = service.writeGeneratedFile(
                workspace, "input/browser/history.sqlite", source);
        Map<String, Object> second = service.writeGeneratedFile(
                workspace, "input/browser/history.sqlite", source);

        assertTrue((Boolean) first.get("created"));
        assertFalse((Boolean) first.get("reused"));
        assertFalse((Boolean) second.get("created"));
        assertTrue((Boolean) second.get("reused"));
        assertEquals(first.get("sha256"), second.get("sha256"));
        assertArrayEquals(bytes, Files.readAllBytes(
                workspace.filesRoot().resolve("input/browser/history.sqlite")));
    }

    @Test
    void rejectsDifferentContentAtExistingGeneratedPath() throws Exception {
        Path first = tempDir.resolve("first.bin");
        Path second = tempDir.resolve("second.bin");
        Files.write(first, new byte[]{1, 2, 3});
        Files.write(second, new byte[]{4, 5, 6});
        service.writeGeneratedFile(workspace, "input/item.bin", first);

        assertThrows(IllegalArgumentException.class,
                () -> service.writeGeneratedFile(workspace, "input/item.bin", second));
    }

    @Test
    void preflightRejectsExistingTarget() throws Exception {
        Path source = tempDir.resolve("source.txt");
        Files.writeString(source, "artifact");
        service.writeGeneratedFile(workspace, "input/item.txt", source);

        assertThrows(IllegalArgumentException.class,
                () -> service.validateGeneratedFileTarget(
                        workspace, "input/item.txt", Files.size(source)));
    }
}
