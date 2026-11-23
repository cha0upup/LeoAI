package org.leo.service.concurrent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.leo.core.config.LeoConfig;
import org.leo.service.transfer.DownloadEngineService;
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
        persist("owner", "task-one", "session-one", storedState);
        Path checkpoint = checkpoint("owner", "task-one");
        byte[] before = Files.readAllBytes(checkpoint);
        var modified = Files.getLastModifiedTime(checkpoint);

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
        assertArrayEquals(before, Files.readAllBytes(checkpoint));
        assertEquals(modified, Files.getLastModifiedTime(checkpoint));
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

    @Test
    @SuppressWarnings("unchecked")
    void shutdownUsesTheSamePausePathAndReleasesTheTaskRegistry() {
        DownloadTask first = mock(DownloadTask.class);
        DownloadTask second = mock(DownloadTask.class);
        Map<String, DownloadTask> tasks = (Map<String, DownloadTask>) ReflectionTestUtils.getField(service, "tasksById");
        assertNotNull(tasks);
        tasks.put("owner:first", first);
        tasks.put("owner:second", second);

        service.close();

        verify(first).pause();
        verify(second).pause();
        assertTrue(tasks.isEmpty());
        service.close();
        verifyNoMoreInteractions(first, second);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{", "{\"state\":\"UNKNOWN\",\"sessionId\":\"session-one\"}"})
    void listingSkipsDamagedCheckpointsWhileDirectQueriesReportTheirFailure(String damaged) throws Exception {
        persist("owner", "valid", "session-one", "COMPLETED");
        persist("owner", "broken", "session-one", "PAUSED");
        Path checkpoint = checkpoint("owner", "broken");
        Files.writeString(checkpoint, damaged);
        var tasks = (List<?>) service.listBySessionId("owner", "session-one").get("tasks");
        assertEquals(1, tasks.size());
        assertEquals("valid", ((Map<?, ?>) tasks.get(0)).get("taskId"));
        assertThrows(IllegalStateException.class, () -> service.progress("owner", "broken"));
        assertEquals(damaged, Files.readString(checkpoint));
    }

    @Test
    void expiryRemovesOnlyTerminalCheckpointsPastTheCutoff() throws Exception {
        persist("owner", "expired", "session-one", "COMPLETED");
        var recent = persist("owner", "recent", "session-one", "FAILED");
        var metadata = recent.readMeta();
        metadata.put("updatedAtMs", 101L);
        recent.writeMeta(metadata);
        persist("owner", "running", "session-one", "RUNNING");
        persist("owner", "blank", "session-one", "COMPLETED");
        Files.writeString(checkpoint("owner", "blank"), " \n");
        persist("owner", "broken", "session-one", "COMPLETED");
        Files.writeString(checkpoint("owner", "broken"), "{");

        assertEquals(1, DownloadStore.evictExpiredTaskArtifacts(101));

        assertTrue(Files.notExists(checkpoint("owner", "expired").getParent()));
        for (String id : List.of("recent", "running", "blank", "broken")) {
            assertTrue(Files.isRegularFile(checkpoint("owner", id)), id);
        }
    }

    private Path checkpoint(String user, String taskId) {
        return temp.resolve("users").resolve(user).resolve("downloads/.tasks").resolve(taskId).resolve("meta.json");
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
