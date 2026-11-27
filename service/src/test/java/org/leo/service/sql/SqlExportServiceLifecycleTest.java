package org.leo.service.sql;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.leo.core.agent.capability.SqlCapable;
import org.leo.core.util.json.JsonUtil;
import org.leo.service.concurrent.ServiceTaskExecutor;
import org.mockito.stubbing.Answer;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class SqlExportServiceLifecycleTest {
    @TempDir Path directory;
    private final AgentNodeSqlService sql = mock(AgentNodeSqlService.class);
    private final ServiceTaskExecutor executor = mock(ServiceTaskExecutor.class);
    private final SqlCapable node = mock(SqlCapable.class);
    private final List<Runnable> queued = new ArrayList<>();
    private final SqlExportWorkspace workspace = new SqlExportWorkspace(() -> directory);
    private final SqlExportArtifacts artifacts = spy(new FileSqlExportArtifacts(workspace));
    private final SqlExportTaskRepository repository = spy(new FileSqlExportTaskRepository(workspace));
    private final SqlExportService service = newService();

    @BeforeEach void setup() {
        when(executor.submitSqlExport(any(Runnable.class))).thenAnswer(invocation -> {
            queued.add(invocation.getArgument(0));
            return CompletableFuture.completedFuture(null);
        });
    }

    @AfterEach void cleanup() {
        service.close();
    }

    @ParameterizedTest
    @ValueSource(strings = {"progress", "pause", "stop", "resume"})
    void cachedTasksRemainPrivateToTheirOwner(String operation) throws Exception {
        String id = id(start("table"));
        assertThrows(IllegalArgumentException.class, () -> {
            switch (operation) {
                case "progress" -> service.progress("other", id);
                case "pause" -> service.pause("other", id);
                case "stop" -> service.stop("other", id);
                case "resume" -> service.resume(node, "other", "other-session", id, Map.of());
                default -> throw new AssertionError(operation);
            }
        });
        assertEquals("PENDING", service.progress("owner", id).get("status"));
        assertEquals(1, queued.size());
        verifyNoInteractions(sql);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", ".", "..", "../owner", "/tmp/task", "a/b", "a\\b", "C:task"})
    void validatesIdentityBeforeConsultingTheTaskCache(String invalid) throws Exception {
        String id = id(start("table"));
        assertThrows(IllegalArgumentException.class, () -> service.progress(invalid, id));
        assertThrows(IllegalArgumentException.class, () -> service.progress("owner", invalid));
    }

    @ParameterizedTest
    @ValueSource(strings = {"userId", "taskId"})
    void persistedMetadataMustMatchItsOwnerAndDirectory(String field) throws Exception {
        String id = id(start("table"));
        Path meta = metadata("owner", id);
        @SuppressWarnings("unchecked")
        Map<String, Object> task = JsonUtil.fromJsonString(Files.readString(meta), Map.class);
        task.put(field, "different-identity");
        Files.writeString(meta, JsonUtil.toJsonString(task));
        SqlExportService restored = newService();
        try {
            assertThrows(IllegalArgumentException.class, () -> restored.progress("owner", id));
            assertThrows(IllegalArgumentException.class, () -> restored.stop("owner", id));
            assertEquals(0, restored.listBySessionId("owner", "session").get("count"));
        } finally { restored.close(); }
    }

    @Test
    @SuppressWarnings("unchecked")
    void freshServicesCanReadAndControlOwnedPersistedTasks() throws Exception {
        var original = start("database");
        String id = id(original);
        var key = new SqlExportTaskKey("owner", id);
        // Reuse one persisted snapshot to distinguish copied response fields from shared metadata.
        var stored = repository.load(key);
        doReturn(stored, stored).doCallRealMethod().when(repository).load(key);
        SqlExportService restored = newService();
        try {
            var progress = restored.progress("owner", id);
            assertEquals(original, progress);
            var listed = restored.listBySessionId("owner", "session");
            assertEquals(List.of(original), listed.get("tasks"));
            ((Map<String, Object>) ((List<?>) progress.get("tableRefs")).get(0)).put("name", "changed");
            assertEquals(List.of(original), listed.get("tasks"));
            assertEquals(original.get("tableRefs"), stored.get("tableRefs"));
            assertEquals("CANCELLED", restored.stop("owner", id).get("status"));
            assertEquals("CANCELLED", restored.progress("owner", id).get("status"));
            assertEquals("CANCELLED", repository.load(key).get("status"));
            verifyNoInteractions(sql);
        } finally { restored.close(); }
    }

    @Test void identicalTaskIdsInDifferentWorkspacesRemainIndependent() throws Exception {
        String id = id(start("table"));
        var otherKey = new SqlExportTaskKey("other", id);
        Path otherOutput = artifacts.reserveOutput("other", "other.xlsx");
        SqlExportTask.table(repository, otherKey, "other-session", otherOutput,
                artifacts.relativePath("other", otherOutput), SqlObjectRef.table("app", "public", "orders")).persist();

        assertEquals("other.xlsx", service.progress("other", id).get("fileName"));
        assertEquals(1, service.listBySessionId("other", "other-session").get("count"));
        assertEquals("CANCELLED", service.stop("other", id).get("status"));
        assertEquals("CANCELLED", repository.load(otherKey).get("status"));
        assertEquals("PENDING", service.progress("owner", id).get("status"));
        verifyNoInteractions(sql);
    }

    @Test void listingKeepsTheTaskStateCapturedBeforeAConcurrentResume() throws Exception {
        String id = id(start("table"));
        service.pause("owner", id);
        queued.get(0).run();
        assertEquals("PAUSED", service.progress("owner", id).get("status"));

        var otherKey = new SqlExportTaskKey("owner", "other-task");
        Path otherOutput = artifacts.reserveOutput("owner", "other.xlsx");
        SqlExportTask.table(repository, otherKey, "unrelated-session", otherOutput,
                artifacts.relativePath("owner", otherOutput), SqlObjectRef.table("app", "public", "orders")).persist();
        doReturn(List.of(id, otherKey.taskId())).when(repository).listTaskIds("owner");
        doAnswer(call -> {
            // Simulate a resume after the first task was selected but before the list is assembled.
            service.resume(node, "owner", "new-session", id, Map.of());
            return call.callRealMethod();
        }).doCallRealMethod().when(repository).load(otherKey);

        Map<String, Object> result = service.listBySessionId("owner", "session");
        assertEquals(1, result.get("count"));
        var row = (Map<?, ?>) ((List<?>) result.get("tasks")).get(0);
        assertEquals(id, row.get("taskId"));
        assertEquals("PAUSED", row.get("status"));
        assertEquals("PENDING", service.progress("owner", id).get("status"));
        assertEquals(0, service.listBySessionId("owner", "session").get("count"));
        assertEquals(1, service.listBySessionId("owner", "new-session").get("count"));
        verifyNoInteractions(sql);
    }

    @Test void reportsTheReservedFilenameAndNeverOverwritesAnExistingExport() throws Exception {
        var first = start("table");
        Path firstPath = directory.resolve("users/owner").resolve((String) first.get("downloadPath"));
        assertTrue(Files.exists(firstPath));
        Files.writeString(firstPath, "existing-export");
        var second = start("table");
        Path secondPath = directory.resolve("users/owner").resolve((String) second.get("downloadPath"));
        assertNotEquals(firstPath, secondPath);
        assertEquals(secondPath.getFileName().toString(), second.get("fileName"));
        assertEquals("existing-export", Files.readString(firstPath));
    }

    @ParameterizedTest
    @CsvSource({"table,pause", "table,stop", "database,pause", "database,stop"})
    void pausedOrCancelledQueuedExportsSkipArtifactAndNodeAccess(String kind, String operation) throws Exception {
        String id = id(start(kind));
        var registration = inOrder(repository, executor);
        registration.verify(repository).save(eq(new SqlExportTaskKey("owner", id)), anyMap());
        registration.verify(executor).submitSqlExport(any(Runnable.class));
        if (operation.equals("pause")) service.pause("owner", id);
        else service.stop("owner", id);
        clearInvocations(artifacts);
        queued.get(0).run();
        assertEquals(operation.equals("pause") ? "PAUSED" : "CANCELLED", service.progress("owner", id).get("status"));
        verifyNoInteractions(artifacts, sql);
    }

    @ParameterizedTest
    @CsvSource({"table,restore,pause", "table,restore,stop", "database,restore,pause", "database,restore,stop",
            "database,workspace,pause", "database,workspace,stop"})
    void stopRequestsDuringArtifactPreparationPreventQueries(String kind, String stage, String operation) throws Exception {
        String id = id(start(kind));
        Answer<Path> stopDuringPreparation = call -> {
            Path path = (Path) call.callRealMethod();
            if (operation.equals("pause")) service.pause("owner", id);
            else service.stop("owner", id);
            return path;
        };
        if (stage.equals("restore"))
            doAnswer(stopDuringPreparation).when(artifacts).restoreOutput(anyString(), anyString());
        else doAnswer(stopDuringPreparation).when(artifacts).workDirectory(any());

        queued.get(0).run();

        String expected = operation.equals("pause") ? "PAUSED" : "CANCELLED";
        assertEquals(expected, service.progress("owner", id).get("status"));
        assertEquals(expected, repository.load(new SqlExportTaskKey("owner", id)).get("status"));
        verifyNoInteractions(sql);
    }

    @Test void supersededQueuedWorkersNeverChangeTheReplacementTask() throws Exception {
        String id = id(start("table"));
        service.pause("owner", id);
        service.resume(node, "owner", "session", id, Map.of());
        assertEquals(2, queued.size());
        queued.get(0).run();
        assertEquals("PENDING", service.progress("owner", id).get("status"));
        verifyNoInteractions(sql);
    }

    @Test void resumingAnExpiredFailureWhileItIsEvictedStillRunsTheExport() throws Exception {
        var key = new SqlExportTaskKey("owner", "expired-task");
        var table = SqlObjectRef.table("app", "public", "orders");
        Path output = artifacts.reserveOutput(key.userId(), "orders.xlsx");
        SqlExportTask.table(repository, key, "session", output,
                artifacts.relativePath(key.userId(), output), table).persist();
        var failed = repository.load(key);
        failed.put("status", "FAILED");
        failed.put("endTime", System.currentTimeMillis() - TimeUnit.HOURS.toMillis(1));
        repository.save(key, failed);
        when(sql.getTableColumns(any(), anyMap(), any())).thenReturn(Map.of("columns", List.of(Map.of("name", "id"))));
        when(sql.queryTable(any(), anyMap(), any(), anyInt(), anyInt(), anyList(), anyList(), anyList(), anyBoolean(), isNull()))
                .thenReturn(Map.of("rows", List.of(Map.of("id", 7)), "pagination", Map.of("total", 1)));

        var beforeRemoval = new CountDownLatch(1);
        var releaseRemoval = new CountDownLatch(1);
        ReflectionTestUtils.setField(service, "liveTasks", new ConcurrentHashMap<SqlExportTaskKey, SqlExportTask>() {
            @Override public boolean remove(Object key, Object value) {
                beforeRemoval.countDown();
                try {
                    assertTrue(releaseRemoval.await(10, TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(e);
                }
                return super.remove(key, value);
            }
        });
        assertEquals("FAILED", service.pause(key.userId(), key.taskId()).get("status"));

        var eviction = new FutureTask<>(service::evictFinished);
        var resume = new FutureTask<>(() -> service.resume(node, key.userId(), "new-session", key.taskId(), Map.of()));
        var cleaner = new Thread(eviction, "sql-export-eviction-test");
        var resumer = new Thread(resume, "sql-export-resume-test");
        cleaner.start();
        try {
            assertTrue(beforeRemoval.await(5, TimeUnit.SECONDS));
            resumer.start();
            var threads = ManagementFactory.getThreadMXBean();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            // On the old path resume finishes before removal; on the fixed path it waits for eviction.
            while (!resume.isDone()) {
                var info = threads.getThreadInfo(resumer.getId());
                if (info != null && info.getLockOwnerId() == cleaner.getId()) break;
                assertTrue(System.nanoTime() - deadline < 0, "resume did not reach task eviction");
                Thread.sleep(1);
            }
        } finally {
            releaseRemoval.countDown();
        }

        assertEquals(1, eviction.get(5, TimeUnit.SECONDS));
        assertEquals("PENDING", resume.get(5, TimeUnit.SECONDS).get("status"));
        assertEquals(1, queued.size());
        queued.get(0).run();
        var saved = repository.load(key);
        assertEquals("COMPLETED", saved.get("status"));
        assertEquals("new-session", saved.get("sessionId"));
    }

    @Test void supersededRunningCallbacksAndRetirementKeepTheReplacementAttemptIntact() throws Exception {
        String id = id(start("table"));
        AtomicBoolean resumed = new AtomicBoolean();
        doAnswer(call -> {
            Map<String, Object> saved = call.getArgument(1);
            call.callRealMethod();
            if ("PAUSED".equals(saved.get("status")) && resumed.compareAndSet(false, true))
                service.resume(node, "owner", "session", id, Map.of());
            return null;
        }).when(repository).save(any(), anyMap());
        when(sql.getTableColumns(any(), anyMap(), any())).thenReturn(Map.of("columns", List.of(Map.of("name", "id"))));
        AtomicBoolean pauseOnce = new AtomicBoolean();
        when(sql.queryTable(any(), anyMap(), any(), anyInt(), anyInt(), anyList(), anyList(), anyList(), anyBoolean(), isNull()))
                .thenAnswer(call -> {
                    if (pauseOnce.compareAndSet(false, true)) service.pause("owner", id);
                    return Map.of("rows", List.of(Map.of("id", 7)), "pagination", Map.of("total", 1));
                });
        queued.get(0).run();
        var replacement = service.progress("owner", id);
        assertTrue(resumed.get());
        assertEquals(2, queued.size());
        assertEquals("PENDING", replacement.get("status"));
        assertEquals(0, replacement.get("progress"));
        assertNull(replacement.get("rowCount"));
        queued.get(1).run();
        assertEquals("COMPLETED", service.progress("owner", id).get("status"));
    }

    @Test void workerExitRetriesAnUnsavedTerminalState() throws Exception {
        String id = id(start("table"));
        var key = new SqlExportTaskKey("owner", id);
        when(sql.getTableColumns(any(), anyMap(), any())).thenThrow(new IllegalStateException("fixture read failure"));
        AtomicBoolean failedFirstSave = new AtomicBoolean();
        doAnswer(call -> {
            Map<String, Object> saved = call.getArgument(1);
            if ("FAILED".equals(saved.get("status")) && failedFirstSave.compareAndSet(false, true)) {
                throw new java.io.IOException("fixture save failure");
            }
            return call.callRealMethod();
        }).when(repository).save(eq(key), anyMap());

        queued.get(0).run();
        var stored = repository.load(key);
        assertTrue(failedFirstSave.get());
        assertEquals("FAILED", stored.get("status"));
        assertEquals("fixture read failure", stored.get("error"));
    }

    private Map<String, Object> start(String kind) throws Exception {
        SqlObjectRef table = SqlObjectRef.table("app", "public", "orders");
        return kind.equals("table")
                ? service.startTableExport(node, "owner", "session", Map.of(), table)
                : service.startDatabaseExport(node, "owner", "session", Map.of(),
                        new SqlObjectRef("app", "public", null, "schema"), List.of(table), true, true);
    }

    private String id(Map<String, Object> task) { return (String) task.get("taskId"); }
    private Path metadata(String user, String id) {
        return directory.resolve("users").resolve(user).resolve("downloads/sql-export/.sql-export-tasks").resolve(id).resolve("meta.json");
    }
    private SqlExportService newService() {
        return new SqlExportService(sql, executor, repository, artifacts, new SqlTableExporter(sql));
    }
}
