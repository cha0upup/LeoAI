package org.leo.service.sql;

import org.apache.poi.ss.SpreadsheetVersion;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.streaming.SXSSFSheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.leo.core.agent.capability.SqlCapable;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** Writes paged table data to a workbook without owning task or file state. */
@Service
public final class SqlTableExporter {
    private static final int PAGE_SIZE = 1000;
    private final AgentNodeSqlService sql;

    public SqlTableExporter(AgentNodeSqlService sql) {
        this.sql = sql;
    }

    public interface Progress {
        boolean stopped();
        void rowsWritten(long rows, long expectedTotal);
    }

    public long writeWorkbook(OutputStream output, SqlCapable node, Map<String, Object> connection,
                              SqlObjectRef table, List<Map<String, Object>> columns, Progress progress) throws Exception {
        if (progress.stopped()) return 0L;
        try (SXSSFWorkbook workbook = new SXSSFWorkbook(100)) {
            workbook.setCompressTempFiles(true);
            long total;
            try {
                total = writeRows(node, connection, table, columns, progress, new WorkbookRows(workbook));
            } catch (Exception failure) {
                try {
                    flushPendingSheets(workbook);
                } catch (IOException flushError) {
                    failure.addSuppressed(flushError);
                }
                throw failure;
            }
            if (progress.stopped()) flushPendingSheets(workbook);
            else workbook.write(output);
            return total;
        }
    }

    private static void flushPendingSheets(SXSSFWorkbook workbook) throws IOException {
        // When discarding an unfinished workbook, flush before close releases the sheet writers.
        for (int index = 0; index < workbook.getNumberOfSheets(); index++) {
            workbook.getSheetAt(index).flushRows();
        }
    }

    @SuppressWarnings("unchecked")
    private long writeRows(SqlCapable node, Map<String, Object> connection, SqlObjectRef table,
                           List<Map<String, Object>> columns, Progress progress, WorkbookRows writer) throws Exception {
        List<String> header = columnNames(columns);
        if (!header.isEmpty()) writer.writeHeader(header);
        long totalRows = 0L;
        long expectedTotal = 0L;
        for (int page = 1; ; page++) {
            if (progress.stopped()) return totalRows;
            Map<String, Object> result = sql.queryTable(node, connection, table, page, PAGE_SIZE,
                    header, Collections.emptyList(), Collections.emptyList(), page == 1, null);
            List<Map<String, Object>> rows = (List<Map<String, Object>>) result.get("rows");
            Map<String, Object> pagination = (Map<String, Object>) result.get("pagination");
            if (pagination != null && pagination.get("total") != null) expectedTotal = longValue(pagination.get("total"));
            if (header.isEmpty()) {
                header.addAll(columnNames((List<Map<String, Object>>) result.get("columns")));
                if (!header.isEmpty() && totalRows == 0L) writer.writeHeader(header);
            }
            if (rows == null || rows.isEmpty()) break;
            for (Map<String, Object> row : rows) {
                writer.writeDataRow(header, row);
                totalRows++;
            }
            if (expectedTotal > 0L) progress.rowsWritten(totalRows, expectedTotal);
            if (rows.size() < PAGE_SIZE) break;
        }
        return totalRows;
    }

    private static List<String> columnNames(List<Map<String, Object>> columns) {
        List<String> names = new ArrayList<>();
        if (columns == null) return names;
        for (Map<String, Object> column : columns) {
            Object name = column.get("label");
            if (name == null || String.valueOf(name).isBlank()) name = column.get("name");
            if (name != null && !String.valueOf(name).isBlank()) names.add(String.valueOf(name));
        }
        return names;
    }

    private static final class WorkbookRows {
        private final SXSSFWorkbook workbook;
        private SXSSFSheet sheet;
        private List<String> header = List.of();
        private int sheetNumber;
        private int nextRow;

        private WorkbookRows(SXSSFWorkbook workbook) {
            this.workbook = workbook;
            addSheet();
        }

        private void writeHeader(List<String> columns) {
            header = List.copyOf(columns);
            appendHeader();
        }

        private void writeDataRow(List<String> columns, Map<String, Object> values) {
            if (nextRow == SpreadsheetVersion.EXCEL2007.getMaxRows()) {
                addSheet();
                appendHeader();
            }
            Row row = createRow(columns.size());
            for (int column = 0; column < columns.size(); column++) {
                Object value = values.get(columns.get(column));
                // 保留数据库文本的精度，且不将源数据解释为公式。
                row.createCell(column).setCellValue(value == null ? "" : String.valueOf(value));
            }
        }

        private void addSheet() {
            sheetNumber++;
            sheet = workbook.createSheet(sheetNumber == 1 ? "数据" : "数据 " + sheetNumber);
            nextRow = 0;
        }

        private void appendHeader() {
            Row row = createRow(header.size());
            for (int column = 0; column < header.size(); column++) {
                row.createCell(column).setCellValue(header.get(column));
            }
        }

        private Row createRow(int columnCount) {
            if (columnCount > SpreadsheetVersion.EXCEL2007.getMaxColumns()) {
                throw new IllegalArgumentException("导出列数超过 XLSX 工作表上限");
            }
            return sheet.createRow(nextRow++);
        }
    }

    private static long longValue(Object value) {
        if (value instanceof Number number) return number.longValue();
        try { return Long.parseLong(String.valueOf(value)); }
        catch (NumberFormatException ignored) { return 0L; }
    }
}
