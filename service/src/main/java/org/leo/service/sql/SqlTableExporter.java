package org.leo.service.sql;

import org.apache.poi.ss.SpreadsheetVersion;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.streaming.SXSSFSheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.leo.core.agent.capability.SqlCapable;
import org.springframework.stereotype.Service;

import java.io.BufferedWriter;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** Streams the same table rows to workbooks or legacy CSV without owning task or file state. */
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

    public long writeCsv(BufferedWriter writer, SqlCapable node, Map<String, Object> connection,
                         SqlObjectRef table, List<Map<String, Object>> columns, Progress progress) throws Exception {
        return writeRows(node, connection, table, columns, progress, values -> writeCsvLine(writer, values));
    }

    public long writeWorkbook(OutputStream output, SqlCapable node, Map<String, Object> connection,
                              SqlObjectRef table, List<Map<String, Object>> columns, Progress progress) throws Exception {
        try (SXSSFWorkbook workbook = new SXSSFWorkbook(100)) {
            workbook.setCompressTempFiles(true);
            WorkbookRows sheets = new WorkbookRows(workbook);
            long total = writeRows(node, connection, table, columns, progress, sheets::write);
            if (!progress.stopped()) workbook.write(output);
            return total;
        }
    }

    @FunctionalInterface
    private interface RowWriter {
        void write(List<String> values) throws Exception;
    }

    @SuppressWarnings("unchecked")
    private long writeRows(SqlCapable node, Map<String, Object> connection, SqlObjectRef table,
                           List<Map<String, Object>> columns, Progress progress, RowWriter writer) throws Exception {
        List<String> header = columnNames(columns);
        if (!header.isEmpty()) writer.write(header);
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
                if (!header.isEmpty() && totalRows == 0L) writer.write(header);
            }
            if (rows == null || rows.isEmpty()) break;
            for (Map<String, Object> row : rows) {
                List<String> values = new ArrayList<>(header.size());
                for (String key : header) {
                    Object value = row.get(key);
                    values.add(value == null ? "" : String.valueOf(value));
                }
                writer.write(values);
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

    private static void writeCsvLine(BufferedWriter writer, List<String> values) throws Exception {
        String separator = "";
        for (String value : values) {
            writer.append(separator).append('"')
                    .append(value == null ? "" : value.replace("\"", "\"\"")).append('"');
            separator = ",";
        }
        writer.newLine();
    }

    private static final class WorkbookRows {
        private final SXSSFWorkbook workbook;
        private SXSSFSheet sheet;
        private List<String> header;
        private int sheetNumber;
        private int nextRow;

        private WorkbookRows(SXSSFWorkbook workbook) {
            this.workbook = workbook;
            addSheet();
        }

        private void write(List<String> values) {
            if (header == null) header = List.copyOf(values);
            if (nextRow == SpreadsheetVersion.EXCEL2007.getMaxRows()) {
                addSheet();
                appendRow(header);
            }
            appendRow(values);
        }

        private void addSheet() {
            sheetNumber++;
            sheet = workbook.createSheet(sheetNumber == 1 ? "数据" : "数据 " + sheetNumber);
            nextRow = 0;
        }

        private void appendRow(List<String> values) {
            if (values.size() > SpreadsheetVersion.EXCEL2007.getMaxColumns()) {
                throw new IllegalArgumentException("导出列数超过 XLSX 工作表上限");
            }
            Row row = sheet.createRow(nextRow++);
            for (int column = 0; column < values.size(); column++) {
                // 保留数据库文本的精度，且不将源数据解释为公式。
                row.createCell(column).setCellValue(values.get(column));
            }
        }
    }

    private static long longValue(Object value) {
        if (value instanceof Number number) return number.longValue();
        try { return Long.parseLong(String.valueOf(value)); }
        catch (NumberFormatException ignored) { return 0L; }
    }
}
