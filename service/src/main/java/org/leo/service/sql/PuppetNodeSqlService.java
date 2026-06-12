package org.leo.service.sql;

import org.leo.core.puppet.impl.JavaPuppetNode;
import org.leo.service.sql.dialect.AbstractSqlDialect;
import org.leo.service.sql.dialect.SqlDialectFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class PuppetNodeSqlService {

    private final SqlDialectFactory sqlDialectFactory;

    @Autowired
    public PuppetNodeSqlService(SqlDialectFactory sqlDialectFactory) {
        this.sqlDialectFactory = sqlDialectFactory;
    }

    public Map<String, Object> testConnection(JavaPuppetNode puppetNode, Map<String, Object> connection) throws Exception {
        AbstractSqlDialect dialect = dialect(connection);
        String testSql = dialect.buildTestSql();
        Map<String, Object> raw = executeRaw(puppetNode, connection, testSql);
        List<Map<String, Object>> rowList = rows(raw);
        String version = "";
        if (!rowList.isEmpty()) {
            Object firstValue = rowList.get(0).values().stream().findFirst().orElse("");
            version = firstValue == null ? "" : String.valueOf(firstValue);
        }

        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("databaseVersion", version);
        data.put("driverVersion", stringValue(connection.get("driver")));
        data.put("latencyMs", null);
        data.put("statementType", detectStatementType(testSql));
        return data;
    }

    public List<Map<String, Object>> getDialects() {
        return sqlDialectFactory.getDialectInfos();
    }

    public List<Map<String, Object>> getDataTypes(String type) {
        return sqlDialectFactory.require(type).getDataTypes();
    }

    public Map<String, Object> getDatabases(JavaPuppetNode puppetNode, Map<String, Object> connection) throws Exception {
        AbstractSqlDialect dialect = dialect(connection);
        Map<String, Object> raw = executeRaw(puppetNode, connection, dialect.buildDatabasesSql());
        List<Map<String, Object>> databases = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> row : rows(raw)) {
            String name = firstNonEmpty(row, "name", "database", "DATABASE", "TABLE_CAT", "TABLE_SCHEM");
            if (name == null || name.isBlank()) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("name", name);
            databases.add(item);
        }
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("databases", databases);
        return data;
    }

    public Map<String, Object> getTables(JavaPuppetNode puppetNode, Map<String, Object> connection, String database) throws Exception {
        AbstractSqlDialect dialect = dialect(connection);
        Map<String, Object> raw = executeRaw(puppetNode, connection, dialect.buildTablesSql(database));
        List<Map<String, Object>> tables = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> row : rows(raw)) {
            String tableName = firstNonEmpty(row, "name", "table_name", "TABLE_NAME");
            if (tableName == null || tableName.isBlank()) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("name", tableName);
            item.put("schema", firstNonEmpty(row, "schema", "schema_name", "table_schema", "TABLE_SCHEM"));
            item.put("comment", safeString(firstValue(row, "comment", "remarks", "table_comment", "TABLE_COMMENT")));
            item.put("rowCount", queryTableCount(puppetNode, connection, dialect, database, tableName));
            tables.add(item);
        }
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("tables", tables);
        return data;
    }

    public Map<String, Object> getTableColumns(JavaPuppetNode puppetNode, Map<String, Object> connection, String database, String table) throws Exception {
        AbstractSqlDialect dialect = dialect(connection);
        Map<String, Object> raw = executeRaw(puppetNode, connection, dialect.buildTableColumnsSql(database, table));
        List<Map<String, Object>> columns = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> row : rows(raw)) {
            Object nullableValue = firstValue(row, "nullable", "is_nullable", "NULLABLE");
            if (nullableValue == null && firstValue(row, "notnull") != null) {
                nullableValue = !"1".equals(String.valueOf(firstValue(row, "notnull")).trim());
            }
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("name", firstNonEmpty(row, "name", "column_name", "COLUMN_NAME"));
            item.put("type", safeString(firstValue(row, "type", "data_type", "TYPE_NAME", "column_type")));
            item.put("nullable", toBoolean(nullableValue));
            item.put("defaultValue", firstValue(row, "defaultValue", "default_value", "column_default", "COLUMN_DEF", "dflt_value"));
            item.put("comment", safeString(firstValue(row, "comment", "remarks", "column_comment")));
            item.put("length", toNullableInteger(firstValue(row, "length", "character_maximum_length", "COLUMN_SIZE", "max_length")));
            item.put("precision", toNullableInteger(firstValue(row, "precision", "numeric_precision", "data_precision")));
            item.put("scale", toNullableInteger(firstValue(row, "scale", "numeric_scale", "DECIMAL_DIGITS", "data_scale")));
            item.put("primaryKey", toBoolean(firstValue(row, "primaryKey", "primary_key", "column_key", "pk")));
            columns.add(item);
        }
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("columns", columns);
        return data;
    }

    public Map<String, Object> queryTable(JavaPuppetNode puppetNode,
                                          Map<String, Object> connection,
                                          String database,
                                          String table,
                                          Integer page,
                                          Integer pageSize,
                                          List<String> columns,
                                          List<Map<String, Object>> orderBy,
                                          List<Map<String, Object>> filters) throws Exception {
        AbstractSqlDialect dialect = dialect(connection);
        int actualPage = page == null || page < 1 ? 1 : page;
        int actualPageSize = pageSize == null || pageSize < 1 ? 20 : pageSize;
        Map<String, Object> rawQuery = executeRaw(puppetNode, connection,
                dialect.buildQueryTableSql(database, table, actualPage, actualPageSize, columns, orderBy, filters));
        Map<String, Object> rawCount = executeRaw(puppetNode, connection, dialect.buildCountSql(database, table, filters));

        Map<String, Object> data = normalizeQueryResult(rawQuery, "SELECT");
        Map<String, Object> pagination = new LinkedHashMap<String, Object>();
        pagination.put("page", actualPage);
        pagination.put("pageSize", actualPageSize);
        pagination.put("total", extractCount(rawCount));
        data.put("pagination", pagination);
        return data;
    }

    public Map<String, Object> executeSql(JavaPuppetNode puppetNode, Map<String, Object> connection, String sql) throws Exception {
        Map<String, Object> raw = executeRaw(puppetNode, connection, sql);
        return normalizeQueryResult(raw, detectStatementType(sql));
    }

    public Map<String, Object> createTable(JavaPuppetNode puppetNode,
                                           Map<String, Object> connection,
                                           String database,
                                           String table,
                                           List<Map<String, Object>> columns) throws Exception {
        AbstractSqlDialect dialect = dialect(connection);
        String sql = dialect.buildCreateTableSql(database, table, columns);
        Map<String, Object> raw = executeRaw(puppetNode, connection, sql);
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("table", table);
        data.put("database", database);
        data.put("sql", sql);
        data.put("affectedRows", toInteger(raw.get("updateCount")));
        return data;
    }

    public Map<String, Object> createDatabase(JavaPuppetNode puppetNode, Map<String, Object> connection, String database) throws Exception {
        AbstractSqlDialect dialect = dialect(connection);
        String sql = dialect.buildCreateDatabaseSql(database);
        Map<String, Object> raw = executeRaw(puppetNode, connection, sql);
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("database", database);
        data.put("sql", sql);
        data.put("affectedRows", toInteger(raw.get("updateCount")));
        return data;
    }

    public Map<String, Object> insertRow(JavaPuppetNode puppetNode,
                                         Map<String, Object> connection,
                                         String database,
                                         String table,
                                         Map<String, Object> row) throws Exception {
        AbstractSqlDialect dialect = dialect(connection);
        String sql = dialect.buildInsertSql(database, table, row);
        Map<String, Object> raw = executeRaw(puppetNode, connection, sql);
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("database", database);
        data.put("table", table);
        data.put("sql", sql);
        data.put("affectedRows", toInteger(raw.get("updateCount")));
        return data;
    }

    public Map<String, Object> updateRows(JavaPuppetNode puppetNode,
                                          Map<String, Object> connection,
                                          String database,
                                          String table,
                                          Map<String, Object> where,
                                          Map<String, Object> update) throws Exception {
        AbstractSqlDialect dialect = dialect(connection);
        String sql = dialect.buildUpdateSql(database, table, where, update);
        Map<String, Object> raw = executeRaw(puppetNode, connection, sql);
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("database", database);
        data.put("table", table);
        data.put("sql", sql);
        data.put("affectedRows", toInteger(raw.get("updateCount")));
        return data;
    }

    public Map<String, Object> deleteRows(JavaPuppetNode puppetNode,
                                          Map<String, Object> connection,
                                          String database,
                                          String table,
                                          Map<String, Object> where) throws Exception {
        AbstractSqlDialect dialect = dialect(connection);
        String sql = dialect.buildDeleteSql(database, table, where);
        Map<String, Object> raw = executeRaw(puppetNode, connection, sql);
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("database", database);
        data.put("table", table);
        data.put("sql", sql);
        data.put("affectedRows", toInteger(raw.get("updateCount")));
        return data;
    }

    private Long queryTableCount(JavaPuppetNode puppetNode,
                                 Map<String, Object> connection,
                                 AbstractSqlDialect dialect,
                                 String database,
                                 String table) {
        try {
            Map<String, Object> raw = executeRaw(puppetNode, connection,
                    dialect.buildCountSql(database, table, Collections.<Map<String, Object>>emptyList()));
            return extractCount(raw);
        } catch (Exception ignored) {
            return null;
        }
    }

    private Map<String, Object> executeRaw(JavaPuppetNode puppetNode, Map<String, Object> connection, String sql) throws Exception {
        Map<String, Object> result = puppetNode.execSql(
                stringValue(connection.get("driver")),
                stringValue(connection.get("url")),
                stringValue(connection.get("user")),
                stringValue(connection.get("password")),
                sql
        );
        if (result == null) {
            throw new IllegalStateException("puppet 执行结果为空");
        }
        Object code = result.get("code");
        if (code != null && !"200".equals(String.valueOf(code))) {
            throw new IllegalStateException(safeString(result.get("msg")));
        }
        return result;
    }

    private Map<String, Object> normalizeQueryResult(Map<String, Object> raw, String statementType) {
        List<Map<String, Object>> rowList = rows(raw);
        List<Map<String, Object>> columns = columns(raw);
        if (columns.isEmpty() && !rowList.isEmpty()) {
            for (String key : rowList.get(0).keySet()) {
                Map<String, Object> column = new LinkedHashMap<String, Object>();
                column.put("name", key);
                column.put("label", key);
                column.put("type", inferType(rowList, key));
                columns.add(column);
            }
        }
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("columns", columns);
        data.put("rows", rowList);
        data.put("affectedRows", toInteger(raw.get("updateCount")));
        data.put("statementType", statementType);
        return data;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> columns(Map<String, Object> raw) {
        Object columns = raw.get("columns");
        if (!(columns instanceof List<?>)) {
            return new ArrayList<Map<String, Object>>();
        }
        List<?> list = (List<?>) columns;
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        for (Object item : list) {
            if (item instanceof Map<?, ?>) {
                result.add((Map<String, Object>) item);
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> rows(Map<String, Object> raw) {
        Object rows = raw.get("rows");
        if (!(rows instanceof List<?>)) {
            return Collections.emptyList();
        }
        List<?> list = (List<?>) rows;
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        for (Object item : list) {
            if (item instanceof Map<?, ?>) {
                result.add((Map<String, Object>) item);
            }
        }
        return result;
    }

    private Long extractCount(Map<String, Object> raw) {
        List<Map<String, Object>> rowList = rows(raw);
        if (rowList.isEmpty()) {
            return 0L;
        }
        Object value = rowList.get(0).values().stream().findFirst().orElse(0);
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (Exception ignored) {
            return 0L;
        }
    }

    private String inferType(List<Map<String, Object>> rows, String key) {
        for (Map<String, Object> row : rows) {
            Object value = row.get(key);
            if (value != null) {
                return value.getClass().getSimpleName().toUpperCase(Locale.ROOT);
            }
        }
        return "UNKNOWN";
    }

    private AbstractSqlDialect dialect(Map<String, Object> connection) {
        return sqlDialectFactory.require(stringValue(connection.get("type")));
    }

    private Object firstValue(Map<String, Object> row, String... keys) {
        for (String key : keys) {
            for (Map.Entry<String, Object> entry : row.entrySet()) {
                if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(key)) {
                    return entry.getValue();
                }
            }
        }
        return null;
    }

    private String firstNonEmpty(Map<String, Object> row, String... keys) {
        Object value = firstValue(row, keys);
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value);
        return text.isBlank() ? null : text;
    }

    private String safeString(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private Integer toInteger(Object value) {
        Integer result = toNullableInteger(value);
        return result == null ? 0 : result;
    }

    private Integer toNullableInteger(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (Exception ignored) {
            return null;
        }
    }

    private Boolean toBoolean(Object value) {
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        if (value == null) {
            return false;
        }
        String text = String.valueOf(value).trim();
        if ("YES".equalsIgnoreCase(text) || "Y".equalsIgnoreCase(text) || "true".equalsIgnoreCase(text) || "1".equals(text)) {
            return true;
        }
        if ("PRI".equalsIgnoreCase(text) || "PK".equalsIgnoreCase(text)) {
            return true;
        }
        return false;
    }

    private String detectStatementType(String sql) {
        if (sql == null || sql.isBlank()) {
            return "UNKNOWN";
        }
        String trimmed = sql.trim();
        int index = trimmed.indexOf(' ');
        return (index > 0 ? trimmed.substring(0, index) : trimmed).toUpperCase(Locale.ROOT);
    }
}
