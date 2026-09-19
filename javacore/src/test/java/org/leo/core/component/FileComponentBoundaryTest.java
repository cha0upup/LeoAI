package org.leo.core.component;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.leo.core.component.ComponentTestSupport.runComponent;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class FileComponentBoundaryTest {
    @TempDir Path directory;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void listsEmptyAndPopulatedDirectoriesWithTransportActions(boolean transformed) throws Exception {
        Map<String, Object> empty = invoke(transformed, "list", directory, Map.of());
        assertEquals(200, empty.get("code"));
        assertEquals(0, empty.get("count"));
        Files.writeString(directory.resolve("sample.txt"), "sample");
        Map<String, Object> result = runComponent("FileComponent", transformed,
                Map.of("action", "list", "path", directory.toString()));
        assertEquals(200, result.get("code"));
        assertEquals(1, result.get("count"));
        assertEquals("sample.txt", ((Map<?, ?>) ((List<?>) result.get("fileList")).get(0)).get("name"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void copyAndMovePreserveAllConflictStrategies(boolean transformed) throws Exception {
        for (String action : List.of("copy", "move")) {
            for (String strategy : List.of("overwrite", "autorename", "skip")) {
                Path scope = Files.createDirectory(directory.resolve(action + "-" + strategy));
                Path source = Files.writeString(scope.resolve("source.txt"), "new");
                Path target = Files.writeString(scope.resolve("target.txt"), "old");
                Map<String, Object> result = transfer(transformed, action, source, target, strategy);

                assertEquals(200, result.get("code"), result.toString());
                Path resolved = "autorename".equals(strategy) ? scope.resolve("target (1).txt") : target;
                assertEquals(resolved.toString(), result.get("newPath"));
                assertEquals("skip".equals(strategy) ? "old" : "new", Files.readString(resolved));
                if (!"overwrite".equals(strategy)) assertEquals("old", Files.readString(target));
                assertEquals("copy".equals(action) || "skip".equals(strategy), Files.exists(source));
                if ("skip".equals(strategy)) assertEquals(true, result.get("skipped"));
                if ("copy".equals(action) && !"skip".equals(strategy)) assertEquals(3L, result.get("size"));
                assertNoBackups(scope);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void transferCreatesParentsAndRejectsInvalidStrategiesAndDirectoryOverwrite(boolean transformed) throws Exception {
        for (String action : List.of("copy", "move")) {
            Path scope = directory.resolve(action);
            Path source = Files.writeString(directory.resolve(action + ".txt"), "value");
            Path target = scope.resolve("nested/target.txt");
            assertEquals(500, transfer(transformed, action, source, target, "invalid").get("code"));
            assertFalse(Files.exists(scope));
            assertEquals(200, transfer(transformed, action, source, target, "overwrite").get("code"));
            assertEquals("value", Files.readString(target));

            Path occupied = Files.createDirectory(scope.resolve("occupied"));
            Files.writeString(occupied.resolve("keep.txt"), "keep");
            Map<String, Object> rejected = transfer(transformed, action, target, occupied, "overwrite");
            assertEquals(500, rejected.get("code"));
            assertEquals("cannot overwrite directory: " + occupied, rejected.get("msg"));
            assertEquals("value", Files.readString(target));
            assertEquals("keep", Files.readString(occupied.resolve("keep.txt")));
            assertNoBackups(scope);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void failedCopyRestoresTargetAfterItWasBackedUp(boolean transformed) throws Exception {
        assumeFalse(System.getProperty("os.name").toLowerCase().contains("win"));
        Path target = Files.writeString(directory.resolve("target.txt"), "original");
        // Moving the target to its backup makes this previously readable source dangling.
        Path source = Files.createSymbolicLink(directory.resolve("source-link"), target);
        Map<String, Object> result = transfer(transformed, "copy", source, target, "overwrite");

        assertEquals(500, result.get("code"));
        assertEquals("original", Files.readString(target));
        assertEquals("original", Files.readString(source));
        assertTrue(Files.isSymbolicLink(source));
        assertNoBackups(directory);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void failedSourceDeletionRollsBackMove(boolean transformed) throws Exception {
        assumeTrue(Files.getFileStore(directory).supportsFileAttributeView("posix"));
        Path sourceParent = Files.createDirectory(directory.resolve("source-parent"));
        Path source = Files.writeString(sourceParent.resolve("source.txt"), "new");
        Path target = Files.writeString(directory.resolve("target.txt"), "old");
        var permissions = Files.getPosixFilePermissions(sourceParent);
        try {
            Files.setPosixFilePermissions(sourceParent, PosixFilePermissions.fromString("r-x------"));
            assumeFalse(Files.isWritable(sourceParent), "requires enforced directory permissions");
            Map<String, Object> result = transfer(transformed, "move", source, target, "overwrite");

            assertEquals(500, result.get("code"));
            assertEquals("move rollback: source delete failed: " + source, result.get("msg"));
            assertEquals("new", Files.readString(source));
            assertEquals("old", Files.readString(target));
            assertNoBackups(directory);
        } finally {
            Files.setPosixFilePermissions(sourceParent, permissions);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void createAndEditKeepDistinctNullEmptyAndOverwriteSemantics(boolean transformed) throws Exception {
        Path file = directory.resolve("nested/file.txt");
        Map<String, Object> created = invoke(transformed, "createFile", file, Map.of());
        assertEquals(200, created.get("code"));
        assertFalse(created.containsKey("size"));
        assertEquals(0L, Files.size(file));

        byte[] content = "中文😀".getBytes(StandardCharsets.UTF_8);
        assertEquals(500, invoke(transformed, "createFile", file, Map.of("content", content)).get("code"));
        Map<String, Object> edited = invoke(transformed, "edit", file, Map.of("content", content));
        assertEquals(200, edited.get("code"));
        assertEquals(content.length, edited.get("size"));
        assertArrayEquals(content, Files.readAllBytes(file));
        assertEquals(500, invoke(transformed, "edit", file, Map.of()).get("code"));
        assertArrayEquals(content, Files.readAllBytes(file));
        assertEquals(200, invoke(transformed, "edit", file, Map.of("content", new byte[0])).get("code"));
        assertEquals(0L, Files.size(file));

        Path newFile = directory.resolve("another/created.txt");
        assertEquals(200, invoke(transformed, "createFile", newFile, Map.of("content", content)).get("code"));
        assertArrayEquals(content, Files.readAllBytes(newFile));
        Path editedNewFile = directory.resolve("edit-parent/created.txt");
        assertEquals(200, invoke(transformed, "edit", editedNewFile, Map.of("content", content)).get("code"));
        assertArrayEquals(content, Files.readAllBytes(editedNewFile));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void oversizedContentLeavesExistingFileAndMissingParentsUntouched(boolean transformed) throws Exception {
        byte[] content = new byte[50 * 1024 * 1024 + 1];
        Path existing = Files.writeString(directory.resolve("existing.txt"), "keep");
        Path missing = directory.resolve("missing/file.txt");
        assertEquals(500, invoke(transformed, "edit", existing, Map.of("content", content)).get("code"));
        assertEquals("keep", Files.readString(existing));
        assertEquals(500, invoke(transformed, "createFile", missing, Map.of("content", content)).get("code"));
        assertFalse(Files.exists(missing.getParent()));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void recursiveDeletionOnlyRemovesLinksInsideTheSelectedTree(boolean transformed) throws Exception {
        assumeFalse(System.getProperty("os.name").toLowerCase().contains("win"));
        Path outside = Files.createDirectory(directory.resolve("outside"));
        Path keep = Files.writeString(outside.resolve("keep.txt"), "keep");
        Path selected = Files.createDirectory(directory.resolve("selected"));
        Files.writeString(Files.createDirectory(selected.resolve("child")).resolve("remove.txt"), "remove");
        Files.createSymbolicLink(selected.resolve("directory-link"), outside);
        Files.createSymbolicLink(selected.resolve("file-link"), keep);
        Files.createSymbolicLink(selected.resolve("broken-link"), directory.resolve("missing"));

        assertEquals(200, invoke(transformed, "delete", selected, Map.of()).get("code"));
        assertFalse(Files.exists(selected));
        assertEquals("keep", Files.readString(keep));
        Path directLink = Files.createSymbolicLink(directory.resolve("direct-link"), outside);
        assertEquals(200, invoke(transformed, "delete", directLink, Map.of()).get("code"));
        assertFalse(Files.exists(directLink, LinkOption.NOFOLLOW_LINKS));
        assertEquals("keep", Files.readString(keep));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void recursiveDeletionReportsTheDepthBoundary(boolean transformed) throws Exception {
        Path selected = Files.createDirectory(directory.resolve("selected"));
        Path nested = selected;
        for (int depth = 0; depth < 52; depth++) nested = Files.createDirectory(nested.resolve("d"));
        Path keep = Files.writeString(nested.resolve("keep.txt"), "keep");
        Map<String, Object> result = invoke(transformed, "delete", selected, Map.of());

        assertEquals(500, result.get("code"));
        List<?> failures = (List<?>) result.get("failedFiles");
        assertTrue(failures.stream().anyMatch(path -> path.toString().contains("max depth exceeded")));
        assertEquals(failures.size(), result.get("failedCount"));
        assertEquals("keep", Files.readString(keep));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void listingReflectsExecutablePermissions(boolean transformed) throws Exception {
        assumeTrue(Files.getFileStore(directory).supportsFileAttributeView("posix"));
        Path file = Files.writeString(directory.resolve("script.sh"), "echo test");
        for (String mode : List.of("rwx------", "rw-------")) {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString(mode));
            Map<String, Object> result = invoke(transformed, "list", directory, Map.of());
            assertEquals(200, result.get("code"));
            Map<?, ?> entry = (Map<?, ?>) ((List<?>) result.get("fileList")).get(0);
            assertEquals(Files.isExecutable(file), entry.get("canExecute"));
        }
    }

    private Map<String, Object> transfer(boolean transformed, String action, Path source, Path target, String strategy)
            throws Exception {
        return invoke(transformed, action, source, Map.of("copy".equals(action) ? "destPath" : "newPath",
                target.toString().getBytes(StandardCharsets.UTF_8),
                "conflictStrategy", strategy.getBytes(StandardCharsets.UTF_8)));
    }

    private void assertNoBackups(Path scope) throws Exception {
        try (var files = Files.list(scope)) {
            assertFalse(files.anyMatch(path -> path.getFileName().toString().contains(".leo-backup-")));
        }
    }

    private Map<String, Object> invoke(boolean transformed, String action, Path path, Map<String, Object> options)
            throws Exception {
        HashMap<String, Object> params = new HashMap<>(options);
        params.put("action", action.getBytes(StandardCharsets.UTF_8));
        params.put("path", path.toString().getBytes(StandardCharsets.UTF_8));
        return runComponent("FileComponent", transformed, params);
    }
}
