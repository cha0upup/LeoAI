package org.leo.service.concurrent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.leo.core.puppet.capability.FileCapable;
import org.leo.service.UploadEngineService;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class UploadEngineServiceTest {
    @TempDir Path directory;
    private final ServiceTaskExecutor executor = new ServiceTaskExecutor(1, 1, 1, 4, 1, 1);
    private final UploadEngineService service = new UploadEngineService(executor);
    private final FileCapable node = mock(FileCapable.class);
    private final CountDownLatch entered = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);
    private Path source;

    @BeforeEach
    void prepare() throws Exception {
        source = Files.writeString(directory.resolve("source.txt"), "hello");
        when(node.createFile(anyString(), anyString())).thenReturn(Map.of("code", 200));
        when(node.fileUploadChunk(anyString(), anyLong(), any())).thenReturn(Map.of("code", 200));
        when(node.getFileMD5(anyString())).thenReturn(checksum());
        when(node.moveFile(anyString(), anyString(), anyString())).thenReturn(Map.of("code", 200));
    }

    @AfterEach
    void close() throws Exception {
        release.countDown();
        try { drain(); } finally { service.close(); executor.close(); }
    }

    @Test
    void cancellationDuringRemoteChecksumNeverCommits() throws Exception {
        when(node.getFileMD5(anyString())).thenAnswer(call -> { holdRemoteCall(); return checksum(); });
        String id = start();
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        assertEquals("CANCELLED", service.cancel("user", id).get("state"));
        release.countDown(); drain();
        verify(node, never()).moveFile(anyString(), anyString(), anyString());
        verify(node).deleteFile(anyString());
        assertEquals("CANCELLED", service.progress("user", id).get("state"));
    }

    @Test
    void commitRejectsPauseAndCancelUntilItsResultIsKnown() throws Exception {
        when(node.moveFile(anyString(), anyString(), anyString())).thenAnswer(call -> {
            holdRemoteCall(); return Map.of("code", 200);
        });
        String id = start();
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        assertEquals("COMMITTING", service.progress("user", id).get("currentStage"));
        assertThrows(IllegalStateException.class, () -> service.cancel("user", id));
        assertThrows(IllegalStateException.class, () -> service.pause("user", id));
        assertEquals("RUNNING", service.progress("user", id).get("state"));
        release.countDown(); drain();
        assertEquals("COMPLETED", service.progress("user", id).get("state"));
        verify(node, never()).deleteFile(anyString());
    }

    @Test
    void resumeWaitsForInFlightWriteThenUsesItsAcknowledgedOffset() throws Exception {
        when(node.fileUploadChunk(anyString(), anyLong(), any())).thenAnswer(call -> {
            holdRemoteCall(); return Map.of("code", 200);
        });
        String id = start();
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        assertEquals("PAUSED", service.pause("user", id).get("state"));
        assertThrows(IllegalStateException.class, () -> service.resume(node, "user", "session", id));
        release.countDown(); drain();
        assertEquals(5L, service.progress("user", id).get("uploadedBytes"));
        service.resume(node, "user", "session", id); drain();
        assertEquals("COMPLETED", service.progress("user", id).get("state"));
        verify(node).createFile(anyString(), eq(""));
        verify(node).fileUploadChunk(anyString(), eq(0L), any());
        verify(node).moveFile(anyString(), eq("/remote/file.txt"), eq("overwrite"));
    }

    private String start() {
        return (String) service.start(node, "user", "session", "/remote/file.txt", source.toFile(), "file.txt", 1024).get("taskId");
    }
    private Map<String, Object> checksum() { return Map.of("code", 200, "md5", "5d41402abc4b2a76b9719d911017c592"); }
    private void drain() throws Exception { executor.submitUpload(() -> {}).get(5, TimeUnit.SECONDS); }
    private void holdRemoteCall() throws Exception {
        entered.countDown();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (release.getCount() > 0) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) throw new IllegalStateException("fixture timed out");
            try { release.await(remaining, TimeUnit.NANOSECONDS); }
            catch (InterruptedException ignored) { /* Model a remote request that has already been sent. */ }
        }
    }
}
