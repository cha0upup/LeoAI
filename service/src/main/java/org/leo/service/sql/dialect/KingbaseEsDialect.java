package org.leo.service.sql.dialect;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** KingbaseES dialect with PostgreSQL-compatible SQL semantics and its own runtime identity. */
public class KingbaseEsDialect extends PostgreSqlDialect {

    public String getType() { return "kingbasees"; }
    public String getName() { return "人大金仓 KingbaseES"; }
    public Integer getDefaultPort() { return 54321; }
    public List<Map<String, Object>> getVariants() {
        return Arrays.<Map<String, Object>>asList(
                variant("default", "KingbaseES", "host", "port", "database", "username", "password", "options"));
    }
    public List<Map<String, Object>> getDataTypes() {
        return Arrays.<Map<String, Object>>asList(
                dataType("INT", null, null, null), dataType("BIGINT", null, null, null),
                dataType("VARCHAR", 255, null, null), dataType("TEXT", null, null, null),
                dataType("NUMERIC", null, 10, 2), dataType("DATE", null, null, null),
                dataType("TIMESTAMP", null, null, null), dataType("BOOLEAN", null, null, null),
                dataType("UUID", null, null, null), dataType("JSONB", null, null, null)
        );
    }
    public SqlDialectCapabilities getCapabilities() { return SqlDialectCapabilities.managed(false); }
    public Map<String, Boolean> getRuntimeSupport() { return Map.of("java", true, "php", false); }
    public String buildDatabasesSql() {
        return "SELECT schema_name AS name FROM information_schema.schemata " +
                "WHERE schema_name NOT IN ('information_schema', 'sys', 'sys_catalog') " +
                "AND schema_name NOT LIKE 'pg_%' ORDER BY schema_name";
    }
    public String buildCreateDatabaseSql(String database) {
        throw new IllegalArgumentException("kingbasees 暂不支持通过结构化接口创建数据库");
    }
}
