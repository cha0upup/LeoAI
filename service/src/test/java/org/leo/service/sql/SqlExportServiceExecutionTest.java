package org.leo.service.sql;

import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.leo.core.agent.capability.SqlCapable;
import org.leo.core.util.json.JsonUtil;
import org.leo.service.concurrent.ServiceTaskExecutor;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SqlExportServiceExecutionTest {
    @TempDir Path root;
    private final AgentNodeSqlService sql = mock(AgentNodeSqlService.class);
    private final ServiceTaskExecutor executor = mock(ServiceTaskExecutor.class);
    private final SqlCapable node = mock(SqlCapable.class);
    private final List<Runnable> queued = new ArrayList<>();
    private final SqlExportWorkspace workspace = new SqlExportWorkspace(() -> root);
    private final SqlExportTaskRepository repository = new FileSqlExportTaskRepository(workspace);
    private final SqlExportArtifacts artifacts = spy(new FileSqlExportArtifacts(workspace));
    private final SqlExportService service = new SqlExportService(sql, executor, repository, artifacts, new SqlTableExporter(sql));
    private final SqlObjectRef orders = SqlObjectRef.table("app", "sales", "orders");
    private final SqlObjectRef customers = SqlObjectRef.table("app", "sales", "customers");

    @BeforeEach void setup() throws Exception {
        when(executor.submitSqlExport(any(Runnable.class))).thenAnswer(call -> {
            queued.add(call.getArgument(0)); return CompletableFuture.completedFuture(null);
        });
        when(sql.getTableColumns(any(), anyMap(), any())).thenReturn(Map.of("columns", List.of(Map.of("name", "id"))));
        when(sql.queryTable(any(), anyMap(), any(), anyInt(), anyInt(), anyList(), anyList(), anyList(), anyBoolean(), isNull()))
                .thenReturn(Map.of("rows", List.of(Map.of("id", 7)), "pagination", Map.of("total", 1)));
    }

    @AfterEach void close() { service.close(); }

    @Test void completesTableExportsWithoutExposingPrivateTaskMetadata() throws Exception {
        when(sql.queryTable(any(), anyMap(), any(), anyInt(), anyInt(), anyList(), anyList(), anyList(), anyBoolean(), isNull()))
                .thenReturn(Map.of("rows", List.of(Map.of("id", "00001234567890123456"), Map.of("id", "=备注")),
                        "pagination", Map.of("total", 2)));
        var task = startTable();
        queued.get(0).run();
        var result = service.progress("owner", id(task));
        assertEquals("COMPLETED", result.get("status"), () -> String.valueOf(result.get("error")));
        assertEquals(100, result.get("progress"));
        assertEquals("xlsx", result.get("format"));
        assertEquals(2L, result.get("rowCount"));
        Path output = root.resolve("users/owner").resolve((String) result.get("downloadPath"));
        assertTrue(output.getFileName().toString().endsWith(".xlsx"));
        assertEquals(Files.size(output), result.get("fileSize"));
        assertFalse(result.containsKey("finalPath"));
        assertFalse(result.containsKey("userId"));
        assertTrue(((String) result.get("downloadUrl")).startsWith("/user/file/download?path=downloads%2Fsql-export%2F"));
        assertEquals("COMPLETED", repository.load(new SqlExportTaskKey("owner", id(task))).get("status"));
        try (var stream = Files.newInputStream(output); var workbook = WorkbookFactory.create(stream)) {
            var sheet = workbook.getSheet("数据");
            assertEquals("id", sheet.getRow(0).getCell(0).getStringCellValue());
            assertEquals("00001234567890123456", sheet.getRow(1).getCell(0).getStringCellValue());
            var text = sheet.getRow(2).getCell(0);
            assertEquals(CellType.STRING, text.getCellType());
            assertEquals("=备注", text.getStringCellValue());
        }
    }

    @ParameterizedTest
    @CsvSource({"true,false", "false,true", "true,true"})
    void preservesDatabaseStructureAndDataOptions(boolean structure, boolean data) throws Exception {
        var task = startDatabase(structure, data);
        queued.get(0).run();
        var result = service.progress("owner", id(task));
        assertEquals("COMPLETED", result.get("status"), () -> String.valueOf(result.get("error")));
        assertEquals("zip", result.get("format"));
        assertEquals(2, result.get("processedTables"));
        Path output = root.resolve("users/owner").resolve((String) result.get("downloadPath"));
        try (var zip = new ZipFile(output.toFile())) {
            try (var input = zip.getInputStream(zip.getEntry("manifest.json"))) {
                var manifest = JsonUtil.fromJsonString(new String(input.readAllBytes(), StandardCharsets.UTF_8), Map.class);
                assertEquals(data ? "xlsx" : null, manifest.get("dataFormat"));
            }
            for (String name : List.of("app_sales_orders", "app_sales_customers")) {
                assertEquals(structure, zip.getEntry("structure/" + name + ".columns.json") != null);
                var entry = zip.getEntry("data/" + name + ".xlsx");
                assertEquals(data, entry != null);
                if (entry != null) {
                    try (var input = zip.getInputStream(entry); var workbook = WorkbookFactory.create(input)) {
                        var sheet = workbook.getSheet("数据");
                        assertEquals("id", sheet.getRow(0).getCell(0).getStringCellValue());
                        assertEquals("7", sheet.getRow(1).getCell(0).getStringCellValue());
                    }
                }
            }
            assertFalse(zip.stream().anyMatch(entry -> entry.getName().endsWith(".csv")));
        }
        verify(sql, times(data ? 2 : 0)).queryTable(any(), anyMap(), any(), anyInt(), anyInt(), anyList(), anyList(), anyList(), anyBoolean(), isNull());
    }

    @Test void resumesDatabaseExportsFromTheLastCompletedTable() throws Exception {
        var pauseOnce = new AtomicBoolean();
        var task = startDatabase(true, true);
        when(sql.queryTable(any(), anyMap(), any(), anyInt(), anyInt(), anyList(), anyList(), anyList(), anyBoolean(), isNull()))
                .thenAnswer(call -> {
                    if (orders.equals(call.getArgument(2)) && pauseOnce.compareAndSet(false, true))
                        service.pause("owner", id(task));
                    return Map.of("rows", List.of(Map.of("id", 7)), "pagination", Map.of("total", 1));
                });
        queued.get(0).run();
        var paused = service.progress("owner", id(task));
        assertEquals("PAUSED", paused.get("status"));
        // Refs sort by full object identity: customers is complete before orders is paused.
        assertEquals(1, paused.get("processedTables"));
        service.resume(node, "owner", "session", id(task), Map.of());
        queued.get(1).run();
        assertEquals("COMPLETED", service.progress("owner", id(task)).get("status"));
        verify(sql, times(1)).queryTable(eq(node), anyMap(), eq(customers), anyInt(), anyInt(), anyList(), anyList(), anyList(), anyBoolean(), isNull());
        verify(sql, times(2)).queryTable(eq(node), anyMap(), eq(orders), anyInt(), anyInt(), anyList(), anyList(), anyList(), anyBoolean(), isNull());
    }

    @Test void emptyDatabasesStillProduceAManifestWithTheDefaultExportOptions() throws Exception {
        var namespace = new SqlObjectRef("app", "sales", null, "schema");
        when(sql.getTables(node, Map.of(), namespace)).thenReturn(Map.of("tables", List.of()));
        var task = service.startDatabaseExport(node, "owner", "session", Map.of(), namespace, List.of(), null, null);
        queued.get(0).run();
        var result = service.progress("owner", id(task));
        assertEquals("COMPLETED", result.get("status"));
        assertEquals(0, result.get("tableCount"));
        assertEquals(true, result.get("includeStructure"));
        assertEquals(true, result.get("includeData"));
        Path output = root.resolve("users/owner").resolve((String) result.get("downloadPath"));
        try (var zip = new ZipFile(output.toFile())) {
            assertEquals(List.of("manifest.json"), zip.stream().map(entry -> entry.getName()).toList());
        }
        verify(sql, never()).getTableColumns(any(), anyMap(), any());
    }

    @ParameterizedTest
    @CsvSource({"table,false", "table,true", "database,false", "database,true"})
    void lateProgressOrFailureNeverRevivesAnExportCancelledDuringAQuery(String kind, boolean fail) throws Exception {
        var task = kind.equals("table") ? startTable() : startDatabase(true, true);
        when(sql.queryTable(any(), anyMap(), any(), anyInt(), anyInt(), anyList(), anyList(), anyList(), anyBoolean(), isNull()))
                .thenAnswer(call -> {
                    service.stop("owner", id(task));
                    if (fail) throw new IllegalStateException("fixture query failure after cancellation");
                    return Map.of("rows", List.of(Map.of("id", 7)), "pagination", Map.of("total", 1));
                });
        queued.get(0).run();
        assertEquals("CANCELLED", service.progress("owner", id(task)).get("status"));
        assertEquals("CANCELLED", repository.load(new SqlExportTaskKey("owner", id(task))).get("status"));
    }

    @Test void reportsQueueRejectionThroughTheTaskContract() throws Exception {
        when(executor.submitSqlExport(any(Runnable.class))).thenThrow(new RejectedExecutionException("fixture busy"));
        assertEquals("FAILED", startTable().get("status"));
        assertEquals(0, queued.size());
        verifyNoInteractions(sql);
    }

    @ParameterizedTest
    @ValueSource(strings = {"table", "database"})
    void failedQueriesRemainFailedUntilAnExplicitResume(String kind) throws Exception {
        var task = kind.equals("table") ? startTable() : startDatabase(true, true);
        when(sql.queryTable(any(), anyMap(), any(), anyInt(), anyInt(), anyList(), anyList(), anyList(), anyBoolean(), isNull()))
                .thenThrow(new IllegalStateException("fixture query failure"));
        queued.get(0).run();
        var failed = service.progress("owner", id(task));
        assertEquals("FAILED", failed.get("status"));
        assertEquals("fixture query failure", failed.get("error"));
        assertNotNull(failed.get("endTime"));
        doReturn(Map.of("rows", List.of(Map.of("id", 7)), "pagination", Map.of("total", 1)))
                .when(sql).queryTable(any(), anyMap(), any(), anyInt(), anyInt(), anyList(), anyList(), anyList(), anyBoolean(), isNull());
        service.resume(node, "owner", "new-session", id(task), Map.of());
        queued.get(1).run();
        assertEquals("COMPLETED", service.progress("owner", id(task)).get("status"));
        assertEquals(1, service.listBySessionId("owner", "new-session").get("count"));
        assertEquals(0, service.listBySessionId("owner", "session").get("count"));
    }

    @ParameterizedTest
    @CsvSource({"table,restore", "table,workbook", "database,restore", "database,workspace", "database,json", "database,zip"})
    void artifactFailuresUseTheSamePersistedTaskFailureContract(String kind, String stage) throws Exception {
        var task = kind.equals("table") ? startTable() : startDatabase(true, true);
        String error = "fixture artifact failure: " + stage;
        switch (stage) {
            case "restore" -> doThrow(new IllegalArgumentException(error)).when(artifacts).restoreOutput(anyString(), anyString());
            case "workspace" -> doThrow(new IllegalArgumentException(error)).when(artifacts).workDirectory(any());
            case "workbook" -> doThrow(new IOException(error)).when(artifacts).openWorkbook(any());
            case "json" -> doThrow(new IOException(error)).when(artifacts).writeJson(any(), any());
            case "zip" -> doThrow(new IOException(error)).when(artifacts).zipDirectory(any(), any());
            default -> throw new AssertionError(stage);
        }

        assertDoesNotThrow(() -> queued.get(0).run());

        var result = service.progress("owner", id(task));
        assertEquals("FAILED", result.get("status"));
        assertEquals(error, result.get("error"));
        assertNotNull(result.get("endTime"));
        assertEquals("FAILED", repository.load(new SqlExportTaskKey("owner", id(task))).get("status"));
        if (stage.equals("restore") || stage.equals("workspace")) verifyNoInteractions(sql);
    }

    @ParameterizedTest
    @ValueSource(strings = {"finalPath", "taskType"})
    void rejectsInvalidRestoredTasksBeforeQuerying(String field) throws Exception {
        var task = startTable();
        service.pause("owner", id(task));
        queued.get(0).run();
        var stored = repository.load(new SqlExportTaskKey("owner", id(task)));
        stored.put(field, field.equals("finalPath") ? root.resolve("outside.xlsx").toString() : "unknown");
        repository.save(new SqlExportTaskKey("owner", id(task)), stored);
        // A fresh service ensures the persisted task is used rather than the existing cache.
        var restarted = new SqlExportService(sql, executor, repository, artifacts, new SqlTableExporter(sql));
        try {
            restarted.resume(node, "owner", "session", id(task), Map.of());
            queued.get(1).run();
            assertEquals("FAILED", restarted.progress("owner", id(task)).get("status"));
            assertEquals("FAILED", repository.load(new SqlExportTaskKey("owner", id(task))).get("status"));
            assertFalse(Files.exists(root.resolve("outside.xlsx")));
            verifyNoInteractions(sql);
        } finally { restarted.close(); }
    }

    @Test void springWiresTheLifecycleAgainstPortsAndTheSharedWorkspaceAdapters() throws Exception {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(AgentNodeSqlService.class, () -> sql);
            context.registerBean(ServiceTaskExecutor.class, () -> executor);
            context.registerBean(SqlExportWorkspace.class, () -> workspace);
            context.register(FileSqlExportTaskRepository.class, FileSqlExportArtifacts.class,
                    SqlTableExporter.class, SqlExportService.class);
            context.refresh();
            var wired = context.getBean(SqlExportService.class);
            var task = wired.startTableExport(node, "owner", "session", Map.of(), orders);
            assertEquals("PENDING", context.getBean(SqlExportTaskRepository.class)
                    .load(new SqlExportTaskKey("owner", id(task))).get("status"));
            assertTrue(context.getBean(SqlExportArtifacts.class) instanceof FileSqlExportArtifacts);
        }
    }

    private Map<String, Object> startTable() throws Exception {
        return service.startTableExport(node, "owner", "session", Map.of(), orders);
    }

    private Map<String, Object> startDatabase(Boolean structure, Boolean data) throws Exception {
        return service.startDatabaseExport(node, "owner", "session", Map.of(),
                new SqlObjectRef("app", "sales", null, "schema"), List.of(orders, customers), structure, data);
    }

    private String id(Map<String, Object> task) { return (String) task.get("taskId"); }
}
