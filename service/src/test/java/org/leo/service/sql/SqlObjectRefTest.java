package org.leo.service.sql;

import org.junit.jupiter.api.Test;
import org.leo.service.sql.dialect.SqlServerDialect;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqlObjectRefTest {

    @Test
    void normalizesAndSerializesOnlyPresentCoordinates() {
        SqlObjectRef ref = new SqlObjectRef(" app ", " sales ", " orders ", " table ");

        assertEquals("sales", ref.namespace());
        assertEquals("app", ref.toMap().get("catalog"));
        assertEquals("sales", ref.toMap().get("schema"));
        assertEquals("orders", ref.toMap().get("name"));
        assertEquals("table", ref.toMap().get("kind"));
    }

    @Test
    void restoresRefsFromPersistedTaskMetadata() {
        SqlObjectRef ref = SqlObjectRef.fromMap(Map.of(
                "catalog", "app", "schema", "sales", "name", "orders", "kind", "table"));

        assertEquals(SqlObjectRef.table("app", "sales", "orders"), ref);
        assertNull(SqlObjectRef.fromMap(Map.of()));
        List<SqlObjectRef> refs = List.of(ref, SqlObjectRef.table("app", "crm", "customers"));
        assertEquals(refs, SqlExportTask.refListValue(SqlExportTask.toRefMaps(refs)));
    }

    @Test
    void includesSchemaInExportEntryNamesToAvoidCollisions() {
        assertEquals("app_sales_orders", SqlExportTask.exportObjectName(
                SqlObjectRef.table("app", "sales", "orders")));
        assertEquals("app_crm_orders", SqlExportTask.exportObjectName(
                SqlObjectRef.table("app", "crm", "orders")));
    }

    @Test
    void sqlServerUsesCatalogAndSchemaForQueriesAndColumnMetadata() {
        SqlServerDialect dialect = new SqlServerDialect();
        SqlObjectRef ref = SqlObjectRef.table("app", "sales", "orders");

        String query = dialect.buildQueryTableCommand(ref, 1, 20,
                List.of(), List.of(), List.of()).sql();
        String columns = dialect.buildTableColumnsSql(ref);

        assertTrue(query.contains("FROM [app].[sales].[orders]"));
        assertTrue(columns.contains("FROM [app].sys.columns"));
        assertTrue(columns.contains("s.name = 'sales'"));
        assertTrue(columns.contains("tb.name = 'orders'"));
    }
}
