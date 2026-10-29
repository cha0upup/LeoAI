package org.leo.core.init;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.leo.core.config.LeoConfig;

import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class VFSInitializerTest {
    @TempDir Path temp;

    private VFSInitializer initializer(Path root) {
        LeoConfig config = mock(LeoConfig.class);
        when(config.getConfiguredVfsPath()).thenReturn(root.toString());
        return new VFSInitializer(config);
    }

    @Test
    void createsMissingParentsAndChecksActualWritesWithoutLeavingProbeFiles() throws Exception {
        Path root = temp.resolve("data/root");
        var init = initializer(root);
        init.init();
        Files.writeString(root.resolve("user.txt"), "keep");
        init.init();
        assertEquals(root, init.getRoot());
        assertEquals("keep", Files.readString(root.resolve("user.txt")));
        try (var files = Files.list(root)) { assertEquals(1, files.count()); }
    }

    @Test
    void concurrentDirectoryCreationIsIdempotent() throws Exception {
        Path root = temp.resolve("data/root");
        var pool = Executors.newFixedThreadPool(6);
        try {
            var results = new ArrayList<Future<?>>();
            for (int i = 0; i < 12; i++) results.add(pool.submit(() -> initializer(root).init()));
            for (var result : results) result.get(10, TimeUnit.SECONDS);
        } finally { pool.shutdownNow(); }
        try (var files = Files.list(root)) { assertEquals(0, files.count()); }
    }

    @Test
    void aFileAtTheRootPathFailsInsteadOfSelectingAnotherDirectory() throws Exception {
        Path root = temp.resolve("root");
        Files.writeString(root, "keep");
        assertThrows(IllegalStateException.class, () -> initializer(root).init());
        assertEquals("keep", Files.readString(root));
    }

    @Test
    void failedWriteProbeStopsInitializationWithTheOriginalCause() {
        var denied = new AccessDeniedException(temp.toString());
        try (var files = mockStatic(Files.class, CALLS_REAL_METHODS)) {
            files.when(() -> Files.createTempFile(temp, ".leo-write-check-", ".tmp")).thenThrow(denied);
            var error = assertThrows(IllegalStateException.class, () -> initializer(temp).init());
            assertSame(denied, error.getCause());
        }
    }
}
