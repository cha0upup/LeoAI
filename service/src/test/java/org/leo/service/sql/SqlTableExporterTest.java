package org.leo.service.sql;

import org.junit.jupiter.api.Test;
import org.leo.core.agent.capability.SqlCapable;

import java.io.BufferedWriter;
import java.io.StringWriter;
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
    private final StringWriter output = new StringWriter();
    private final BufferedWriter writer = new BufferedWriter(output);
    private final RecordingProgress progress = new RecordingProgress();

    @Test void preservesColumnLabelsOrderingUnicodeNullsAndCsvEscaping() throws Exception {
        var columns = List.<Map<String, Object>>of(Map.of("label", " ", "name", "id"),
                Map.of("label", "display", "name", "hidden"), Map.of("name", "empty"), Map.of("ignored", true));
        var row = new LinkedHashMap<String, Object>();
        row.put("display", "中文,\"quoted\"\nnext"); row.put("id", 7); row.put("empty", null);
        when(sql.queryTable(any(), anyMap(), any(), anyInt(), anyInt(), anyList(), anyList(), anyList(), anyBoolean(), isNull()))
                .thenReturn(Map.of("rows", List.of(row), "pagination", Map.of("total", "1")));
        assertEquals(1, exporter.writeCsv(writer, node, Map.of(), table, columns, progress));
        writer.flush();
        String nl = System.lineSeparator();
        assertEquals("\"id\",\"display\",\"empty\"" + nl + "\"7\",\"中文,\"\"quoted\"\"\nnext\",\"\"" + nl, output.toString());
        assertEquals(List.of(List.of(1L, 1L)), progress.updates);
        verify(sql).queryTable(node, Map.of(), table, 1, 1000, List.of("id", "display", "empty"),
                List.of(), List.of(), true, null);
    }

    @Test void learnsMissingHeadersFromTheFirstPageExactlyOnce() throws Exception {
        when(sql.queryTable(any(), anyMap(), any(), anyInt(), anyInt(), anyList(), anyList(), anyList(), anyBoolean(), isNull()))
                .thenReturn(Map.of("columns", List.of(Map.of("name", "id")), "rows", List.of(Map.of("id", 1))));
        assertEquals(1, exporter.writeCsv(writer, node, Map.of(), table, null, progress));
        writer.flush();
        assertEquals("\"id\"" + System.lineSeparator() + "\"1\"" + System.lineSeparator(), output.toString());
        assertTrue(progress.updates.isEmpty());
    }

    @Test void streamsFullPagesAndRequestsTheTotalOnlyOnTheFirstPage() throws Exception {
        when(sql.queryTable(any(), anyMap(), any(), anyInt(), anyInt(), anyList(), anyList(), anyList(), anyBoolean(), isNull()))
                .thenAnswer(call -> call.<Integer>getArgument(3) == 1
                        ? Map.of("rows", Collections.nCopies(1000, Map.of("id", 1)), "pagination", Map.of("total", 1001))
                        : Map.of("rows", List.of(Map.of("id", 2))));
        assertEquals(1001, exporter.writeCsv(writer, node, Map.of(), table, List.of(Map.of("name", "id")), progress));
        writer.flush();
        assertEquals(1002, output.toString().lines().count());
        assertEquals(List.of(List.of(1000L, 1001L), List.of(1001L, 1001L)), progress.updates);
        verify(sql).queryTable(node, Map.of(), table, 1, 1000, List.of("id"), List.of(), List.of(), true, null);
        verify(sql).queryTable(node, Map.of(), table, 2, 1000, List.of("id"), List.of(), List.of(), false, null);
    }

    @Test void emitsHeadersForAnEmptyTableWithoutClosingTheCallersWriter() throws Exception {
        when(sql.queryTable(any(), anyMap(), any(), anyInt(), anyInt(), anyList(), anyList(), anyList(), anyBoolean(), isNull()))
                .thenReturn(Map.of());
        assertEquals(0, exporter.writeCsv(writer, node, Map.of(), table, List.of(Map.of("name", "id")), progress));
        writer.write("caller still owns writer"); writer.flush();
        assertEquals("\"id\"" + System.lineSeparator() + "caller still owns writer", output.toString());
    }

    @Test void observesPauseBeforeQueryingOrRequestingTheNextPage() throws Exception {
        progress.stopped = true;
        assertEquals(0, exporter.writeCsv(writer, node, Map.of(), table, List.of(), progress));
        verifyNoInteractions(sql);
        progress.stopped = false; progress.stopAfterUpdate = true;
        when(sql.queryTable(any(), anyMap(), any(), anyInt(), anyInt(), anyList(), anyList(), anyList(), anyBoolean(), isNull()))
                .thenReturn(Map.of("rows", Collections.nCopies(1000, Map.of("id", 1)), "pagination", Map.of("total", 2000)));
        assertEquals(1000, exporter.writeCsv(writer, node, Map.of(), table, List.of(Map.of("name", "id")), progress));
        verify(sql, times(1)).queryTable(any(), anyMap(), any(), anyInt(), anyInt(), anyList(), anyList(), anyList(), anyBoolean(), isNull());
    }

    @Test void propagatesQueryFailuresWithoutInventingCompletion() throws Exception {
        var failure = new IllegalStateException("fixture query failed");
        when(sql.queryTable(any(), anyMap(), any(), anyInt(), anyInt(), anyList(), anyList(), anyList(), anyBoolean(), isNull()))
                .thenThrow(failure);
        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> exporter.writeCsv(writer, node, Map.of(), table, List.of(), progress)));
        assertTrue(progress.updates.isEmpty());
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
