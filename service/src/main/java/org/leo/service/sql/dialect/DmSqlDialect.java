package org.leo.service.sql.dialect;

import org.leo.service.sql.SqlObjectRef;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** DM-specific catalog SQL with shared Oracle-style qualification and pagination. */
public class DmSqlDialect extends OracleDialect {

    public String getType() { return "dm"; }
    public String getName() { return "达梦 DM"; }
    public Integer getDefaultPort() { return 5236; }
    public List<Map<String, Object>> getVariants() {
        return Arrays.<Map<String, Object>>asList(
                variant("default", "达梦 DM", "host", "port", "database", "username", "password", "options"));
    }
    public List<Map<String, Object>> getDataTypes() {
        return Arrays.<Map<String, Object>>asList(
                dataType("INT", null, null, null), dataType("BIGINT", null, null, null),
                dataType("NUMBER", null, 10, 2), dataType("VARCHAR", 255, null, null),
                dataType("VARCHAR2", 255, null, null), dataType("CHAR", 10, null, null),
                dataType("DATE", null, null, null), dataType("TIMESTAMP", null, null, null),
                dataType("CLOB", null, null, null), dataType("BLOB", null, null, null)
        );
    }
    public SqlDialectCapabilities getCapabilities() { return SqlDialectCapabilities.managed(false); }
    public Map<String, Boolean> getRuntimeSupport() { return Map.of("java", true, "php", false); }
    public String buildTestSql() { return "SELECT BANNER AS version FROM V$VERSION WHERE ROWNUM = 1"; }
    public String buildDatabasesSql() {
        return "SELECT USER AS name FROM DUAL UNION SELECT DISTINCT OWNER AS name FROM ALL_TABLES ORDER BY name";
    }
    public String buildCreateDatabaseSql(String database) {
        throw new IllegalArgumentException("dm 暂不支持通过该接口创建数据库实例");
    }
    public String buildTablesSql(SqlObjectRef namespace) {
        String database = namespace == null ? null : namespace.namespace();
        String ownerExpr = isBlank(database) ? "USER" : formatLiteral(database);
        return "SELECT t.TABLE_NAME AS name, t.OWNER AS schema_name, c.COMMENTS AS remarks " +
                "FROM ALL_TABLES t LEFT JOIN ALL_TAB_COMMENTS c ON c.OWNER = t.OWNER AND c.TABLE_NAME = t.TABLE_NAME " +
                "WHERE t.OWNER = " + ownerExpr + " ORDER BY t.TABLE_NAME";
    }
    public String buildTableColumnsSql(SqlObjectRef tableRef) {
        String database = tableRef == null ? null : tableRef.namespace();
        String table = tableRef == null ? null : tableRef.name();
        String ownerExpr = isBlank(database) ? "USER" : formatLiteral(database);
        return "SELECT c.COLUMN_NAME AS name, c.DATA_TYPE AS type, CASE WHEN c.NULLABLE = 'Y' THEN 'YES' ELSE 'NO' END AS nullable, " +
                "c.DATA_DEFAULT AS default_value, (SELECT cc.COMMENTS FROM ALL_COL_COMMENTS cc " +
                "WHERE cc.OWNER = c.OWNER AND cc.TABLE_NAME = c.TABLE_NAME AND cc.COLUMN_NAME = c.COLUMN_NAME) AS remarks, c.CHAR_LENGTH AS length, " +
                "c.DATA_PRECISION AS numeric_precision, c.DATA_SCALE AS numeric_scale, " +
                "CASE WHEN EXISTS (SELECT 1 FROM ALL_CONSTRAINTS ac JOIN ALL_CONS_COLUMNS acc " +
                "ON ac.OWNER = acc.OWNER AND ac.CONSTRAINT_NAME = acc.CONSTRAINT_NAME " +
                "WHERE ac.CONSTRAINT_TYPE = 'P' AND acc.OWNER = c.OWNER AND acc.TABLE_NAME = c.TABLE_NAME " +
                "AND acc.COLUMN_NAME = c.COLUMN_NAME) THEN 1 ELSE 0 END AS primary_key FROM ALL_TAB_COLUMNS c " +
                "WHERE c.OWNER = " + ownerExpr + " AND c.TABLE_NAME = " + formatLiteral(table) + " ORDER BY c.COLUMN_ID";
    }
}
