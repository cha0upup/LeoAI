package org.leo.service.sql.dialect;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

public class OracleDialect extends AbstractSqlDialect {

    public String getType() { return "oracle"; }
    public String getName() { return "Oracle"; }
    public Integer getDefaultPort() { return 1521; }
    public String getDriverClass() { return "oracle.jdbc.driver.OracleDriver"; }
    public String getUrlTemplate() { return "jdbc:oracle:thin:@//{host}:{port}/{service}?{params}"; }
    public List<Map<String, Object>> getVariants() {
        return Arrays.<Map<String, Object>>asList(
                variant("oracle_service", "Thin (Service Name)", getDriverClass(), "jdbc:oracle:thin:@//{host}:{port}/{service}?{params}"),
                variant("oracle_sid", "Thin (SID)", getDriverClass(), "jdbc:oracle:thin:@{host}:{port}:{sid}?{params}")
        );
    }
    public List<Map<String, Object>> getDataTypes() {
        return Arrays.<Map<String, Object>>asList(
                dataType("NUMBER", null, 10, 2), dataType("VARCHAR2", 255, null, null),
                dataType("CHAR", 10, null, null), dataType("DATE", null, null, null),
                dataType("TIMESTAMP", null, null, null), dataType("CLOB", null, null, null), dataType("BLOB", null, null, null)
        );
    }
    public String buildTestSql() { return "SELECT banner AS version FROM v$version WHERE ROWNUM = 1"; }
    public String buildDatabasesSql() { return "SELECT username AS name FROM all_users ORDER BY username"; }
    public String buildCreateDatabaseSql(String database) {
        throw new IllegalArgumentException("oracle 暂不支持通过该接口创建数据库");
    }
    public String buildTablesSql(String database) {
        String ownerExpr = isBlank(database) ? "USER" : formatLiteral(database.toUpperCase());
        return "SELECT table_name AS name, owner AS schema_name, '' AS comment FROM all_tables WHERE owner = " + ownerExpr + " ORDER BY table_name";
    }
    public String buildTableColumnsSql(String database, String table) {
        String ownerExpr = isBlank(database) ? "USER" : formatLiteral(database.toUpperCase());
        return "SELECT c.column_name AS name, c.data_type AS type, CASE WHEN c.nullable = 'Y' THEN 'YES' ELSE 'NO' END AS nullable, " +
                "c.data_default AS default_value, '' AS comment, c.char_length AS length, c.data_precision AS numeric_precision, c.data_scale AS numeric_scale, " +
                "CASE WHEN pk.column_name IS NULL THEN 0 ELSE 1 END AS primary_key FROM all_tab_columns c LEFT JOIN (" +
                "SELECT acc.owner, acc.table_name, acc.column_name FROM all_constraints ac JOIN all_cons_columns acc " +
                "ON ac.owner = acc.owner AND ac.constraint_name = acc.constraint_name WHERE ac.constraint_type = 'P') pk " +
                "ON pk.owner = c.owner AND pk.table_name = c.table_name AND pk.column_name = c.column_name " +
                "WHERE c.owner = " + ownerExpr + " AND c.table_name = " + formatLiteral(table.toUpperCase()) + " ORDER BY c.column_id";
    }
    protected String buildQualifiedTable(String database, String table) {
        if (isBlank(database)) {
            return escapeIdentifier(table.toUpperCase());
        }
        return escapeIdentifier(database.toUpperCase()) + "." + escapeIdentifier(table.toUpperCase());
    }
    protected String buildPaginationSql(String baseSql, int offset, int pageSize) {
        return "SELECT * FROM (SELECT inner_query.*, ROWNUM rn FROM (" + baseSql + ") inner_query WHERE ROWNUM <= " + (offset + pageSize) + ") WHERE rn > " + offset;
    }
    protected String escapeIdentifier(String identifier) { return "\"" + identifier.replace("\"", "\"\"") + "\""; }
}
