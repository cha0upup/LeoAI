package org.leo.service.sql.dialect;

import org.leo.service.sql.SqlObjectRef;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

public class SqliteDialect extends AbstractSqlDialect {

    public String getType() { return "sqlite"; }
    public String getName() { return "SQLite"; }
    public Integer getDefaultPort() { return null; }
    public List<String> getNamespaceLevels() { return List.of("catalog"); }
    public List<Map<String, Object>> getVariants() { return Arrays.<Map<String, Object>>asList(
            variant("file", "SQLite 文件", "file")); }
    public List<Map<String, Object>> getDataTypes() {
        return Arrays.<Map<String, Object>>asList(
                dataType("INTEGER", null, null, null), dataType("TEXT", null, null, null),
                dataType("REAL", null, null, null), dataType("BLOB", null, null, null), dataType("NUMERIC", null, null, null)
        );
    }
    public SqlDialectCapabilities getCapabilities() { return SqlDialectCapabilities.managed(false); }
    public String buildTestSql() { return "SELECT sqlite_version() AS version"; }
    public String buildDatabasesSql() { return "SELECT name FROM pragma_database_list WHERE name <> 'temp' ORDER BY seq"; }
    public String buildCreateDatabaseSql(String database) {
        throw new IllegalArgumentException("sqlite 暂不支持通过该接口创建数据库");
    }
    public String buildTablesSql(SqlObjectRef namespace) {
        String database = namespace == null || isBlank(namespace.catalog()) ? "main" : namespace.catalog();
        return "SELECT name, '' AS schema_name, '' AS remarks FROM " + escapeIdentifier(database) +
                ".sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' ORDER BY name";
    }
    public String buildTableColumnsSql(SqlObjectRef tableRef) {
        String database = tableRef == null || isBlank(tableRef.catalog()) ? "main" : tableRef.catalog();
        String table = tableRef == null ? null : tableRef.name();
        return "SELECT name, type, CASE WHEN \"notnull\" = 1 OR pk > 0 THEN 'NO' ELSE 'YES' END AS nullable, dflt_value AS default_value, '' AS remarks, " +
                "NULL AS length, NULL AS numeric_precision, NULL AS numeric_scale, pk AS primary_key FROM pragma_table_info(" +
                formatLiteral(table) + ", " + formatLiteral(database) + ")";
    }
    protected String buildQualifiedTable(SqlObjectRef tableRef) {
        String database = tableRef == null || isBlank(tableRef.catalog()) ? "main" : tableRef.catalog();
        return escapeIdentifier(database) + "." + escapeIdentifier(tableRef.name());
    }
    protected String escapeIdentifier(String identifier) { return "[" + identifier.replace("]", "]]") + "]"; }
}
