package org.leo.service.downloadengine;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.leo.core.agent.capability.FileCapable;
import org.leo.core.config.LeoConfig;
import org.leo.service.concurrent.ServiceTaskExecutor;
import org.leo.service.transfer.TransferTaskState;
import org.mockito.MockedStatic;
import org.mockito.invocation.InvocationOnMock;
import org.springframework.util.DigestUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real local workspaces and deterministic workers exercise the transfer/storage boundary. */
class DownloadTaskTest {
    @TempDir Path root;
    private final FileCapable node = mock(FileCapable.class);
    private final ServiceTaskExecutor executor = mock(ServiceTaskExecutor.class);
    private final List<Runnable> workers = new ArrayList<>();
    private final List<Long> offsets = Collections.synchronizedList(new ArrayList<>());
    private byte[] content = "abcdefghijkl".getBytes(StandardCharsets.UTF_8);
    private DownloadStore store;
    private MockedStatic<LeoConfig> config;

    @BeforeEach
    void configureWorkspaceAndWorkers() throws Exception {
        config = mockStatic(LeoConfig.class);
        config.when(LeoConfig::getVfsPath).thenReturn(root.toString());
        acceptWorkers();
        when(node.fileDownloadChunk(anyString(), anyLong(), anyLong())).thenAnswer(this::chunk);
    }

