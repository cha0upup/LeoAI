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
import org.leo.core.config.LeoConfig;
import org.leo.core.util.json.JsonUtil;
import org.leo.service.concurrent.ServiceTaskExecutor;
import org.mockito.MockedStatic;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SqlExportServiceLifecycleTest {
    @TempDir Path directory;
    private final AgentNodeSqlService sql = mock(AgentNodeSqlService.class);
    private final ServiceTaskExecutor executor = mock(ServiceTaskExecutor.class);
    private final SqlCapable node = mock(SqlCapable.class);
    private final List<Runnable> queued = new ArrayList<>();
    private final SqlExportService service = new SqlExportService(sql, executor);
    private MockedStatic<LeoConfig> config;

    @BeforeEach void setup() {
        config = mockStatic(LeoConfig.class);
        config.when(LeoConfig::getVfsPath).thenReturn(directory.toString());
        when(executor.submitSqlExport(any(Runnable.class))).thenAnswer(invocation -> {
            queued.add(invocation.getArgument(0));
            return CompletableFuture.completedFuture(null);
        });
    }

    @AfterEach void cleanup() {
        try { service.close(); } finally { config.close(); }
    }

    @ParameterizedTest
    @ValueSource(strings = {"status", "progress", "pause", "stop", "resume"})
    void cachedTasksRemainPrivateToTheirOwner(String operation) throws Exception {
        String id = id(start("table"));
        assertThrows(IllegalArgumentException.class, () -> {
            switch (operation) {
                case "status" -> service.getTaskStatus("other", id);
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
        clearLiveTasks();
        assertThrows(IllegalArgumentException.class, () -> service.progress("owner", id));
        assertThrows(IllegalArgumentException.class, () -> service.stop("owner", id));
        assertEquals(0, service.listBySessionId("owner", "session").get("count"));
    }

    @Test void ownedPersistedTasksRemainReadableAndControllableAfterCacheEviction() throws Exception {
        String id = id(start("table"));
        clearLiveTasks();
        assertEquals("PENDING", service.progress("owner", id).get("status"));
        assertEquals("CANCELLED", service.stop("owner", id).get("status"));
        assertEquals("CANCELLED", service.progress("owner", id).get("status"));
    }

    @Test void reservesUniqueOutputPathsBeforeWorkersHaveStarted() throws Exception {
        Path first = ReflectionTestUtils.invokeMethod(service, "resolveUniqueExportPath", "owner", "same.csv");
        Path second = ReflectionTestUtils.invokeMethod(service, "resolveUniqueExportPath", "owner", "same.csv");
        assertNotEquals(first, second);
        assertTrue(Files.isRegularFile(first));
        assertTrue(Files.isRegularFile(second));
        assertEquals("same(1).csv", second.getFileName().toString());
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
    void pausedOrCancelledQueuedExportsNeverAccessTheNode(String kind, String operation) throws Exception {
        String id = id(start(kind));
        if (operation.equals("pause")) service.pause("owner", id);
        else service.stop("owner", id);
        queued.get(0).run();
        assertEquals(operation.equals("pause") ? "PAUSED" : "CANCELLED", service.progress("owner", id).get("status"));
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

    private Map<String, Object> start(String kind) throws Exception {
        SqlObjectRef table = SqlObjectRef.table("app", "public", "orders");
        return kind.equals("table")
                ? service.startTableExport(node, "owner", "session", Map.of(), table, "csv")
                : service.startDatabaseExport(node, "owner", "session", Map.of(),
                        new SqlObjectRef("app", "public", null, "schema"), List.of(table), true, true, "zip");
    }

    private String id(Map<String, Object> task) { return (String) task.get("taskId"); }
    private Path metadata(String user, String id) {
        return directory.resolve("users").resolve(user).resolve("downloads/sql-export/.sql-export-tasks").resolve(id).resolve("meta.json");
    }
    private void clearLiveTasks() {
        ((Map<?, ?>) ReflectionTestUtils.getField(service, "liveTasks")).clear();
    }
}
