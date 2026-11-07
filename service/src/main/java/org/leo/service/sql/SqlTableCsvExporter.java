package org.leo.service.sql;

import org.leo.core.agent.capability.SqlCapable;
import org.springframework.stereotype.Service;

import java.io.BufferedWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** Streams paginated query results into CSV; it owns neither task state nor the output file lifecycle. */
@Service
public final class SqlTableCsvExporter {
    private static final int PAGE_SIZE = 1000;
    private final AgentNodeSqlService sql;

    public SqlTableCsvExporter(AgentNodeSqlService sql) {
        this.sql = sql;
    }

    public interface Progress {
        boolean stopped();
        void rowsWritten(long rows, long expectedTotal);
    }

    @SuppressWarnings("unchecked")
    public long write(BufferedWriter writer, SqlCapable node, Map<String, Object> connection,
                      SqlObjectRef table, List<Map<String, Object>> columns, Progress progress) throws Exception {
        List<String> header = columnNames(columns);
        if (!header.isEmpty()) writeLine(writer, header);
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
                if (!header.isEmpty() && totalRows == 0L) writeLine(writer, header);
            }
            if (rows == null || rows.isEmpty()) break;
            for (Map<String, Object> row : rows) {
                List<String> values = new ArrayList<>(header.size());
                for (String key : header) {
                    Object value = row.get(key);
                    values.add(value == null ? "" : String.valueOf(value));
                }
                writeLine(writer, values);
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

    private static void writeLine(BufferedWriter writer, List<String> values) throws Exception {
        List<String> escaped = new ArrayList<>(values.size());
        for (String value : values) escaped.add("\"" + (value == null ? "" : value).replace("\"", "\"\"") + "\"");
        writer.write(String.join(",", escaped));
        writer.newLine();
    }

    private static long longValue(Object value) {
        if (value instanceof Number number) return number.longValue();
        try { return Long.parseLong(String.valueOf(value)); }
        catch (NumberFormatException ignored) { return 0L; }
    }
}
