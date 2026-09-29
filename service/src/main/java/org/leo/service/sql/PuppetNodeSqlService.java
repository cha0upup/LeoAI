package org.leo.service.sql;

import org.leo.core.puppet.capability.SqlCapable;
import org.leo.core.puppet.database.DatabaseConnectionSpec;
import org.leo.core.puppet.database.SqlCommand;
import org.leo.service.sql.dialect.AbstractSqlDialect;
import org.leo.service.sql.dialect.SqlDialectRegistry;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class PuppetNodeSqlService {

    private final SqlDialectRegistry sqlDialectRegistry;

    public PuppetNodeSqlService(SqlDialectRegistry sqlDialectRegistry) {
        this.sqlDialectRegistry = sqlDialectRegistry;
    }

    public Map<String, Object> testConnection(SqlCapable puppetNode, Map<String, Object> connection) throws Exception {
        long startedAt = System.nanoTime();
        List<Map<String, Object>> diagnostics = new ArrayList<Map<String, Object>>();
        Map<String, Object> capabilities = getRuntimeCapabilities(puppetNode, connection);
        if (!isSuccessCode(capabilities.get("code"))) {
            String message = safeString(capabilities.get("msg"));
            diagnostics.add(diagnostic("driver", "failed", message));
            return failedConnectionTest(startedAt, diagnostics, capabilities,
                    "driver", "CAPABILITY_INSPECTION_FAILED", message);
        }
        boolean providerAvailable = !Boolean.FALSE.equals(capabilities.get("available"));
        boolean driverAvailable = requestedDriverAvailable(capabilities);
        if (!providerAvailable || !driverAvailable) {
            String message = capabilityFailureMessage(capabilities, providerAvailable);
            diagnostics.add(diagnostic("driver", "failed", message));
            return failedConnectionTest(startedAt, diagnostics, capabilities,
                    "driver", providerAvailable ? "DRIVER_NOT_FOUND" : "PROVIDER_NOT_FOUND",
                    message);
        }
        diagnostics.add(diagnostic("driver", "passed", capabilitySuccessMessage(capabilities)));

        try {
            AbstractSqlDialect dialect = dialect(connection);
            DatabaseConnectionSpec connectionSpec = connectionSpec(connection);
            String configuredTestSql = stringValue(connectionSpec.getDialectOptions().get("testSql"));
            String testSql = configuredTestSql == null || configuredTestSql.isBlank()
                    ? dialect.buildTestSql()
                    : configuredTestSql;
            Map<String, Object> raw = puppetNode.executeSql(connectionSpec, testSql);
            if (raw == null) {
                throw new IllegalStateException("puppet 执行结果为空");
            }
            if (!isSuccessCode(raw.get("code"))) {
                String category = safeString(raw.get("errorCategory"));
                String failureStage = failureStage(category);
                String message = safeString(raw.get("msg"));
                diagnostics.add(diagnostic(failureStage, "failed", message));
                return failedConnectionTest(startedAt, diagnostics, capabilities,
                        failureStage, category, message);
            }

            diagnostics.add(diagnostic("connection", "passed", "数据库连接已建立"));
            diagnostics.add(diagnostic("healthCheck", "passed", "连通性 SQL 执行成功"));
            List<Map<String, Object>> rowList = rows(raw);
            String version = "";
            if (!rowList.isEmpty()) {
                Object firstValue = rowList.get(0).values().stream().findFirst().orElse("");
                version = firstValue == null ? "" : String.valueOf(firstValue);
            }

            Map<String, Object> data = new LinkedHashMap<String, Object>();
            data.put("success", true);
            data.put("databaseVersion", version);
            data.put("runtimeMetadata", raw.get("runtimeMetadata"));
            data.put("runtimeCapabilities", capabilities);
            data.put("diagnostics", diagnostics);
            data.put("latencyMs", (System.nanoTime() - startedAt) / 1_000_000L);
            data.put("statementType", detectStatementType(testSql));
            return data;
        } catch (Exception error) {
            String category = error instanceof IllegalArgumentException
                    ? "INVALID_ARGUMENT" : "EXECUTION_ERROR";
            String stage = failureStage(category);
            String message = safeString(error.getMessage());
            diagnostics.add(diagnostic(stage, "failed", message));
            return failedConnectionTest(startedAt, diagnostics, capabilities,
                    stage, category, message);
        }
    }

    public Map<String, Object> getRuntimeCapabilities(SqlCapable puppetNode,
                                                       Map<String, Object> connection) {
        try {
            Map<String, Object> result = puppetNode.inspectDatabaseRuntime(canonicalConnection(connection));
            if (result != null) return new LinkedHashMap<String, Object>(result);
            return Map.of("code", 500, "available", false, "msg", "Puppet 运行时能力响应为空");
        } catch (Exception error) {
            return Map.of("code", 500, "available", false,
                    "msg", safeString(error.getMessage()));
        }
    }

    public List<Map<String, Object>> getDialects() {
        return sqlDialectRegistry.getDialectInfos();
    }

    public Map<String, Object> getDatabases(SqlCapable puppetNode, Map<String, Object> connection) throws Exception {
        AbstractSqlDialect dialect = dialect(connection);
        requireCapability(dialect, "listDatabases", dialect.getCapabilities().listDatabases());
        Map<String, Object> raw = executeRaw(puppetNode, connection, dialect.buildDatabasesSql());
        List<Map<String, Object>> databases = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> row : rows(raw)) {
            String name = firstNonEmpty(row, "name", "database", "DATABASE", "TABLE_CAT", "TABLE_SCHEM");
            if (name == null || name.isBlank()) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("name", name);
            SqlObjectRef namespaceRef = dialect.namespaceRef(name);
            item.put("kind", namespaceRef.kind());
            item.put("ref", namespaceRef.toMap());
            databases.add(item);
        }
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("databases", databases);
        return data;
    }

    public Map<String, Object> getTables(SqlCapable puppetNode,
                                         Map<String, Object> connection,
                                         SqlObjectRef namespaceRef) throws Exception {
        AbstractSqlDialect dialect = dialect(connection);
        requireCapability(dialect, "listTables", dialect.getCapabilities().listTables());
        requireNamespaceRef(namespaceRef);
        Map<String, Object> raw = executeRaw(puppetNode, connection, dialect.buildTablesSql(namespaceRef));
        List<Map<String, Object>> tables = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> row : rows(raw)) {
            String tableName = firstNonEmpty(row, "name", "table_name", "TABLE_NAME");
            if (tableName == null || tableName.isBlank()) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("name", tableName);
            String schema = firstNonEmpty(row, "schema", "schema_name", "table_schema", "TABLE_SCHEM");
            item.put("schema", schema);
            item.put("comment", safeString(firstValue(row, "comment", "remarks", "table_comment", "TABLE_COMMENT")));
            item.put("kind", "table");
            item.put("ref", dialect.tableRef(namespaceRef.namespace(), schema, tableName).toMap());
            tables.add(item);
        }
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("tables", tables);
        return data;
    }

    public Map<String, Object> getTableColumns(SqlCapable puppetNode,
                                               Map<String, Object> connection,
                                               SqlObjectRef tableRef) throws Exception {
        AbstractSqlDialect dialect = dialect(connection);
        requireCapability(dialect, "listColumns", dialect.getCapabilities().listColumns());
        requireTableRef(tableRef);
        Map<String, Object> raw = executeRaw(puppetNode, connection, dialect.buildTableColumnsSql(tableRef));
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

    public Map<String, Object> queryTable(SqlCapable puppetNode,
                                          Map<String, Object> connection,
                                          SqlObjectRef tableRef,
                                          Integer page,
                                          Integer pageSize,
                                          List<String> columns,
                                          List<Map<String, Object>> orderBy,
                                          List<Map<String, Object>> filters,
                                          Boolean includeTotal,
                                          Integer queryTimeoutSeconds) throws Exception {
        AbstractSqlDialect dialect = dialect(connection);
        requireCapability(dialect, "structuredQuery", dialect.getCapabilities().structuredQuery());
        requireTableRef(tableRef);
        int actualPage = page == null || page < 1 ? 1 : page;
        int actualPageSize = pageSize == null || pageSize < 1 ? 20 : Math.min(pageSize, 1000);
        Map<String, Object> executionConnection = withQueryTimeout(connection, queryTimeoutSeconds);
        Map<String, Object> rawQuery = executeRaw(puppetNode, executionConnection,
                dialect.buildQueryTableCommand(tableRef, actualPage, actualPageSize, columns, orderBy, filters));

        Map<String, Object> data = normalizeQueryResult(rawQuery, "SELECT");
        Map<String, Object> pagination = new LinkedHashMap<String, Object>();
        pagination.put("page", actualPage);
        pagination.put("pageSize", actualPageSize);
        if (!Boolean.FALSE.equals(includeTotal)) {
            Map<String, Object> rawCount = executeRaw(
                    puppetNode, executionConnection, dialect.buildCountCommand(tableRef, filters));
            pagination.put("total", extractCount(rawCount));
        }
        data.put("pagination", pagination);
        return data;
    }

    public Map<String, Object> executeSql(SqlCapable puppetNode, Map<String, Object> connection, String sql) throws Exception {
        return executeSql(puppetNode, connection, sql, null);
    }

    public Map<String, Object> executeSql(SqlCapable puppetNode,
                                          Map<String, Object> connection,
                                          String sql,
                                          Integer queryTimeoutSeconds) throws Exception {
        Map<String, Object> raw = executeRaw(
                puppetNode, withQueryTimeout(connection, queryTimeoutSeconds), sql);
        return normalizeQueryResult(raw, detectStatementType(sql));
    }

    public Map<String, Object> createTable(SqlCapable puppetNode,
                                           Map<String, Object> connection,
                                           SqlObjectRef tableRef,
                                           List<Map<String, Object>> columns) throws Exception {
        AbstractSqlDialect dialect = dialect(connection);
        requireCapability(dialect, "createTable", dialect.getCapabilities().createTable());
        requireTableRef(tableRef);
        String sql = dialect.buildCreateTableSql(tableRef, columns);
        Map<String, Object> raw = executeRaw(puppetNode, connection, sql);
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("objectRef", tableRef.toMap());
        data.put("sql", sql);
        data.put("affectedRows", toInteger(raw.get("affectedRows")));
        return data;
    }

    public Map<String, Object> createDatabase(SqlCapable puppetNode, Map<String, Object> connection, String database) throws Exception {
        AbstractSqlDialect dialect = dialect(connection);
        requireCapability(dialect, "createDatabase", dialect.getCapabilities().createDatabase());
        String sql = dialect.buildCreateDatabaseSql(database);
        Map<String, Object> raw = executeRaw(puppetNode, connection, sql);
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("database", database);
        data.put("sql", sql);
        data.put("affectedRows", toInteger(raw.get("affectedRows")));
        return data;
    }

    public Map<String, Object> insertRow(SqlCapable puppetNode,
                                         Map<String, Object> connection,
                                         SqlObjectRef tableRef,
                                         Map<String, Object> row) throws Exception {
        AbstractSqlDialect dialect = dialect(connection);
        requireCapability(dialect, "insert", dialect.getCapabilities().insert());
        requireTableRef(tableRef);
        SqlCommand command = dialect.buildInsertCommand(tableRef, row);
        Map<String, Object> raw = executeRaw(puppetNode, connection, command);
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("objectRef", tableRef.toMap());
        data.put("sql", command.sql());
        data.put("affectedRows", toInteger(raw.get("affectedRows")));
        return data;
    }

    public Map<String, Object> updateRows(SqlCapable puppetNode,
                                          Map<String, Object> connection,
                                          SqlObjectRef tableRef,
                                          Map<String, Object> where,
                                          Map<String, Object> update) throws Exception {
        AbstractSqlDialect dialect = dialect(connection);
        requireCapability(dialect, "update", dialect.getCapabilities().update());
        requireTableRef(tableRef);
        SqlCommand command = dialect.buildUpdateCommand(tableRef, where, update);
        Map<String, Object> raw = executeRaw(puppetNode, connection, command);
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("objectRef", tableRef.toMap());
        data.put("sql", command.sql());
        data.put("affectedRows", toInteger(raw.get("affectedRows")));
        return data;
    }

    public Map<String, Object> deleteRows(SqlCapable puppetNode,
                                          Map<String, Object> connection,
                                          SqlObjectRef tableRef,
                                          Map<String, Object> where) throws Exception {
        AbstractSqlDialect dialect = dialect(connection);
        requireCapability(dialect, "delete", dialect.getCapabilities().delete());
        requireTableRef(tableRef);
        SqlCommand command = dialect.buildDeleteCommand(tableRef, where);
        Map<String, Object> raw = executeRaw(puppetNode, connection, command);
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("objectRef", tableRef.toMap());
        data.put("sql", command.sql());
        data.put("affectedRows", toInteger(raw.get("affectedRows")));
        return data;
    }

    private void requireNamespaceRef(SqlObjectRef namespaceRef) {
        if (namespaceRef == null || namespaceRef.namespace() == null) {
            throw new IllegalArgumentException("objectRef 必须包含 catalog 或 schema");
        }
    }

    private void requireTableRef(SqlObjectRef tableRef) {
        if (tableRef == null || tableRef.name() == null) {
            throw new IllegalArgumentException("objectRef 必须包含表名");
        }
    }

    private Map<String, Object> executeRaw(SqlCapable puppetNode, Map<String, Object> connection, String sql) throws Exception {
        return executeRaw(puppetNode, connection, SqlCommand.raw(sql));
    }

    private Map<String, Object> executeRaw(SqlCapable puppetNode,
                                           Map<String, Object> connection,
                                           SqlCommand command) throws Exception {
        Map<String, Object> result = puppetNode.executeSql(connectionSpec(connection), command);
        if (result == null) {
            throw new IllegalStateException("puppet 执行结果为空");
        }
        Object code = result.get("code");
        if (!(code instanceof Number number) || number.intValue() != 200) {
            throw SqlExecutionException.fromResult(result);
        }
        return result;
    }

    private Map<String, Object> failedConnectionTest(long startedAt,
                                                     List<Map<String, Object>> diagnostics,
                                                     Map<String, Object> capabilities,
                                                     String failureStage,
                                                     String category,
                                                     String message) {
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("success", false);
        data.put("failureStage", failureStage);
        data.put("errorCategory", category == null || category.isBlank() ? "UNKNOWN" : category);
        data.put("message", message == null || message.isBlank() ? "连接测试失败" : message);
        data.put("runtimeCapabilities", capabilities);
        data.put("diagnostics", diagnostics);
        data.put("latencyMs", (System.nanoTime() - startedAt) / 1_000_000L);
        return data;
    }

    private Map<String, Object> diagnostic(String stage, String status, String message) {
        Map<String, Object> item = new LinkedHashMap<String, Object>();
        item.put("stage", stage);
        item.put("status", status);
        item.put("message", message);
        return item;
    }

    private boolean isSuccessCode(Object code) {
        return code instanceof Number number && number.intValue() >= 200 && number.intValue() < 300;
    }

    private boolean requestedDriverAvailable(Map<String, Object> capabilities) {
        Object value = capabilities.get("requestedDriver");
        if (!(value instanceof Map<?, ?> requested)) return true;
        return !Boolean.FALSE.equals(requested.get("available"));
    }

    private String capabilityFailureMessage(Map<String, Object> capabilities,
                                            boolean providerAvailable) {
        if (!providerAvailable) {
            String message = safeString(capabilities.get("msg"));
            return message.isBlank() ? "数据库运行时 Provider 不可用" : message;
        }
        Object value = capabilities.get("requestedDriver");
        if (value instanceof Map<?, ?> requested) {
            String message = safeString(requested.get("message"));
            return message.isBlank() ? "数据库驱动不可用" : message;
        }
        return "数据库驱动不可用";
    }

    private String capabilitySuccessMessage(Map<String, Object> capabilities) {
        Object value = capabilities.get("requestedDriver");
        if (value instanceof Map<?, ?> requested) {
            String message = safeString(requested.get("message"));
            if (!message.isBlank()) return message;
        }
        return "数据库运行时 Provider 可用";
    }

    private String failureStage(String category) {
        String normalized = category == null ? "" : category.trim().toUpperCase(Locale.ROOT);
        if (normalized.contains("DRIVER") || normalized.contains("PROVIDER")) return "driver";
        if (normalized.contains("URL") || normalized.contains("ARGUMENT")
                || normalized.contains("CONFIG")) return "configuration";
        if (normalized.contains("AUTH")) return "authentication";
        if (normalized.contains("CONNECTION") || normalized.contains("TIMEOUT")) return "network";
        return "healthCheck";
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
        data.put("rowCount", raw.get("rowCount") == null ? rowList.size() : toInteger(raw.get("rowCount")));
        data.put("affectedRows", toInteger(raw.get("affectedRows")));
        data.put("generatedKey", raw.get("generatedKey"));
        data.put("truncated", toBoolean(raw.get("truncated")));
        data.put("truncationReason", raw.get("truncationReason"));
        data.put("resultBytes", toInteger(raw.get("resultBytes")));
        data.put("runtimeMetadata", raw.get("runtimeMetadata"));
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
        return sqlDialectRegistry.require(stringValue(connection.get("dialect")));
    }

    private DatabaseConnectionSpec connectionSpec(Map<String, Object> connection) {
        return DatabaseConnectionSpec.fromMap(canonicalConnection(connection));
    }

    private Map<String, Object> canonicalConnection(Map<String, Object> connection) {
        if (connection == null || connection.isEmpty()) return Collections.emptyMap();
        Map<String, Object> canonical = new LinkedHashMap<String, Object>(connection);
        String dialect = stringValue(connection.get("dialect"));
        if (dialect != null && !dialect.isBlank()) {
            canonical.put("dialect", sqlDialectRegistry.canonicalType(dialect));
        }
        return canonical;
    }

    private Map<String, Object> withQueryTimeout(Map<String, Object> connection,
                                                 Integer queryTimeoutSeconds) {
        if (queryTimeoutSeconds == null) return connection;
        if (queryTimeoutSeconds < 1 || queryTimeoutSeconds > 300) {
            throw new IllegalArgumentException("queryTimeoutSeconds 必须在 1 到 300 之间");
        }
        Map<String, Object> result = new LinkedHashMap<String, Object>(connection);
        result.put("timeoutSeconds", queryTimeoutSeconds);

        Map<String, Object> runtimeOptions = new LinkedHashMap<String, Object>();
        Object configuredRuntimes = connection.get("runtimeOptions");
        if (configuredRuntimes instanceof Map<?, ?> configured) {
            configured.forEach((key, value) -> runtimeOptions.put(String.valueOf(key), value));
        }
        Map<String, Object> javaOptions = new LinkedHashMap<String, Object>();
        Object configuredJava = runtimeOptions.get("java");
        if (configuredJava instanceof Map<?, ?> configured) {
            configured.forEach((key, value) -> javaOptions.put(String.valueOf(key), value));
        }
        javaOptions.put("queryTimeoutSeconds", queryTimeoutSeconds);
        runtimeOptions.put("java", javaOptions);
        result.put("runtimeOptions", runtimeOptions);
        return result;
    }

    private void requireCapability(AbstractSqlDialect dialect,
                                   String capability,
                                   boolean supported) {
        if (!supported) {
            throw new IllegalArgumentException(dialect.getName()
                    + " 方言不支持能力 " + capability + "，请直接执行厂商 SQL");
        }
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
