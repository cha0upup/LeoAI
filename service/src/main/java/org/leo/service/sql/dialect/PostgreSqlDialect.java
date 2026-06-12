package org.leo.service.sql.dialect;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

public class PostgreSqlDialect extends AbstractSqlDialect {

    public String getType() { return "postgresql"; }
    public String getName() { return "PostgreSQL"; }
    public Integer getDefaultPort() { return 5432; }
    public String getDriverClass() { return "org.postgresql.Driver"; }
    public String getUrlTemplate() { return "jdbc:postgresql://{host}:{port}/{database}?{params}"; }
    public List<Map<String, Object>> getVariants() { return Arrays.<Map<String, Object>>asList(variant("pg", "PostgreSQL", getDriverClass(), getUrlTemplate())); }
    public List<Map<String, Object>> getDataTypes() {
        return Arrays.<Map<String, Object>>asList(
                dataType("INT", null, null, null), dataType("BIGINT", null, null, null),
                dataType("VARCHAR", 255, null, null), dataType("TEXT", null, null, null),
                dataType("NUMERIC", null, 10, 2), dataType("TIMESTAMP", null, null, null),
                dataType("UUID", null, null, null), dataType("JSONB", null, null, null)
        );
    }
    public String buildTestSql() { return "SELECT version() AS version"; }
    public String buildDatabasesSql() { return "SELECT datname AS name FROM pg_database WHERE datistemplate = false ORDER BY datname"; }
    public String buildTablesSql(String database) {
        String schema = isBlank(database) ? "public" : database;
        return "SELECT table_name AS name, table_schema AS schema_name, '' AS comment FROM information_schema.tables " +
                "WHERE table_schema = " + formatLiteral(schema) + " AND table_type = 'BASE TABLE' ORDER BY table_name";
    }
    public String buildTableColumnsSql(String database, String table) {
        String schema = isBlank(database) ? "public" : database;
        return "SELECT c.column_name AS name, c.data_type AS type, c.is_nullable AS nullable, c.column_default AS default_value, '' AS comment, " +
                "c.character_maximum_length AS length, c.numeric_precision AS numeric_precision, c.numeric_scale AS numeric_scale, " +
                "CASE WHEN tc.constraint_type = 'PRIMARY KEY' THEN 1 ELSE 0 END AS primary_key " +
                "FROM information_schema.columns c " +
                "LEFT JOIN information_schema.key_column_usage kcu ON c.table_schema = kcu.table_schema AND c.table_name = kcu.table_name AND c.column_name = kcu.column_name " +
                "LEFT JOIN information_schema.table_constraints tc ON kcu.constraint_name = tc.constraint_name AND kcu.table_schema = tc.table_schema " +
                "WHERE c.table_schema = " + formatLiteral(schema) + " AND c.table_name = " + formatLiteral(table) + " ORDER BY c.ordinal_position";
    }
    protected String buildQualifiedTable(String database, String table) {
        String schema = isBlank(database) ? "public" : database;
        return escapeIdentifier(schema) + "." + escapeIdentifier(table);
    }
    protected String buildPaginationSql(String baseSql, int offset, int pageSize) { return baseSql + " LIMIT " + pageSize + " OFFSET " + offset; }
    protected String escapeIdentifier(String identifier) { return "\"" + identifier.replace("\"", "\"\"") + "\""; }
}
