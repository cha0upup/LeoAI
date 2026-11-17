package org.leo.service.sql.dialect;

import org.leo.service.sql.SqlObjectRef;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

public class OracleDialect extends AbstractSqlDialect {

    public String getType() { return "oracle"; }
    public String getName() { return "Oracle"; }
    public Integer getDefaultPort() { return 1521; }
    public List<String> getNamespaceLevels() { return List.of("schema"); }
    public List<Map<String, Object>> getVariants() {
        return Arrays.<Map<String, Object>>asList(
                variant("service", "Service Name", "host", "port", "service", "username", "password", "options"),
                variant("sid", "SID", "host", "port", "sid", "username", "password", "options")
        );
    }
    public List<Map<String, Object>> getDataTypes() {
        return Arrays.<Map<String, Object>>asList(
                dataType("NUMBER", null, 10, 2), dataType("VARCHAR2", 255, null, null),
                dataType("CHAR", 10, null, null), dataType("DATE", null, null, null),
                dataType("TIMESTAMP", null, null, null), dataType("CLOB", null, null, null), dataType("BLOB", null, null, null)
        );
    }
    public SqlDialectCapabilities getCapabilities() { return SqlDialectCapabilities.managed(false); }
    public String buildTestSql() { return "SELECT banner AS version FROM v$version WHERE ROWNUM = 1"; }
    public String buildDatabasesSql() {
        return "SELECT USER AS name FROM dual UNION SELECT DISTINCT owner AS name FROM all_tables ORDER BY name";
    }
    public String buildCreateDatabaseSql(String database) {
        throw new IllegalArgumentException("oracle 暂不支持通过该接口创建数据库");
    }
    public String buildTablesSql(SqlObjectRef namespace) {
        String database = namespace == null ? null : namespace.namespace();
        String ownerExpr = isBlank(database) ? "USER" : formatLiteral(database);
        return "SELECT t.table_name AS name, t.owner AS schema_name, c.comments AS remarks " +
                "FROM all_tables t LEFT JOIN all_tab_comments c ON c.owner = t.owner AND c.table_name = t.table_name " +
                "WHERE t.owner = " + ownerExpr + " ORDER BY t.table_name";
    }
    public String buildTableColumnsSql(SqlObjectRef tableRef) {
        String database = tableRef == null ? null : tableRef.namespace();
        String table = tableRef == null ? null : tableRef.name();
        String ownerExpr = isBlank(database) ? "USER" : formatLiteral(database);
        return "SELECT c.column_name AS name, c.data_type AS type, CASE WHEN c.nullable = 'Y' THEN 'YES' ELSE 'NO' END AS nullable, " +
                "c.data_default AS default_value, (SELECT cc.comments FROM all_col_comments cc " +
                "WHERE cc.owner = c.owner AND cc.table_name = c.table_name AND cc.column_name = c.column_name) AS remarks, " +
                "c.char_length AS length, c.data_precision AS numeric_precision, c.data_scale AS numeric_scale, " +
                "CASE WHEN EXISTS (SELECT 1 FROM all_constraints ac JOIN all_cons_columns acc " +
                "ON ac.owner = acc.owner AND ac.constraint_name = acc.constraint_name " +
                "WHERE ac.constraint_type = 'P' AND acc.owner = c.owner AND acc.table_name = c.table_name " +
                "AND acc.column_name = c.column_name) THEN 1 ELSE 0 END AS primary_key FROM all_tab_columns c " +
                "WHERE c.owner = " + ownerExpr + " AND c.table_name = " + formatLiteral(table) + " ORDER BY c.column_id";
    }
    protected String buildPaginationSql(String baseSql, int offset, int pageSize) {
        return "SELECT * FROM (SELECT inner_query.*, ROWNUM rn FROM (" + baseSql + ") inner_query WHERE ROWNUM <= " + (offset + pageSize) + ") WHERE rn > " + offset;
    }
}
