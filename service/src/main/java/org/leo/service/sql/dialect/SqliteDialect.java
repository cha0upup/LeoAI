package org.leo.service.sql.dialect;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

public class SqliteDialect extends AbstractSqlDialect {

    public String getType() { return "sqlite"; }
    public String getName() { return "SQLite"; }
    public Integer getDefaultPort() { return null; }
    public String getDriverClass() { return "org.sqlite.JDBC"; }
    public String getUrlTemplate() { return "jdbc:sqlite:{path}"; }
    public List<Map<String, Object>> getVariants() { return Arrays.<Map<String, Object>>asList(variant("sqlite", "SQLite", getDriverClass(), getUrlTemplate())); }
    public List<Map<String, Object>> getDataTypes() {
        return Arrays.<Map<String, Object>>asList(
                dataType("INTEGER", null, null, null), dataType("TEXT", null, null, null),
                dataType("REAL", null, null, null), dataType("BLOB", null, null, null), dataType("NUMERIC", null, null, null)
        );
    }
    public String buildTestSql() { return "SELECT sqlite_version() AS version"; }
    public String buildDatabasesSql() { return "SELECT 'main' AS name"; }
    public String buildCreateDatabaseSql(String database) {
        throw new IllegalArgumentException("sqlite 暂不支持通过该接口创建数据库");
    }
    public String buildTablesSql(String database) { return "SELECT name, '' AS schema_name, '' AS comment FROM sqlite_master WHERE type = 'table' ORDER BY name"; }
    public String buildTableColumnsSql(String database, String table) {
        return "SELECT name, type, CASE WHEN \"notnull\" = 1 THEN 'NO' ELSE 'YES' END AS nullable, dflt_value AS default_value, '' AS comment, " +
                "NULL AS length, NULL AS numeric_precision, NULL AS numeric_scale, pk AS primary_key FROM pragma_table_info(" + formatLiteral(table) + ")";
    }
    protected String buildQualifiedTable(String database, String table) { return escapeIdentifier(table); }
    protected String buildPaginationSql(String baseSql, int offset, int pageSize) { return baseSql + " LIMIT " + pageSize + " OFFSET " + offset; }
    protected String escapeIdentifier(String identifier) { return "[" + identifier.replace("]", "]]") + "]"; }
}
