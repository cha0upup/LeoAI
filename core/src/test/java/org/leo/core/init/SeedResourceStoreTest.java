package org.leo.core.init;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ByteArrayResource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.leo.core.init.SeedResourceStore.Result.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SeedResourceStoreTest {
    @TempDir Path root;
    private final SeedResourceStore store = new SeedResourceStore();
    private ByteArrayResource bytes(String value) { return new ByteArrayResource(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)); }

    @Test
    void managedResourcesUpdateOnlyWhenTheirContentChanges() throws Exception {
        assertEquals(CREATED, store.sync(root, "skills/fixture/file", bytes("first"), true));
        Path file = root.resolve("skills/fixture/file");
        Files.setLastModifiedTime(file, FileTime.fromMillis(1000));
        assertEquals(UNCHANGED, store.sync(root, "skills/fixture/file", bytes("first"), true));
        assertEquals(1000, Files.getLastModifiedTime(file).toMillis());
        assertEquals(UPDATED, store.sync(root, "skills/fixture/file", bytes("next"), true));
        assertEquals("next", Files.readString(file));
        assertNoTemps();
    }

    @Test
    void missingOnlyResourcesKeepAllExistingUserContent() throws Exception {
        assertEquals(CREATED, store.sync(root, "fingerprint/fixture.json", bytes("user content"), false));
        assertEquals(PRESERVED, store.sync(root, "fingerprint/fixture.json", bytes("built in"), false));
        assertEquals("user content", Files.readString(root.resolve("fingerprint/fixture.json")));
    }

    @Test
    void interruptedStreamsNeverPublishPartialFilesOrDestroyPreviousContent() throws Exception {
        var broken = new ByteArrayResource(new byte[0]) {
            @Override public InputStream getInputStream() {
                return new InputStream() { public int read() throws IOException { throw new IOException("fixture interruption"); } };
            }
        };
        Files.writeString(root.resolve("existing"), "keep");
        for (String name : List.of("missing", "existing")) {
            assertThrows(IOException.class, () -> store.sync(root, name, broken, true));
            assertNoTemps();
        }
        assertFalse(Files.exists(root.resolve("missing")));
        assertEquals("keep", Files.readString(root.resolve("existing")));
        assertEquals(CREATED, store.sync(root, "missing", bytes("retry"), true));
    }

    @Test
    void failedAtomicPublishLeavesThePreviousFileIntact() throws Exception {
        Files.writeString(root.resolve("existing"), "keep");
        try (var files = mockStatic(Files.class, CALLS_REAL_METHODS)) {
            files.when(() -> Files.move(any(Path.class), eq(root.resolve("existing")),
                    eq(StandardCopyOption.ATOMIC_MOVE), eq(StandardCopyOption.REPLACE_EXISTING)))
                    .thenThrow(new AtomicMoveNotSupportedException("fixture", "fixture", "fixture"));
            assertThrows(IOException.class, () -> store.sync(root, "existing", bytes("new"), true));
        }
        assertEquals("keep", Files.readString(root.resolve("existing")));
        assertNoTemps();
    }

    @Test
    void missingOnlyPublishNeverReplacesAFileCreatedDuringTheCopy() throws Exception {
        var racing = new ByteArrayResource("seed".getBytes(java.nio.charset.StandardCharsets.UTF_8)) {
            @Override public InputStream getInputStream() {
                try { Files.writeString(root.resolve("race"), "user wins"); }
                catch (IOException error) { throw new java.io.UncheckedIOException(error); }
                return new java.io.ByteArrayInputStream("seed".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
        };
        assertEquals(PRESERVED, store.sync(root, "race", racing, false));
        assertEquals("user wins", Files.readString(root.resolve("race")));
        assertNoTemps();
    }

    @Test
    void concurrentMissingOnlyCopiesHaveExactlyOnePublisher() throws Exception {
        var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> store.sync(root, "race", bytes("first"), false));
            var next = pool.submit(() -> store.sync(root, "race", bytes("second"), false));
            assertEquals(java.util.Set.of(CREATED, PRESERVED), java.util.Set.of(
                    first.get(10, TimeUnit.SECONDS), next.get(10, TimeUnit.SECONDS)));
        } finally { pool.shutdownNow(); }
        assertTrue(List.of("first", "second").contains(Files.readString(root.resolve("race"))));
        assertNoTemps();
    }

    @Test
    void pathsAndDirectorySymlinksStayInsideTheConfiguredRoot() throws Exception {
        assertThrows(IOException.class, () -> store.sync(root, "../outside", bytes("bad"), true));
        assertThrows(IOException.class, () -> store.sync(root, root.resolve("absolute").toString(), bytes("bad"), true));
        Path outside = Files.createTempDirectory(root.getParent(), "seed-boundary-");
        try {
            Files.createSymbolicLink(root.resolve("link"), outside);
            assertThrows(IOException.class, () -> store.sync(root, "link/nested/file", bytes("bad"), true));
            assertFalse(Files.exists(outside.resolve("nested")));
        } finally { Files.delete(outside); }
    }

    @Test
    void aShortReadIsRejectedEvenWhenItEndsWithoutAnIoException() throws Exception {
        var truncated = new ByteArrayResource(new byte[20]) {
            @Override public InputStream getInputStream() { return new java.io.ByteArrayInputStream(new byte[3]); }
        };
        Files.writeString(root.resolve("existing"), "keep");
        assertThrows(IOException.class, () -> store.sync(root, "existing", truncated, true));
        assertEquals("keep", Files.readString(root.resolve("existing")));
        assertNoTemps();
    }

    private void assertNoTemps() throws Exception {
        try (var files = Files.walk(root)) { assertTrue(files.noneMatch(p -> p.getFileName().toString().startsWith(".leo-seed-"))); }
    }
}
