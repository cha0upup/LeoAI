package org.leo.service.sql.dialect;

import org.junit.jupiter.api.Test;
import org.leo.core.agent.database.SqlCommand;
import org.leo.service.sql.SqlObjectRef;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MainstreamSqlDialectTest {

    @Test
    void mysqlQuotesCatalogTableAndColumnsWhileBindingValues() {
        MySqlDialect dialect = new MySqlDialect();
        SqlObjectRef table = SqlObjectRef.table("inventory-db", null, "order details");

        SqlCommand command = dialect.buildQueryTableCommand(
                table, 2, 50, List.of("select", "中文字段"),
                List.of(Map.of("field", "select", "direction", "DESC")),
                List.of(
                        Map.of("field", "select", "operator", "like", "value", "%O'Reilly%"),
                        Map.of("field", "中文字段", "operator", "in", "value", List.of("甲", "乙"))));

        assertTrue(command.sql().startsWith(
                "SELECT `select`, `中文字段` FROM `inventory-db`.`order details`"));
        assertTrue(command.sql().contains("`select` LIKE ? AND `中文字段` IN (?, ?)"));
        assertTrue(command.sql().endsWith("ORDER BY `select` DESC LIMIT 50 OFFSET 50"));
        assertEquals(List.of("%O'Reilly%", "甲", "乙"), command.parameters());
        assertFalse(command.sql().contains("O'Reilly"));

        assertTrue(dialect.buildTableColumnsSql(
                        SqlObjectRef.table("inventory'db", null, "order details"))
                .contains("c.TABLE_SCHEMA = 'inventory''db' AND c.TABLE_NAME = 'order details'"));
        assertTrue(dialect.buildTablesSql(SqlObjectRef.catalog("inventory-db"))
                .contains("TABLE_TYPE = 'BASE TABLE'"));
    }

    @Test
    void postgresqlUsesSchemaAndPrimaryKeyMetadataWithoutDuplicateColumnJoins() {
        PostgreSqlDialect dialect = new PostgreSqlDialect();
        SqlObjectRef table = SqlObjectRef.table(null, "sales ops", "order\"details");

        SqlCommand command = dialect.buildCountCommand(table, List.of(
                Map.of("field", "deleted at", "operator", "is_null"),
                Map.of("field", "status", "operator", "ne", "value", "closed")));

        assertEquals(
                "SELECT COUNT(*) AS total FROM \"sales ops\".\"order\"\"details\""
                        + " WHERE \"deleted at\" IS NULL AND \"status\" <> ?",
                command.sql());
        assertEquals(List.of("closed"), command.parameters());

        String metadataSql = dialect.buildTableColumnsSql(
                SqlObjectRef.table(null, "sales", "orders"));
        assertTrue(metadataSql.contains("CASE WHEN EXISTS"));
        assertTrue(metadataSql.contains("tc.constraint_type = 'PRIMARY KEY'"));
        assertTrue(metadataSql.contains("tc.table_name = kcu.table_name"));
        assertFalse(metadataSql.contains("LEFT JOIN information_schema.key_column_usage"));
        assertTrue(metadataSql.contains("col_description(pc.oid, pa.attnum) AS remarks"));
        assertTrue(dialect.buildTablesSql(SqlObjectRef.schema("sales"))
                .contains("obj_description(pc.oid, 'pg_class') AS remarks"));
    }

    @Test
    void oraclePreservesQuotedIdentifiersAndUsesAccessibleMetadataComments() {
        OracleDialect dialect = new OracleDialect();
        SqlObjectRef table = SqlObjectRef.table(null, "MixedSchema", "OrderDetails");

        assertTrue(dialect.buildDatabasesSql().contains("DISTINCT owner"));
        assertTrue(dialect.buildTablesSql(SqlObjectRef.schema("MixedSchema"))
                .contains("c.comments AS remarks"));
        String columnsSql = dialect.buildTableColumnsSql(table);
        assertTrue(columnsSql.contains("all_col_comments"));
        assertTrue(columnsSql.contains("CASE WHEN EXISTS"));
        assertTrue(columnsSql.contains("c.table_name = 'OrderDetails'"));
        assertTrue(dialect.buildQueryTableCommand(table, 1, 20,
                List.of(), List.of(), List.of()).sql()
                .contains("\"MixedSchema\".\"OrderDetails\""));
    }

    @Test
    void sqlServerUsesCatalogScopedDefaultsCommentsAndSchema() {
        SqlServerDialect dialect = new SqlServerDialect();
        SqlObjectRef table = SqlObjectRef.table("Sales Db", "audit", "Order Details");

        assertTrue(dialect.buildDatabasesSql().contains("HAS_DBACCESS(name) = 1"));
        String tablesSql = dialect.buildTablesSql(SqlObjectRef.catalog("Sales Db"));
        assertTrue(tablesSql.contains("[Sales Db].sys.extended_properties"));
        assertTrue(tablesSql.contains("ep.class = 1"));
        String columnsSql = dialect.buildTableColumnsSql(table);
        assertTrue(columnsSql.contains("[Sales Db].sys.default_constraints dc"));
        assertTrue(columnsSql.contains("dc.definition AS default_value"));
        assertTrue(columnsSql.contains("s.name = 'audit'"));
        assertTrue(dialect.buildQueryTableCommand(table, 1, 20,
                List.of(), List.of(), List.of()).sql()
                .contains("[Sales Db].[audit].[Order Details]"));
    }

    @Test
    void sqliteScopesMetadataAndQueriesToSelectedCatalog() {
        SqliteDialect dialect = new SqliteDialect();
        SqlObjectRef table = SqlObjectRef.table("main", null, "order details");

        assertTrue(dialect.buildDatabasesSql().contains("pragma_database_list"));
        assertTrue(dialect.buildTablesSql(SqlObjectRef.catalog("main"))
                .contains("FROM [main].sqlite_master"));
        String columnsSql = dialect.buildTableColumnsSql(table);
        assertTrue(columnsSql.contains("\"notnull\" = 1 OR pk > 0"));
        assertTrue(columnsSql.contains("FROM pragma_table_info('order details', 'main')"));
        assertTrue(dialect.buildQueryTableCommand(table, 1, 20,
                List.of(), List.of(), List.of()).sql()
                .contains("FROM [main].[order details]"));
    }
}
