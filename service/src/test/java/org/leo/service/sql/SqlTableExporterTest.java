package org.leo.service.sql;

import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.Test;
import org.leo.core.agent.capability.SqlCapable;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SqlTableExporterTest {
    private final AgentNodeSqlService sql = mock(AgentNodeSqlService.class);
    private final SqlCapable node = mock(SqlCapable.class);
    private final SqlObjectRef table = SqlObjectRef.table("app", "public", "orders");
    private final SqlTableExporter exporter = new SqlTableExporter(sql);
    private final ByteArrayOutputStream output = new ByteArrayOutputStream();
    private final RecordingProgress progress = new RecordingProgress();

    @Test void preservesColumnLabelsOrderingUnicodeAndEmptyCells() throws Exception {
        var columns = List.<Map<String, Object>>of(Map.of("label", " ", "name", "id"),
                Map.of("label", "display", "name", "hidden"), Map.of("name", "empty"), Map.of("ignored", true));
        var row = new LinkedHashMap<String, Object>();
        row.put("display", "中文,\"quoted\"\nnext"); row.put("id", 7); row.put("empty", null);
        when(sql.queryTable(any(), anyMap(), any(), anyInt(), anyInt(), anyList(), anyList(), anyList(), anyBoolean(), isNull()))
                .thenReturn(Map.of("rows", List.of(row), "pagination", Map.of("total", "1")));
        assertEquals(1, exporter.writeWorkbook(output, node, Map.of(), table, columns, progress));
        try (var workbook = readWorkbook()) {
            var sheet = workbook.getSheetAt(0);
            assertEquals("id", sheet.getRow(0).getCell(0).getStringCellValue());
            assertEquals("display", sheet.getRow(0).getCell(1).getStringCellValue());
            assertEquals("empty", sheet.getRow(0).getCell(2).getStringCellValue());
            assertEquals("7", sheet.getRow(1).getCell(0).getStringCellValue());
            assertEquals("中文,\"quoted\"\nnext", sheet.getRow(1).getCell(1).getStringCellValue());
            assertEquals("", sheet.getRow(1).getCell(2).getStringCellValue());
        }
        assertEquals(List.of(List.of(1L, 1L)), progress.updates);
        verify(sql).queryTable(node, Map.of(), table, 1, 1000, List.of("id", "display", "empty"),
                List.of(), List.of(), true, null);
    }

    @Test void learnsMissingHeadersFromTheFirstPageExactlyOnce() throws Exception {
        when(sql.queryTable(any(), anyMap(), any(), anyInt(), anyInt(), anyList(), anyList(), anyList(), anyBoolean(), isNull()))
                .thenReturn(Map.of("columns", List.of(Map.of("name", "id")), "rows", List.of(Map.of("id", 1))));
        assertEquals(1, exporter.writeWorkbook(output, node, Map.of(), table, null, progress));
        try (var workbook = readWorkbook()) {
            var sheet = workbook.getSheetAt(0);
            assertEquals("id", sheet.getRow(0).getCell(0).getStringCellValue());
            assertEquals("1", sheet.getRow(1).getCell(0).getStringCellValue());
        }
        assertTrue(progress.updates.isEmpty());
    }

    @Test void streamsFullPagesAndRequestsTheTotalOnlyOnTheFirstPage() throws Exception {
        when(sql.queryTable(any(), anyMap(), any(), anyInt(), anyInt(), anyList(), anyList(), anyList(), anyBoolean(), isNull()))
                .thenAnswer(call -> call.<Integer>getArgument(3) == 1
                        ? Map.of("rows", Collections.nCopies(1000, Map.of("id", 1)), "pagination", Map.of("total", 1001))
                        : Map.of("rows", List.of(Map.of("id", 2))));
        assertEquals(1001, exporter.writeWorkbook(output, node, Map.of(), table, List.of(Map.of("name", "id")), progress));
        try (var workbook = readWorkbook()) {
            var sheet = workbook.getSheetAt(0);
            assertEquals(1002, sheet.getPhysicalNumberOfRows());
            assertEquals("2", sheet.getRow(1001).getCell(0).getStringCellValue());
        }
        assertEquals(List.of(List.of(1000L, 1001L), List.of(1001L, 1001L)), progress.updates);
        verify(sql).queryTable(node, Map.of(), table, 1, 1000, List.of("id"), List.of(), List.of(), true, null);
        verify(sql).queryTable(node, Map.of(), table, 2, 1000, List.of("id"), List.of(), List.of(), false, null);
    }

    @Test void emitsHeadersForAnEmptyTable() throws Exception {
        when(sql.queryTable(any(), anyMap(), any(), anyInt(), anyInt(), anyList(), anyList(), anyList(), anyBoolean(), isNull()))
                .thenReturn(Map.of());
        assertEquals(0, exporter.writeWorkbook(output, node, Map.of(), table, List.of(Map.of("name", "id")), progress));
        try (var workbook = readWorkbook()) {
            var sheet = workbook.getSheetAt(0);
            assertEquals(1, sheet.getPhysicalNumberOfRows());
            assertEquals("id", sheet.getRow(0).getCell(0).getStringCellValue());
        }
    }

    @Test void observesPauseBeforeQueryingOrRequestingTheNextPage() throws Exception {
        progress.stopped = true;
        assertEquals(0, exporter.writeWorkbook(output, node, Map.of(), table, List.of(), progress));
        verifyNoInteractions(sql);
        progress.stopped = false; progress.stopAfterUpdate = true;
        when(sql.queryTable(any(), anyMap(), any(), anyInt(), anyInt(), anyList(), anyList(), anyList(), anyBoolean(), isNull()))
                .thenReturn(Map.of("rows", Collections.nCopies(1000, Map.of("id", 1)), "pagination", Map.of("total", 2000)));
        assertEquals(1000, exporter.writeWorkbook(output, node, Map.of(), table, List.of(Map.of("name", "id")), progress));
        assertEquals(0, output.size());
        verify(sql, times(1)).queryTable(any(), anyMap(), any(), anyInt(), anyInt(), anyList(), anyList(), anyList(), anyBoolean(), isNull());
    }

    @Test void propagatesQueryFailuresWithoutInventingCompletion() throws Exception {
        var failure = new IllegalStateException("fixture query failed");
        when(sql.queryTable(any(), anyMap(), any(), anyInt(), anyInt(), anyList(), anyList(), anyList(), anyBoolean(), isNull()))
                .thenThrow(failure);
        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> exporter.writeWorkbook(output, node, Map.of(), table, List.of(), progress)));
        assertTrue(progress.updates.isEmpty());
    }

    private Workbook readWorkbook() throws Exception {
        return WorkbookFactory.create(new ByteArrayInputStream(output.toByteArray()));
    }

    private static final class RecordingProgress implements SqlTableExporter.Progress {
        boolean stopped, stopAfterUpdate;
        final List<List<Long>> updates = new ArrayList<>();
        @Override public boolean stopped() { return stopped; }
        @Override public void rowsWritten(long rows, long expectedTotal) {
            updates.add(List.of(rows, expectedTotal));
            if (stopAfterUpdate) stopped = true;
        }
    }
}
