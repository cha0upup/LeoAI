package org.leo.service.sql.dialect;



import java.util.Arrays;
import java.util.List;
import java.util.Map;

public class SqlServerDialect extends AbstractSqlDialect {

    public String getType() { return "sqlserver"; }
    public String getName() { return "SQL Server"; }
    public Integer getDefaultPort() { return 1433; }
    public String getDriverClass() { return "com.microsoft.sqlserver.jdbc.SQLServerDriver"; }
    public String getUrlTemplate() { return "jdbc:sqlserver://{host}:{port};databaseName={database};{params}"; }
    public List<Map<String, Object>> getVariants() { return Arrays.<Map<String, Object>>asList(variant("mssql", "Microsoft Driver", getDriverClass(), getUrlTemplate())); }
    public List<Map<String, Object>> getDataTypes() {
        return Arrays.<Map<String, Object>>asList(
                dataType("INT", null, null, null), dataType("BIGINT", null, null, null),
                dataType("NVARCHAR", 255, null, null), dataType("TEXT", null, null, null),
                dataType("DECIMAL", null, 10, 2), dataType("DATETIME", null, null, null),
                dataType("BIT", null, null, null), dataType("UNIQUEIDENTIFIER", null, null, null)
        );
    }
    public String buildTestSql() { return "SELECT @@VERSION AS version"; }
    public String buildDatabasesSql() { return "SELECT name FROM sys.databases ORDER BY name"; }
    public String buildTablesSql(String database) {
        String prefix = isBlank(database) ? "" : escapeIdentifier(database) + ".";
        return "SELECT t.name AS name, s.name AS schema_name, CAST(ep.value AS NVARCHAR(4000)) AS comment " +
                "FROM " + prefix + "sys.tables t JOIN " + prefix + "sys.schemas s ON s.schema_id = t.schema_id " +
                "LEFT JOIN " + prefix + "sys.extended_properties ep ON ep.major_id = t.object_id AND ep.minor_id = 0 AND ep.name = 'MS_Description' ORDER BY t.name";
    }
    public String buildTableColumnsSql(String database, String table) {
        String prefix = isBlank(database) ? "" : escapeIdentifier(database) + ".";
        return "SELECT c.name AS name, t.name AS type, CASE WHEN c.is_nullable = 1 THEN 'YES' ELSE 'NO' END AS nullable, " +
                "OBJECT_DEFINITION(c.default_object_id) AS default_value, CAST(ep.value AS NVARCHAR(4000)) AS comment, c.max_length AS length, " +
                "c.precision AS numeric_precision, c.scale AS numeric_scale, CASE WHEN pk.column_id IS NULL THEN 0 ELSE 1 END AS primary_key " +
                "FROM " + prefix + "sys.columns c JOIN " + prefix + "sys.types t ON c.user_type_id = t.user_type_id " +
                "JOIN " + prefix + "sys.tables tb ON c.object_id = tb.object_id " +
                "LEFT JOIN " + prefix + "sys.extended_properties ep ON ep.major_id = c.object_id AND ep.minor_id = c.column_id AND ep.name = 'MS_Description' " +
                "LEFT JOIN (SELECT ic.object_id, ic.column_id FROM " + prefix + "sys.indexes i JOIN " + prefix + "sys.index_columns ic " +
                "ON i.object_id = ic.object_id AND i.index_id = ic.index_id WHERE i.is_primary_key = 1) pk ON pk.object_id = c.object_id AND pk.column_id = c.column_id " +
                "WHERE tb.name = " + formatLiteral(table) + " ORDER BY c.column_id";
    }
    protected String buildQualifiedTable(String database, String table) {
        if (isBlank(database)) {
            return "[dbo]." + escapeIdentifier(table);
        }
        return escapeIdentifier(database) + ".[dbo]." + escapeIdentifier(table);
    }
    protected String buildPaginationSql(String baseSql, int offset, int pageSize) {
        String ordered = baseSql.toLowerCase().contains(" order by ") ? baseSql : baseSql + " ORDER BY 1";
        return ordered + " OFFSET " + offset + " ROWS FETCH NEXT " + pageSize + " ROWS ONLY";
    }
    protected String escapeIdentifier(String identifier) { return "[" + identifier.replace("]", "]]") + "]"; }
}
