package org.leo.service.sql.dialect;

import org.leo.service.sql.SqlObjectRef;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

public class PostgreSqlDialect extends AbstractSqlDialect {

    public String getType() { return "postgresql"; }
    public String getName() { return "PostgreSQL"; }
    public Integer getDefaultPort() { return 5432; }
    public List<String> getNamespaceLevels() { return List.of("schema"); }
    public List<Map<String, Object>> getVariants() { return Arrays.<Map<String, Object>>asList(
            variant("default", "PostgreSQL", "host", "port", "database", "username", "password", "options")); }
    public List<Map<String, Object>> getDataTypes() {
        return Arrays.<Map<String, Object>>asList(
                dataType("INT", null, null, null), dataType("BIGINT", null, null, null),
                dataType("VARCHAR", 255, null, null), dataType("TEXT", null, null, null),
                dataType("NUMERIC", null, 10, 2), dataType("TIMESTAMP", null, null, null),
                dataType("UUID", null, null, null), dataType("JSONB", null, null, null)
        );
    }
    public String buildTestSql() { return "SELECT version() AS version"; }
    public String buildDatabasesSql() {
        return "SELECT schema_name AS name FROM information_schema.schemata " +
                "WHERE schema_name <> 'information_schema' AND schema_name NOT LIKE 'pg_%' ORDER BY schema_name";
    }
    public String buildTablesSql(SqlObjectRef namespace) {
        String database = namespace == null ? null : namespace.namespace();
        String schema = isBlank(database) ? "public" : database;
        return "SELECT it.table_name AS name, it.table_schema AS schema_name, " +
                "obj_description(pc.oid, 'pg_class') AS remarks FROM information_schema.tables it " +
                "JOIN pg_catalog.pg_namespace pn ON pn.nspname = it.table_schema " +
                "JOIN pg_catalog.pg_class pc ON pc.relnamespace = pn.oid AND pc.relname = it.table_name " +
                "WHERE it.table_schema = " + formatLiteral(schema) +
                " AND it.table_type = 'BASE TABLE' ORDER BY it.table_name";
    }
    public String buildTableColumnsSql(SqlObjectRef tableRef) {
        String database = tableRef == null ? null : tableRef.namespace();
        String table = tableRef == null ? null : tableRef.name();
        String schema = isBlank(database) ? "public" : database;
        return "SELECT c.column_name AS name, c.data_type AS type, c.is_nullable AS nullable, c.column_default AS default_value, " +
                "col_description(pc.oid, pa.attnum) AS remarks, " +
                "c.character_maximum_length AS length, c.numeric_precision AS numeric_precision, c.numeric_scale AS numeric_scale, " +
                "CASE WHEN EXISTS (SELECT 1 FROM information_schema.table_constraints tc " +
                "JOIN information_schema.key_column_usage kcu ON tc.constraint_name = kcu.constraint_name " +
                "AND tc.table_schema = kcu.table_schema AND tc.table_name = kcu.table_name " +
                "WHERE tc.constraint_type = 'PRIMARY KEY' AND kcu.table_schema = c.table_schema " +
                "AND kcu.table_name = c.table_name AND kcu.column_name = c.column_name) THEN 1 ELSE 0 END AS primary_key " +
                "FROM information_schema.columns c " +
                "JOIN pg_catalog.pg_namespace pn ON pn.nspname = c.table_schema " +
                "JOIN pg_catalog.pg_class pc ON pc.relnamespace = pn.oid AND pc.relname = c.table_name " +
                "JOIN pg_catalog.pg_attribute pa ON pa.attrelid = pc.oid AND pa.attname = c.column_name " +
                "AND pa.attnum > 0 AND NOT pa.attisdropped " +
                "WHERE c.table_schema = " + formatLiteral(schema) + " AND c.table_name = " + formatLiteral(table) + " ORDER BY c.ordinal_position";
    }
    protected String buildQualifiedTable(SqlObjectRef tableRef) {
        String database = tableRef == null ? null : tableRef.namespace();
        String table = tableRef == null ? null : tableRef.name();
        String schema = isBlank(database) ? "public" : database;
        return escapeIdentifier(schema) + "." + escapeIdentifier(table);
    }
}
