package org.leo.service.concurrent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.leo.core.config.LeoConfig;
import org.leo.service.DownloadEngineService;
import org.leo.service.downloadengine.DownloadStore;
import org.leo.service.downloadengine.DownloadTask;
import org.mockito.MockedStatic;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DownloadEngineServiceTest {
    @TempDir Path temp;
    private final ServiceTaskExecutor executor = new ServiceTaskExecutor(1, 1, 1, 1, 1, 1);
    private final DownloadEngineService service = new DownloadEngineService(executor);
    private MockedStatic<LeoConfig> config;

    @BeforeEach
    void configureRoot() {
        config = mockStatic(LeoConfig.class);
        config.when(LeoConfig::getVfsPath).thenReturn(temp.toString());
    }

    @AfterEach
    void close() {
        try { service.close(); executor.close(); } finally { config.close(); }
    }

    @ParameterizedTest
    @CsvSource({"RUNNING,PAUSED", "PAUSED,PAUSED", "NEW,NEW", "COMPLETED,COMPLETED", "FAILED,FAILED", "CANCELLED,CANCELLED"})
    void persistedTasksUseTheSameFlatPublicSnapshotWithoutRewritingMetadata(String storedState, String displayedState) throws Exception {
        DownloadStore store = persist("owner", "task-one", "session-one", storedState);
        byte[] before = Files.readAllBytes(store.getMetaFile().toPath());
        var modified = Files.getLastModifiedTime(store.getMetaFile().toPath());

        Map<String, Object> snapshot = service.progress("owner", "task-one");

        assertEquals(displayedState, snapshot.get("state"));
        assertEquals("task-one", snapshot.get("taskId"));
        assertEquals("session-one", snapshot.get("sessionId"));
        assertEquals(8L, ((Number) snapshot.get("expectedLength")).longValue());
        assertEquals(4L, ((Number) snapshot.get("downloadedBytes")).longValue());
        assertEquals(0L, snapshot.get("speedBytesPerSec"));
        assertEquals("downloads/result.bin", snapshot.get("downloadPath"));
        assertEquals("downloads/.tasks/task-one/target.part", snapshot.get("taskTempPath"));
        for (String field : List.of("meta", "userId", "finalPath", "tempPath", "internalNote")) {
            assertFalse(snapshot.containsKey(field), field);
        }
        assertFalse(snapshot.toString().contains(temp.toString()));
        assertEquals(List.of(snapshot), service.listBySessionId("owner", "session-one").get("tasks"));
        assertArrayEquals(before, Files.readAllBytes(store.getMetaFile().toPath()));
        assertEquals(modified, Files.getLastModifiedTime(store.getMetaFile().toPath()));
    }

    @Test
    void listingIsScopedByOwnerAndSession() throws Exception {
        persist("owner", "task-one", "session-one", "COMPLETED");
        persist("owner", "task-two", "session-two", "PAUSED");
        persist("other", "other-task", "session-one", "COMPLETED");
        assertEquals(1, service.listBySessionId("owner", "session-one").get("count"));
        assertEquals(1, service.listBySessionId("owner", "session-two").get("count"));
        assertThrows(IllegalArgumentException.class, () -> service.progress("other", "task-one"));
        assertThrows(IllegalArgumentException.class, () -> service.progress("owner", "missing"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void anActiveMemoryTaskWinsOverItsStoredCheckpoint() throws Exception {
        persist("owner", "task-one", "session-one", "RUNNING");
        DownloadTask live = mock(DownloadTask.class);
        var snapshot = Map.<String, Object>of("taskId", "task-one", "sessionId", "session-one",
                "state", "RUNNING", "speedBytesPerSec", 42L);
        when(live.snapshot()).thenReturn(snapshot);
        when(live.belongsTo("owner", "session-one")).thenReturn(true);
        Map<String, DownloadTask> tasks = (Map<String, DownloadTask>) ReflectionTestUtils.getField(service, "tasksById");
        assertNotNull(tasks);
        tasks.put("owner:task-one", live);
        assertSame(snapshot, service.progress("owner", "task-one"));
        assertEquals(List.of(snapshot), service.listBySessionId("owner", "session-one").get("tasks"));
    }

    @Test
    void serverPathsOutsideTheOwnerDirectoryAreNeverProjected() throws Exception {
        DownloadStore store = persist("owner", "task-one", "session-one", "COMPLETED");
        var meta = store.readMeta();
        meta.put("finalPath", temp.resolve("users/other/private.bin").toString());
        meta.put("tempPath", temp.resolve("outside.bin").toString());
        store.writeMeta(meta);
        var snapshot = service.progress("owner", "task-one");
        assertFalse(snapshot.containsKey("downloadPath"));
        assertFalse(snapshot.containsKey("taskTempPath"));
        assertFalse(snapshot.toString().contains(temp.toString()));
    }

    private DownloadStore persist(String user, String taskId, String session, String state) throws Exception {
        DownloadStore store = new DownloadStore(user, taskId);
        Map<String, Object> meta = new HashMap<>();
        meta.put("taskId", taskId);
        meta.put("userId", user);
        meta.put("sessionId", session);
        meta.put("state", state);
        meta.put("filePath", "/remote/result.bin");
        meta.put("currentStage", "TRANSFERRING");
        meta.put("expectedLength", 8L);
        meta.put("downloadedBytes", 4L);
        meta.put("threads", 2);
        meta.put("chunkSize", 4);
        meta.put("totalChunks", 2);
        meta.put("doneChunks", 1);
        meta.put("updatedAtMs", 100L);
        meta.put("finalPath", temp.resolve("users").resolve(user).resolve("downloads/result.bin").toString());
        meta.put("tempPath", store.getTempFile().getAbsolutePath());
        meta.put("internalNote", "private checkpoint detail");
        store.writeMeta(meta);
        return store;
    }
}
