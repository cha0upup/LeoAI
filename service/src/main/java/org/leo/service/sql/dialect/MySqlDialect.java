package org.leo.service.sql.dialect;

import org.leo.service.sql.SqlObjectRef;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

public class MySqlDialect extends AbstractSqlDialect {

    public String getType() { return "mysql"; }
    public String getName() { return "MySQL"; }
    public Integer getDefaultPort() { return 3306; }
    public List<Map<String, Object>> getVariants() { return Arrays.<Map<String, Object>>asList(
            variant("default", "MySQL", "host", "port", "database", "username", "password", "options")); }
    public List<Map<String, Object>> getDataTypes() {
        return Arrays.<Map<String, Object>>asList(
                dataType("INT", null, null, null), dataType("BIGINT", null, null, null),
                dataType("VARCHAR", 255, null, null), dataType("TEXT", null, null, null),
                dataType("DECIMAL", null, 10, 2), dataType("DATETIME", null, null, null),
                dataType("TIMESTAMP", null, null, null), dataType("JSON", null, null, null)
        );
    }
    public String buildTestSql() { return "SELECT VERSION() AS version"; }
    public String buildDatabasesSql() { return "SHOW DATABASES"; }
    public String buildTablesSql(SqlObjectRef namespace) {
        String database = namespace == null ? null : namespace.namespace();
        if (isBlank(database)) {
            return "SELECT TABLE_NAME AS name, TABLE_SCHEMA AS schema_name, TABLE_COMMENT AS remarks FROM information_schema.TABLES " +
                    "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_TYPE = 'BASE TABLE' ORDER BY TABLE_NAME";
        }
        return "SELECT TABLE_NAME AS name, TABLE_SCHEMA AS schema_name, TABLE_COMMENT AS remarks FROM information_schema.TABLES " +
                "WHERE TABLE_SCHEMA = " + formatLiteral(database) + " AND TABLE_TYPE = 'BASE TABLE' ORDER BY TABLE_NAME";
    }
    public String buildTableColumnsSql(SqlObjectRef tableRef) {
        String database = tableRef == null ? null : tableRef.namespace();
        String table = tableRef == null ? null : tableRef.name();
        String schemaExpr = isBlank(database) ? "DATABASE()" : formatLiteral(database);
        return "SELECT c.COLUMN_NAME AS name, c.COLUMN_TYPE AS type, c.IS_NULLABLE AS nullable, c.COLUMN_DEFAULT AS default_value, " +
                "c.COLUMN_COMMENT AS remarks, c.CHARACTER_MAXIMUM_LENGTH AS length, c.NUMERIC_PRECISION AS numeric_precision, c.NUMERIC_SCALE AS numeric_scale, " +
                "CASE WHEN c.COLUMN_KEY = 'PRI' THEN 1 ELSE 0 END AS primary_key FROM information_schema.COLUMNS c " +
                "WHERE c.TABLE_SCHEMA = " + schemaExpr + " AND c.TABLE_NAME = " + formatLiteral(table) + " ORDER BY c.ORDINAL_POSITION";
    }
    protected String escapeIdentifier(String identifier) { return "`" + identifier.replace("`", "``") + "`"; }
}
