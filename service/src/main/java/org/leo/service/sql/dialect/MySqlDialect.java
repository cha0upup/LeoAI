package org.leo.service.sql.dialect;


import java.util.Arrays;
import java.util.List;
import java.util.Map;

public class MySqlDialect extends AbstractSqlDialect {

    public String getType() { return "mysql"; }
    public String getName() { return "MySQL"; }
    public Integer getDefaultPort() { return 3306; }
    public String getDriverClass() { return "com.mysql.cj.jdbc.Driver"; }
    public String getUrlTemplate() { return "jdbc:mysql://{host}:{port}/{database}?{params}"; }
    public List<Map<String, Object>> getVariants() { return Arrays.<Map<String, Object>>asList(variant("mysql8", "MySQL 8.x", getDriverClass(), getUrlTemplate())); }
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
    public String buildTablesSql(String database) {
        if (isBlank(database)) {
            return "SELECT TABLE_NAME AS name, TABLE_SCHEMA AS schema_name, TABLE_COMMENT AS comment FROM information_schema.TABLES " +
                    "WHERE TABLE_SCHEMA = DATABASE() ORDER BY TABLE_NAME";
        }
        return "SELECT TABLE_NAME AS name, TABLE_SCHEMA AS schema_name, TABLE_COMMENT AS comment FROM information_schema.TABLES " +
                "WHERE TABLE_SCHEMA = " + formatLiteral(database) + " ORDER BY TABLE_NAME";
    }
    public String buildTableColumnsSql(String database, String table) {
        String schemaExpr = isBlank(database) ? "DATABASE()" : formatLiteral(database);
        return "SELECT c.COLUMN_NAME AS name, c.COLUMN_TYPE AS type, c.IS_NULLABLE AS nullable, c.COLUMN_DEFAULT AS default_value, " +
                "c.COLUMN_COMMENT AS comment, c.CHARACTER_MAXIMUM_LENGTH AS length, c.NUMERIC_PRECISION AS numeric_precision, c.NUMERIC_SCALE AS numeric_scale, " +
                "CASE WHEN c.COLUMN_KEY = 'PRI' THEN 1 ELSE 0 END AS primary_key FROM information_schema.COLUMNS c " +
                "WHERE c.TABLE_SCHEMA = " + schemaExpr + " AND c.TABLE_NAME = " + formatLiteral(table) + " ORDER BY c.ORDINAL_POSITION";
    }
    protected String buildQualifiedTable(String database, String table) {
        if (isBlank(database)) {
            return escapeIdentifier(table);
        }
        return escapeIdentifier(database) + "." + escapeIdentifier(table);
    }
    protected String buildPaginationSql(String baseSql, int offset, int pageSize) { return baseSql + " LIMIT " + pageSize + " OFFSET " + offset; }
    protected String escapeIdentifier(String identifier) { return "`" + identifier.replace("`", "``") + "`"; }
}
