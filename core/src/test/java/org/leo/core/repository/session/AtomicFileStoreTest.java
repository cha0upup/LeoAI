package org.leo.core.repository.session;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AtomicFileStoreTest {
    @TempDir Path root;
    private final AtomicFileStore store = new AtomicFileStore();

    @Test
    void replacesTextAndReadsJsonWithoutChangingWhitespace() throws Exception {
        Path target = root.resolve("nested/state.json");
        store.writeText(target.toFile(), "first");
        store.writeText(target.toFile(), "  second\n");
        assertEquals("  second\n", store.readText(target.toFile()));
        store.writeJson(target.toFile(), Map.of("hostId", "host-a"));
        assertEquals("host-a", store.readJsonMap(target.toFile()).get("hostId"));
        assertNull(store.readText(root.resolve("missing.txt").toFile()));
        assertNull(store.readJsonMap(root.resolve("missing.json").toFile()));
        store.writeText(target.toFile(), "");
        assertEquals("", store.readText(target.toFile()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "null", "[]", "123", "{\"incomplete\":"})
    void malformedOrNonObjectJsonIsNotReportedAsAnAbsentFile(String content) throws Exception {
        Path target = root.resolve("state.json");
        Files.writeString(target, content);
        FileStoreException error = assertThrows(FileStoreException.class, () -> store.readJsonMap(target.toFile()));
        assertEquals(FileStoreException.Reason.INVALID_CONTENT, error.getReason());
        assertEquals(target, error.getPath());
        assertEquals(content, Files.readString(target));
    }

    @Test
    void readFailuresRetainTheirCauseAndDoNotLookLikeMissingFiles() throws Exception {
        Path target = root.resolve("state.json");
        Files.writeString(target, "{}");
        var denied = new AccessDeniedException(target.toString());
        try (var files = mockStatic(Files.class, CALLS_REAL_METHODS)) {
            files.when(() -> Files.readString(eq(target), any(java.nio.charset.Charset.class))).thenThrow(denied);
            FileStoreException error = assertThrows(FileStoreException.class, () -> store.readJsonMap(target.toFile()));
            assertEquals(FileStoreException.Reason.READ_FAILED, error.getReason());
            assertSame(denied, error.getCause());
        }
        assertEquals(FileStoreException.Reason.READ_FAILED,
                assertThrows(FileStoreException.class, () -> store.readText(root.toFile())).getReason());
    }

    @Test
    void invalidUtf8IsReportedAsCorruptContent() throws Exception {
        Path target = root.resolve("state.txt");
        Files.write(target, new byte[]{(byte) 0xC3, (byte) 0x28});
        assertEquals(FileStoreException.Reason.INVALID_CONTENT,
                assertThrows(FileStoreException.class, () -> store.readText(target.toFile())).getReason());
    }

    @Test
    void malformedTextIsRejectedBeforeReplacingThePreviousFile() throws Exception {
        Path target = root.resolve("state.txt");
        store.writeText(target.toFile(), "original");
        assertThrows(java.nio.charset.CharacterCodingException.class,
                () -> store.writeText(target.toFile(), "broken-" + (char) 0xD800));
        assertEquals("original", store.readText(target.toFile()));
        try (var files = Files.list(root)) {
            assertEquals(List.of(target), files.toList());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void failedReplacementPreservesPreviousBytesAndCleansTemporaryFiles(boolean unsupported) throws Exception {
        Path target = root.resolve("state.json");
        Files.writeString(target, "previous content");
        IOException failure = unsupported ? new AtomicMoveNotSupportedException("temp", "target", "fixture")
                : new IOException("fixture replacement failure");
        try (var files = mockStatic(Files.class, CALLS_REAL_METHODS)) {
            files.when(() -> Files.move(any(Path.class), eq(target),
                    eq(StandardCopyOption.ATOMIC_MOVE), eq(StandardCopyOption.REPLACE_EXISTING))).thenThrow(failure);
            assertSame(failure, assertThrows(IOException.class, () -> store.writeText(target.toFile(), "new content")));
            files.verify(() -> Files.move(any(Path.class), eq(target),
                    eq(StandardCopyOption.ATOMIC_MOVE), eq(StandardCopyOption.REPLACE_EXISTING)), times(1));
        }
        assertEquals("previous content", Files.readString(target));
        try (var files = Files.list(root)) {
            assertEquals(List.of(target), files.toList());
        }
    }

    @Test
    @Timeout(30)
    void concurrentReadersOnlyObserveCompleteCommittedVersions() throws Exception {
        Path target = root.resolve("shared.txt");
        List<String> versions = java.util.stream.IntStream.range(0, 7)
                .mapToObj(n -> "version-" + n + "\n" + ("value-" + n + "中").repeat(1000)).toList();
        Set<String> completeVersions = Set.copyOf(versions);
        store.writeText(target.toFile(), versions.get(0));
        var executor = Executors.newFixedThreadPool(7);
        CountDownLatch start = new CountDownLatch(1);
        AtomicBoolean finished = new AtomicBoolean();
        AtomicInteger reads = new AtomicInteger();
        Future<?> reader = executor.submit(() -> {
            start.await();
            do {
                assertTrue(completeVersions.contains(store.readText(target.toFile())));
                reads.incrementAndGet();
            } while (!finished.get());
            return null;
        });
        List<Future<?>> writers = new ArrayList<>();
        for (int n = 1; n < versions.size(); n++) {
            String version = versions.get(n);
            writers.add(executor.submit(() -> {
                start.await();
                for (int attempt = 0; attempt < 12; attempt++) store.writeText(target.toFile(), version);
                return null;
            }));
        }
        try {
            start.countDown();
            for (Future<?> writer : writers) writer.get(20, TimeUnit.SECONDS);
        } finally {
            finished.set(true);
            executor.shutdown();
        }
        reader.get(5, TimeUnit.SECONDS);
        assertTrue(reads.get() > 0);
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        try (var files = Files.list(root)) {
            assertEquals(List.of(target), files.toList());
        }
    }
}
