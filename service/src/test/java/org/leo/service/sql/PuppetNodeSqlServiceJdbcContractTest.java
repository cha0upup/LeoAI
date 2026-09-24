package org.leo.service.sql;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.leo.core.component.DatabaseComponent;
import org.leo.core.puppet.capability.SqlCapable;
import org.leo.core.puppet.database.DatabaseConnectionSpec;
import org.leo.core.puppet.database.JavaDatabaseConnectionAdapter;
import org.leo.core.puppet.database.SqlCommand;
import org.leo.service.sql.dialect.SqlDialectRegistry;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PuppetNodeSqlServiceJdbcContractTest {

    private Path database;
    private PuppetNodeSqlService service;
    private SqlCapable jdbc;
    private Map<String, Object> connection;

    @BeforeEach
    void setUp() throws Exception {
        database = Files.createTempFile("leo-jdbc-sql-service-", ".sqlite");
        service = new PuppetNodeSqlService(new SqlDialectRegistry());
        jdbc = new JavaComponentSqlCapable();
        connection = new LinkedHashMap<String, Object>();
        connection.put("dialect", "sqlite");
        connection.put("connectionMode", "standard");
        connection.put("variant", "file");
        connection.put("file", database.toAbsolutePath().toString());
        connection.put("username", "");
        connection.put("password", "");
    }

    @AfterEach
    void tearDown() throws Exception {
        if (database != null) Files.deleteIfExists(database);
    }

    @Test
    void supportsCrudFiltersNullsAndQuotedIdentifiersThroughJdbcComponent() throws Exception {
        Map<String, Object> tested = service.testConnection(jdbc, connection);
        assertEquals(true, tested.get("success"));
        assertTrue(String.valueOf(tested.get("databaseVersion")).matches("\\d+\\.\\d+.*"));

        String table = "order details";
        SqlObjectRef tableRef = SqlObjectRef.table("main", null, table);
        service.createTable(jdbc, connection, tableRef, List.of(
                Map.of("name", "id", "type", "INTEGER", "nullable", false, "primaryKey", true),
                Map.of("name", "select", "type", "TEXT", "nullable", false),
                Map.of("name", "备注", "type", "TEXT", "nullable", true),
                Map.of("name", "amount", "type", "NUMERIC", "nullable", true)));

        Map<String, Object> first = new LinkedHashMap<String, Object>();
        first.put("id", 1);
        first.put("select", "O'Reilly");
        first.put("备注", null);
        first.put("amount", new BigDecimal("12.50"));
        service.insertRow(jdbc, connection, tableRef, first);

        Map<String, Object> second = new LinkedHashMap<String, Object>();
        second.put("id", 2);
        second.put("select", "中文值");
        second.put("备注", "");
        second.put("amount", 0);
        service.insertRow(jdbc, connection, tableRef, second);

        List<?> tables = (List<?>) service.getTables(
                jdbc, connection, SqlObjectRef.catalog("main")).get("tables");
        Map<?, ?> metadata = tables.stream().map(Map.class::cast)
                .filter(item -> table.equals(item.get("name"))).findFirst().orElseThrow();
        assertEquals(table, ((Map<?, ?>) metadata.get("ref")).get("name"));

        List<?> columns = (List<?>) service.getTableColumns(jdbc, connection, tableRef).get("columns");
        assertEquals(List.of("id", "select", "备注", "amount"), columns.stream()
                .map(item -> String.valueOf(((Map<?, ?>) item).get("name"))).toList());
        assertEquals(true, ((Map<?, ?>) columns.get(0)).get("primaryKey"));

        Map<String, Object> nullPage = service.queryTable(
                jdbc, connection, tableRef, 1, 20,
                List.of("id", "select", "备注", "amount"),
                List.of(Map.of("field", "id", "direction", "ASC")),
                List.of(
                        Map.of("field", "select", "operator", "like", "value", "%Reilly%"),
                        Map.of("field", "备注", "operator", "is_null")), true, null);
        assertEquals(1L, ((Number) ((Map<?, ?>) nullPage.get("pagination")).get("total")).longValue());
        Map<?, ?> nullRow = (Map<?, ?>) ((List<?>) nullPage.get("rows")).get(0);
        assertEquals("O'Reilly", nullRow.get("select"));
        assertEquals(null, nullRow.get("备注"));

        Map<String, Object> paged = service.queryTable(
                jdbc, connection, tableRef, 2, 1,
                List.of("id", "select", "备注"),
                List.of(Map.of("field", "id", "direction", "ASC")),
                List.of(Map.of("field", "id", "operator", "in", "value", List.of(1, 2))), true, null);
        assertEquals(2L, ((Number) ((Map<?, ?>) paged.get("pagination")).get("total")).longValue());
        assertEquals("中文值", ((Map<?, ?>) ((List<?>) paged.get("rows")).get(0)).get("select"));
        assertEquals("", ((Map<?, ?>) ((List<?>) paged.get("rows")).get(0)).get("备注"));

        service.updateRows(jdbc, connection, tableRef,
                Map.of("type", "pk", "values", Map.of("id", 1)),
                Map.of("select", "", "备注", "已更新"));
        Map<String, Object> updated = service.queryTable(
                jdbc, connection, tableRef, 1, 20,
                List.of("id", "select", "备注"), List.of(),
                List.of(Map.of("field", "id", "operator", "eq", "value", 1)), false, null);
        assertFalse(((Map<?, ?>) updated.get("pagination")).containsKey("total"));
        Map<?, ?> updatedRow = (Map<?, ?>) ((List<?>) updated.get("rows")).get(0);
        assertEquals("", updatedRow.get("select"));
        assertEquals("已更新", updatedRow.get("备注"));

        assertEquals(1, ((Number) service.deleteRows(jdbc, connection, tableRef,
                Map.of("type", "pk", "values", Map.of("id", 2))).get("affectedRows")).intValue());
        Map<String, Object> remaining = service.executeSql(jdbc, connection,
                "SELECT COUNT(*) AS total FROM \"order details\"");
        assertEquals(1, ((Number) ((Map<?, ?>) ((List<?>) remaining.get("rows")).get(0))
                .get("total")).intValue());
    }

    private static final class JavaComponentSqlCapable implements SqlCapable {
        private final JavaDatabaseConnectionAdapter adapter = new JavaDatabaseConnectionAdapter();

        @Override
        public Map<String, Object> executeSql(DatabaseConnectionSpec connection,
                                              SqlCommand command) throws Exception {
            Map<String, Object> params = new LinkedHashMap<String, Object>(adapter.adapt(connection));
            params.put("sql", command.sql());
            if (command.hasParameters()) params.put("parameters", command.parameters());
            return invoke(params);
        }

        @Override
        public Map<String, Object> inspectDatabaseRuntime(Map<String, Object> connection) throws Exception {
            Map<String, Object> params = new LinkedHashMap<String, Object>();
            params.put("operation", "capabilities");
            params.put("requestedDriver", adapter.defaultDriver(String.valueOf(connection.get("dialect"))));
            return invoke(params);
        }

        @SuppressWarnings("unchecked")
        private Map<String, Object> invoke(Map<String, Object> params) throws Exception {
            DatabaseComponent component = new DatabaseComponent();
            HashMap<String, Object> results = new HashMap<String, Object>();
            setField(component, "params", new HashMap<String, Object>(params));
            setField(component, "results", results);
            component.invoke();
            return results;
        }

        private void setField(Object target, String name, Object value) throws Exception {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        }
    }
}
