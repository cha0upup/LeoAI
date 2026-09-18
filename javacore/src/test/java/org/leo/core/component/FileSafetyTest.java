package org.leo.core.component;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;

class FileSafetyTest {
    @TempDir Path directory;

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void permissionFallbackPreservesExactOwnerOnlyMode(boolean payload) throws Exception {
        Assumptions.assumeTrue(Files.getFileStore(directory).supportsFileAttributeView("posix"));
        Path target = Files.writeString(directory.resolve("private.txt"), "private");
        Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("rw-------"));
        Object component = component("FileEnhanceComponent", payload);
        Method fallback = component.getClass().getDeclaredMethod("applyChmodJava", File.class, String.class, boolean.class, int.class);
        fallback.setAccessible(true);
        assertEquals(true, fallback.invoke(component, target.toFile(), "600", false, 0));
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(target)));
        assertEquals(false, fallback.invoke(component, target.toFile(), "4755", false, 0));
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(target)));
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void recursiveTouchDoesNotFollowDirectoryOrFileLinks(boolean payload) throws Exception {
        Assumptions.assumeFalse(System.getProperty("os.name").toLowerCase().contains("windows"));
        Path scope = Files.createDirectory(directory.resolve("scope"));
        Path outside = Files.createDirectory(directory.resolve("outside"));
        Path target = Files.writeString(outside.resolve("keep.txt"), "keep");
        FileTime original = FileTime.fromMillis(1600000000000L);
        Files.setLastModifiedTime(target, original);
        Files.createSymbolicLink(scope.resolve("directory-link"), outside);
        Files.createSymbolicLink(scope.resolve("file-link"), target);
        Map<String, Object> result = invoke(component("FileEnhanceComponent", payload), Map.of(
                "action", 2, "path", scope.toString(), "recursive", true, "time", "2020-01-02 03:04:05"));
        assertEquals(200, result.get("code"));
        assertEquals(original, Files.getLastModifiedTime(target));
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void failedCompressionPreservesExistingArchiveAndCleansTemporaryFiles(boolean payload) throws Exception {
        Assumptions.assumeFalse(System.getProperty("os.name").toLowerCase().contains("windows"));
        Path source = Files.createDirectory(directory.resolve("source"));
        Files.createSymbolicLink(source.resolve("broken"), source.resolve("missing"));
        Path destination = Files.writeString(directory.resolve("existing.zip"), "original archive");
        Map<String, Object> result = invoke(component("CompressComponent", payload), Map.of(
                "src", utf8(source), "des", utf8(destination)));
        assertEquals(500, result.get("code"));
        assertEquals("original archive", Files.readString(destination));
        try (var files = Files.list(directory)) {
            assertFalse(files.anyMatch(path -> path.getFileName().toString().startsWith(".leo-compress-")));
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void compressionInsideSourceExcludesItsOwnOutputAndStagingFile(boolean payload) throws Exception {
        Path source = Files.createDirectory(directory.resolve("source"));
        Files.writeString(source.resolve("a.txt"), "hello");
        Path destination = Files.writeString(source.resolve("out.zip"), "old");
        boolean posix = Files.getFileStore(directory).supportsFileAttributeView("posix");
        if (posix) Files.setPosixFilePermissions(destination, PosixFilePermissions.fromString("rw-------"));
        Map<String, Object> result = invoke(component("CompressComponent", payload), Map.of(
                "src", utf8(source), "des", utf8(destination)));
        assertEquals(200, result.get("code"));
        if (posix) assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(destination)));
        try (ZipFile archive = new ZipFile(destination.toFile())) {
            assertEquals(2, archive.size());
            assertTrue(archive.getEntry("source/").isDirectory());
            assertNotNull(archive.getEntry("source/a.txt"));
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void compressionPreservesEmptyDirectoriesAndHonorsExclusions(boolean payload) throws Exception {
        Path source = Files.createDirectory(directory.resolve("source"));
        Path archivePath = directory.resolve("empty.zip");
        assertEquals(200, invoke(component("CompressComponent", payload), Map.of(
                "src", utf8(source), "des", utf8(archivePath))).get("code"));
        try (ZipFile archive = new ZipFile(archivePath.toFile())) {
            assertEquals(1, archive.size());
            assertTrue(archive.getEntry("source/").isDirectory());
        }
        Files.createDirectories(source.resolve("nested/empty"));
        Files.createDirectory(source.resolve("excluded"));
        Files.writeString(source.resolve("one.txt"), "one");
        assertEquals(200, invoke(component("CompressComponent", payload), Map.of(
                "src", utf8(source), "des", utf8(archivePath), "exclude", "excluded")).get("code"));
        try (ZipFile archive = new ZipFile(archivePath.toFile())) {
            assertEquals(4, archive.size());
            assertTrue(archive.getEntry("source/nested/empty/").isDirectory());
            assertNull(archive.getEntry("source/excluded/"));
            assertNotNull(archive.getEntry("source/one.txt"));
        }
        Path output = directory.resolve("unpacked");
        assertEquals(200, invoke(component("DecompressComponent", payload), Map.of(
                "src", archivePath.toString(), "des", output.toString(), "format", "zip")).get("code"));
        assertTrue(Files.isDirectory(output.resolve("source/nested/empty")));
        assertEquals("one", Files.readString(output.resolve("source/one.txt")));
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void profileAdvertisesOnlyImplementedFileCapabilities(boolean payload) throws Exception {
        Map<String, Object> result = invoke(component("FileComponent", payload), Map.of("action", "profile"));
        Map<?, ?> capabilities = (Map<?, ?>) result.get("capabilities");
        assertEquals(Boolean.TRUE, capabilities.get("rename"));
        assertEquals(Boolean.FALSE, capabilities.get("copyDirectory"));
        assertEquals(1048576, capabilities.get("maxUploadChunkBytes"));
    }

    private byte[] utf8(Path path) { return path.toString().getBytes(StandardCharsets.UTF_8); }

    private Object component(String name, boolean payload) throws Exception {
        if (!payload) return Class.forName("org.leo.core.component." + name).getDeclaredConstructor().newInstance();
        try (var input = getClass().getResourceAsStream("/component/" + name + ".payload")) {
            assertNotNull(input);
            return new BytecodeLoader().load(input.readAllBytes()).getDeclaredConstructor().newInstance();
        }
    }
    private Map<String, Object> invoke(Object component, Map<String, Object> params) throws Exception {
        HashMap<String, Object> results = new HashMap<>();
        for (String name : new String[]{"params", "results"}) {
            Field field = component.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.set(component, name.equals("params") ? new HashMap<>(params) : results);
        }
        component.getClass().getMethod("invoke").invoke(component);
        return results;
    }
    private static class BytecodeLoader extends ClassLoader {
        Class<?> load(byte[] data) { return defineClass(null, data, 0, data.length); }
    }
}
