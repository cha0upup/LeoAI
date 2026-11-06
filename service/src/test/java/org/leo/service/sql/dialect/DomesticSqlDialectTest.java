package org.leo.service.sql.dialect;

import org.junit.jupiter.api.Test;
import org.leo.service.sql.SqlObjectRef;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DomesticSqlDialectTest {

    @Test
    void dmUsesOwnerSemanticsAndRownumPagination() {
        DmSqlDialect dialect = new DmSqlDialect();

        assertEquals("SELECT BANNER AS version FROM V$VERSION WHERE ROWNUM = 1", dialect.buildTestSql());
        assertTrue(dialect.buildDatabasesSql().contains("DISTINCT OWNER"));
        String tablesSql = dialect.buildTablesSql(SqlObjectRef.schema("APP"));
        assertTrue(tablesSql.contains("t.OWNER = 'APP'"));
        assertTrue(tablesSql.contains("AS remarks"));
        SqlObjectRef table = SqlObjectRef.table(null, "APP", "ORDERS");
        assertTrue(dialect.buildTableColumnsSql(table).contains("c.TABLE_NAME = 'ORDERS'"));
        String query = dialect.buildQueryTableCommand(table, 2, 20,
                List.of("id"), List.of(Map.of("field", "id", "direction", "ASC")), List.of()).sql();
        assertTrue(query.contains("ROWNUM <= 40"));
        assertTrue(query.endsWith("WHERE rn > 20"));
        SqlObjectRef quotedTable = SqlObjectRef.table(null, "MixedSchema", "OrderDetails");
        assertTrue(dialect.buildQueryTableCommand(
                quotedTable, 1, 20, List.of(), List.of(), List.of()).sql()
                .contains("\"MixedSchema\".\"OrderDetails\""));
        assertThrows(IllegalArgumentException.class, () -> dialect.buildCreateDatabaseSql("app"));
    }

    @Test
    void kingbaseUsesPostgresqlCompatibleMetadataAndPagination() {
        KingbaseEsDialect dialect = new KingbaseEsDialect();

        assertTrue(dialect.buildDatabasesSql().contains("information_schema.schemata"));
        assertTrue(dialect.buildTablesSql(SqlObjectRef.schema("sales")).contains("table_schema = 'sales'"));
        SqlObjectRef table = SqlObjectRef.table(null, "sales", "orders");
        String columnsSql = dialect.buildTableColumnsSql(table);
        assertTrue(columnsSql.contains("information_schema.columns"));
        assertTrue(columnsSql.contains("CASE WHEN EXISTS"));
        assertTrue(columnsSql.contains("col_description(pc.oid, pa.attnum) AS remarks"));
        assertFalse(columnsSql.contains("LEFT JOIN information_schema.key_column_usage"));
        assertTrue(dialect.buildTablesSql(SqlObjectRef.schema("sales"))
                .contains("obj_description(pc.oid, 'pg_class') AS remarks"));
        String query = dialect.buildQueryTableCommand(table, 3, 25,
                List.of(), List.of(), List.of()).sql();
        assertTrue(query.endsWith("LIMIT 25 OFFSET 50"));
        assertThrows(IllegalArgumentException.class, () -> dialect.buildCreateDatabaseSql("warehouse"));
    }

    @Test
    void sharedQualificationPaginationAndBindingsMatchTheirDialectFamilies() {
        assertSharedCommands(new KingbaseEsDialect(), new PostgreSqlDialect());
        assertSharedCommands(new DmSqlDialect(), new OracleDialect());
        var kingbase = new KingbaseEsDialect();
        var postgres = new PostgreSqlDialect();
        assertEquals(postgres.buildTablesSql(null), kingbase.buildTablesSql(null));
        assertEquals(postgres.buildTableColumnsSql(null), kingbase.buildTableColumnsSql(null));
        var namespace = SqlObjectRef.schema("Quoted'Schema");
        assertEquals(postgres.buildTablesSql(namespace), kingbase.buildTablesSql(namespace));
    }

    @Test
    void retainsNativeDatabaseIdentityTypesAndCapabilityLimits() {
        var kingbase = new KingbaseEsDialect();
        var dm = new DmSqlDialect();
        assertEquals("kingbasees", kingbase.getType());
        assertEquals(54321, kingbase.getDefaultPort());
        assertEquals("dm", dm.getType());
        assertEquals(5236, dm.getDefaultPort());
        for (AbstractSqlDialect dialect : List.of(kingbase, dm)) {
            assertEquals(List.of("schema"), dialect.getNamespaceLevels());
            assertEquals(Map.of("java", true, "php", false), dialect.getRuntimeSupport());
            assertFalse(dialect.getCapabilities().createDatabase());
            assertThrows(IllegalArgumentException.class, () -> dialect.buildCreateDatabaseSql("app"));
        }
        assertTrue(kingbase.getDataTypes().stream().anyMatch(type -> type.containsValue("BOOLEAN")));
        assertTrue(dm.getDataTypes().stream().anyMatch(type -> type.containsValue("VARCHAR2")));
        assertTrue(kingbase.buildDatabasesSql().contains("'sys_catalog'"));
        assertTrue(dm.buildDatabasesSql().contains("ALL_TABLES"));
    }

    private void assertSharedCommands(AbstractSqlDialect dialect, AbstractSqlDialect family) {
        for (SqlObjectRef table : List.of(
                SqlObjectRef.table(null, null, "orders"),
                SqlObjectRef.table(null, "Quoted\"Schema", "Order\"Details"),
                SqlObjectRef.table("catalog", null, "orders"))) {
            List<Map<String, Object>> filters = List.of(Map.of("field", "status", "operator", "eq", "value", "active"));
            assertEquals(family.buildQueryTableCommand(table, 2, 25, List.of("id"),
                            List.of(Map.of("field", "id", "direction", "DESC")), filters),
                    dialect.buildQueryTableCommand(table, 2, 25, List.of("id"),
                            List.of(Map.of("field", "id", "direction", "DESC")), filters));
            assertEquals(family.buildCountCommand(table, filters), dialect.buildCountCommand(table, filters));
            assertEquals(family.buildInsertCommand(table, Map.of("status", "active")),
                    dialect.buildInsertCommand(table, Map.of("status", "active")));
        }
    }
}
