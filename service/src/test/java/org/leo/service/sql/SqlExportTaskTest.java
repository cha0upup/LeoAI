package org.leo.service.sql;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SqlExportTaskTest {
    private final SqlExportTaskRepository repository = mock(SqlExportTaskRepository.class);
    private final SqlExportTaskKey key = new SqlExportTaskKey("owner", "task");
    private final SqlObjectRef orders = SqlObjectRef.table("app", "sales", "orders");

    @Test
    void replacingAnAttemptFencesEveryLateMutationAndOldRetirement() {
        SqlExportTask task = database(orders);
        var old = task.beginAttempt();
        task.running(old, 30);
        task.pause();
        assertTrue(task.checkStopped(old));
        assertTrue(task.prepareResume("replacement-session"));
        var current = task.beginAttempt();
        task.running(current, 12);
        task.tablesResolved(current, 2);
        var expected = task.snapshot();

        task.running(old, 95);
        task.rowsWritten(old, 999);
        task.tablesResolved(old, 999);
        task.tableStarted(old, orders, 99, 99);
        task.tableCompleted(old, 99);
        task.complete(old, 999);
        task.completeDatabase(old, 99, 999);
        task.fail(old, "late error");
        task.failUnlessStopped(old, new IllegalStateException("late error"));
        assertTrue(task.checkStopped(old));
        assertTrue(task.tableStopped(old));
        task.finishAttempt(old);
        assertEquals(expected, task.snapshot());
        assertTrue(task.isCurrent(current));

        task.completeDatabase(current, 2, 7);
        assertEquals("COMPLETED", task.snapshot().get("status"));
        assertEquals(2, task.snapshot().get("processedTables"));
    }

    @Test
    void cancelledStateIgnoresLateProgressAndFailureAndRejectsResume() {
        SqlExportTask task = table();
        var attempt = task.beginAttempt();
        task.running(attempt, 42);
        task.cancel();
        task.running(attempt, 90);
        task.fail(attempt, "late failure");
        task.failUnlessStopped(attempt, new IllegalStateException("late failure"));
        var snapshot = task.snapshot();
        assertEquals("CANCELLED", snapshot.get("status"));
        assertEquals(42, snapshot.get("progress"));
        assertNull(snapshot.get("error"));
        assertNotNull(snapshot.get("endTime"));
        assertThrows(IllegalArgumentException.class, () -> task.prepareResume("session"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"TABLE_EXPORT", "DATABASE_EXPORT"})
    void failedCheckpointsResumeWithTheAppropriateTableOrDatabaseReset(String type) throws Exception {
        Map<String, Object> metadata = metadata(type, "FAILED");
        metadata.putAll(Map.of("progress", 60, "processedTables", 1, "rowCount", 4L,
                "fileSize", 88L, "error", "failed", "endTime", 100L));
        when(repository.load(key)).thenReturn(metadata);
        SqlExportTask task = SqlExportTask.restore(repository, key);
        assertTrue(task.prepareResume("new-session"));
        var resumed = task.snapshot();
        boolean table = type.equals("TABLE_EXPORT");
        assertEquals("PENDING", resumed.get("status"));
        assertEquals(table ? 0 : 60, resumed.get("progress"));
        assertEquals(table ? null : 4L, resumed.get("rowCount"));
        assertEquals(table ? null : 88L, resumed.get("fileSize"));
        assertEquals(1, resumed.get("processedTables"));
        assertNull(resumed.get("error"));
        assertNull(resumed.get("endTime"));
        assertEquals("new-session", task.sessionId());
        var attempt = task.beginAttempt();
        task.running(attempt, 1);
        assertFalse(task.prepareResume("ignored-session"));
        assertEquals("new-session", task.sessionId());
    }

    @Test
    @SuppressWarnings("unchecked")
    void publicViewsAndRepositoryPayloadsDoNotExposeMutableTaskMetadata() throws Exception {
        var customers = SqlObjectRef.table("app", "crm", "customers");
        var refs = List.of(orders, customers);
        SqlExportTask task = database(orders, customers);
        task.persist();
        ArgumentCaptor<Map<String, Object>> saved = ArgumentCaptor.forClass(Map.class);
        verify(repository).save(eq(key), saved.capture());
        assertEquals(refs.stream().map(SqlObjectRef::toMap).toList(), saved.getValue().get("tableRefs"));
        when(repository.load(key)).thenReturn(saved.getValue());
        assertEquals(refs, SqlExportTask.restore(repository, key).selectedTables());

        var view = task.snapshot();
        ((Map<String, Object>) view.get("objectRef")).put("catalog", "changed");
        ((Map<String, Object>) ((List<?>) view.get("tableRefs")).get(0)).put("name", "changed");
        saved.getValue().put("userId", "other");
        ((List<?>) saved.getValue().get("tableRefs")).clear();
        assertEquals(refs, task.selectedTables());
        assertEquals("app", task.objectRef().catalog());
        assertSame(task, task.requireOwner(key));
        assertFalse(task.snapshot().containsKey("userId"));
        assertFalse(task.snapshot().containsKey("finalPath"));

        var source = metadata("TABLE_EXPORT", "PAUSED");
        when(repository.load(key)).thenReturn(source);
        SqlExportTask restored = SqlExportTask.restore(repository, key);
        source.put("status", "CANCELLED");
        source.put("userId", "other");
        assertEquals("PAUSED", restored.snapshot().get("status"));
        assertSame(restored, restored.requireOwner(key));
    }

    @Test
    void repositoryFailurePreservesTheExistingBestEffortTaskStateContract() throws Exception {
        doThrow(new IllegalStateException("offline")).when(repository).save(any(), anyMap());
        SqlExportTask task = table();
        var attempt = task.beginAttempt();
        task.running(attempt, 70);
        task.pause();
        assertTrue(task.checkStopped(attempt));
        assertTrue(task.prepareResume("session"));
        attempt = task.beginAttempt();
        task.complete(attempt, 8);
        assertEquals("COMPLETED", task.snapshot().get("status"));
        assertEquals(100, task.snapshot().get("progress"));
    }

    @Test
    void shutdownCancelsActiveAttemptsButRetainsFailedCheckpointsAndExpiryBoundaries() throws Exception {
        SqlExportTask active = table();
        var attempt = active.beginAttempt();
        active.running(attempt, 30);
        active.signalCancellation();
        active.cancelOnShutdown();
        active.clearAttempt();
        active.running(attempt, 99);
        assertEquals("CANCELLED", active.snapshot().get("status"));
        assertFalse(active.isCurrent(attempt));

        var failed = metadata("DATABASE_EXPORT", "FAILED");
        failed.put("endTime", 1000L);
        when(repository.load(key)).thenReturn(failed);
        SqlExportTask stored = SqlExportTask.restore(repository, key);
        stored.cancelOnShutdown();
        assertEquals("FAILED", stored.snapshot().get("status"));
        assertFalse(stored.isExpired(1100, 100));
        assertTrue(stored.isExpired(1101, 100));
    }

    private SqlExportTask table() {
        return SqlExportTask.table(repository, "task", "owner", "session", Path.of("export.csv"),
                "downloads/export.csv", orders, "csv");
    }

    private SqlExportTask database(SqlObjectRef... tables) {
        return SqlExportTask.database(repository, "task", "owner", "session", Path.of("export.zip"),
                "downloads/export.zip", new SqlObjectRef("app", "sales", null, "schema"), List.of(tables), true, true, "zip");
    }

    private Map<String, Object> metadata(String type, String status) {
        return new LinkedHashMap<>(Map.of("taskId", "task", "userId", "owner", "sessionId", "session",
                "taskType", type, "status", status));
    }
}