    @AfterEach
    void closeConfiguration() {
        config.close();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 10})
    void publishesVerifiedContentAndLeavesAReadableCheckpoint(int length) throws Exception {
        content = Arrays.copyOf(content, length);
        DownloadTask task = createTask();
        task.ensureStarted();
        assertEquals(TransferTaskState.RUNNING, task.getState());
        workers.remove(0).run();

        assertEquals(TransferTaskState.COMPLETED, task.getState());
        assertArrayEquals(content, Files.readAllBytes(finalPath(task)));
        assertFalse(store.getTempFile().exists());
        assertEquals(length, ((Number) task.snapshot().get("downloadedBytes")).intValue());
        assertEquals(task.snapshot().get("totalChunks"), task.snapshot().get("doneChunks"));
        assertEquals("COMPLETED", store.readSnapshot().get("state"));
        assertEquals(task.snapshot().get("downloadPath"), store.readSnapshot().get("downloadPath"));
        assertFalse(store.readSnapshot().containsKey("finalPath"));
        verify(executor).submitDownloadWorkers(eq(2), any());
    }

    @Test
    void parallelWorkersWriteEachRangeOnceBeforePublishingTheCompleteFile() throws Exception {
        DownloadTask task = createTask();
        CountDownLatch parallelReads = new CountDownLatch(2);
        doAnswer(call -> {
            long offset = call.getArgument(2);
            if (offset < 8) {
                parallelReads.countDown();
                assertTrue(parallelReads.await(2, TimeUnit.SECONDS));
            }
            return chunk(call);
        }).when(node).fileDownloadChunk(anyString(), anyLong(), anyLong());
        task.ensureStarted();
        Runnable worker = workers.remove(0);
        var pool = Executors.newFixedThreadPool(2);
        try {
            CompletableFuture.allOf(CompletableFuture.runAsync(worker, pool),
                    CompletableFuture.runAsync(worker, pool)).get(3, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        assertEquals(TransferTaskState.COMPLETED, task.getState());
        assertEquals(List.of(0L, 4L, 8L), offsets.stream().sorted().toList());
        assertArrayEquals(content, Files.readAllBytes(finalPath(task)));
    }

    @Test
    void reloadsPausedChunksWithoutOverwritingOrRedownloadingCompletedRanges() throws Exception {
        DownloadTask first = createTask();
        AtomicBoolean pauseOnce = new AtomicBoolean(true);
        doAnswer(call -> {
            Map<String, Object> response = chunk(call);
            if (pauseOnce.getAndSet(false)) first.pause();
            return response;
        }).when(node).fileDownloadChunk(anyString(), anyLong(), anyLong());
        first.ensureStarted();
        workers.remove(0).run();
        assertEquals(TransferTaskState.PAUSED, first.getState());
        assertEquals(1, first.snapshot().get("doneChunks"));

        DownloadTask restored = DownloadTask.loadFromDisk(node, "owner", "session", "task",
                new DownloadStore("owner", "task"), executor);
        assertEquals(TransferTaskState.PAUSED, restored.getState());
        assertEquals(4L, restored.snapshot().get("downloadedBytes"));
        restored.ensureStarted();
        workers.remove(0).run();
        assertEquals(TransferTaskState.COMPLETED, restored.getState());
        assertEquals(List.of(0L, 4L, 8L), offsets);
        assertArrayEquals(content, Files.readAllBytes(finalPath(restored)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"remote", "local"})
    void checksumFailureKeepsPartialContentOutOfThePublishedDownload(String failedSide) throws Exception {
        DownloadTask task = createTask();
        if ("remote".equals(failedSide)) {
            when(node.getFileMD5(anyString())).thenReturn(Map.of("code", 200, "md5", "changed"));
        } else {
            doAnswer(call -> {
                var response = chunk(call);
                ((byte[]) response.get("data"))[0] ^= 1;
                return response;
            }).when(node).fileDownloadChunk(anyString(), anyLong(), anyLong());
        }
        task.ensureStarted();
        workers.remove(0).run();
        assertEquals(TransferTaskState.FAILED, task.getState());
        assertEquals("remote".equals(failedSide) ? "VERIFYING_REMOTE" : "VERIFYING_LOCAL", task.snapshot().get("errorStage"));
        assertTrue(store.getTempFile().isFile());
        assertFalse(Files.exists(finalPath(task)));
        assertEquals("FAILED", store.readSnapshot().get("state"));
    }

    @Test
    void retriesFailedPublicationUsingTheVerifiedChunksAlreadyOnDisk() throws Exception {
        DownloadTask task = createTask();
        Path target = finalPath(task);
        Files.createDirectories(target);
        Path obstruction = Files.writeString(target.resolve("keep.txt"), "occupied");
        task.ensureStarted();
        workers.remove(0).run();
        assertEquals(TransferTaskState.FAILED, task.getState());
        assertEquals("COMMITTING", task.snapshot().get("errorStage"));
        assertTrue(store.getTempFile().isFile());
        assertEquals("occupied", Files.readString(obstruction));
        List<Long> fetched = List.copyOf(offsets);

        Files.delete(obstruction);
        Files.delete(target);
        assertTrue(task.resetFailedForRetry());
        task.ensureStarted();
        workers.remove(0).run();
        assertEquals(TransferTaskState.COMPLETED, task.getState());
        assertEquals(fetched, offsets);
        assertArrayEquals(content, Files.readAllBytes(target));
    }

    @Test
    void queueRejectionCanRetryWithoutLosingThePreparedWorkspace() throws Exception {
        DownloadTask task = createTask();
        doThrow(new RejectedExecutionException("busy")).when(executor).submitDownloadWorkers(anyInt(), any());
        task.ensureStarted();
        assertEquals(TransferTaskState.FAILED, task.getState());
        assertEquals("下载任务队列繁忙", task.snapshot().get("lastError"));
        assertEquals(0, task.snapshot().get("doneChunks"));
        assertTrue(store.getTempFile().isFile());
        verifyNoInteractions(node);

        acceptWorkers();
        assertTrue(task.resetFailedForRetry());
        task.ensureStarted();
        workers.remove(0).run();
        assertEquals(TransferTaskState.COMPLETED, task.getState());
        assertArrayEquals(content, Files.readAllBytes(finalPath(task)));
    }

    @Test
    void cancellationBeforeWorkersStartRemainsTerminalAndKeepsTheCheckpoint() throws Exception {
        DownloadTask task = createTask();
        task.cancel();
        task.ensureStarted();
        assertEquals(TransferTaskState.CANCELLED, task.getState());
        assertEquals("CANCELLED", store.readSnapshot().get("state"));
        assertFalse(Files.exists(finalPath(task)));
        verify(executor, never()).submitDownloadWorkers(anyInt(), any());
        verifyNoInteractions(node);
    }

    @Test
    void rejectsRestoringAnotherSessionBeforeChangingTheCheckpointOrPartialContent() throws Exception {
        createTask();
        byte[] meta = Files.readAllBytes(store.getMetaFile().toPath());
        byte[] partial = Files.readAllBytes(store.getTempFile().toPath());
        assertThrows(IllegalArgumentException.class, () -> DownloadTask.loadFromDisk(
                node, "owner", "other-session", "task", store, executor));
        assertArrayEquals(meta, Files.readAllBytes(store.getMetaFile().toPath()));
        assertArrayEquals(partial, Files.readAllBytes(store.getTempFile().toPath()));
        verifyNoInteractions(node, executor);
    }

    private DownloadTask createTask() throws Exception {
        String md5 = DigestUtils.md5DigestAsHex(content);
        when(node.getFileMD5(anyString())).thenReturn(Map.of("code", 200, "md5", md5));
        store = new DownloadStore("owner", "task");
        return DownloadTask.createNewOrLoad(node, "owner", "session", "task", "/remote/result.bin",
                2, 4, content.length, md5, store, executor);
    }

    private void acceptWorkers() {
        doAnswer(call -> {
            workers.add(call.getArgument(1));
            return List.of(new CompletableFuture<>());
        }).when(executor).submitDownloadWorkers(anyInt(), any());
    }

    private Map<String, Object> chunk(InvocationOnMock call) {
        long offset = call.getArgument(2), size = call.getArgument(1);
        offsets.add(offset);
        byte[] data = Arrays.copyOfRange(content, (int) offset, (int) Math.min(content.length, offset + size));
        return Map.of("code", 200, "data", data, "bytesRead", data.length);
    }

    private Path finalPath(DownloadTask task) {
        return root.resolve("users/owner").resolve(String.valueOf(task.snapshot().get("downloadPath")));
    }
}
