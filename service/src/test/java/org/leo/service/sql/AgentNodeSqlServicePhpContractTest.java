package org.leo.service.sql;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.leo.core.agent.capability.SqlCapable;
import org.leo.service.sql.dialect.SqlDialectRegistry;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentNodeSqlServicePhpContractTest {

    private Path database;
    private AgentNodeSqlService service;
    private Map<String, Object> connection;

    @BeforeEach
    void setUp() throws Exception {
        Assumptions.assumeTrue(PhpComponentSqlCapable.phpAvailable(), "PHP CLI is not installed");
        Assumptions.assumeTrue(
                PhpComponentSqlCapable.pdoDriverAvailable("sqlite"), "pdo_sqlite is not installed");
        database = Files.createTempFile("leo-php-sql-service-", ".sqlite");
        service = new AgentNodeSqlService(new SqlDialectRegistry());
        connection = new LinkedHashMap<>();
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
    void supportsTheDatabaseManagementWorkflowThroughSqlCapable() throws Exception {
        SqlCapable php = PhpComponentSqlCapable.create();

        Map<String, Object> tested = service.testConnection(php, connection);
        assertTrue(String.valueOf(tested.get("databaseVersion")).matches("\\d+\\.\\d+.*"));

        SqlObjectRef tableRef = SqlObjectRef.table("main", null, "inventory");
        Map<String, Object> created = service.createTable(php, connection, tableRef, List.of(
                Map.of("name", "id", "type", "INTEGER", "nullable", false, "primaryKey", true),
                Map.of("name", "name", "type", "TEXT", "nullable", false),
                Map.of("name", "quantity", "type", "INTEGER", "nullable", true)));
        assertEquals("inventory", ((Map<?, ?>) created.get("objectRef")).get("name"));

        assertEquals(1, ((Number) service.insertRow(php, connection, tableRef,
                Map.of("id", 1, "name", "alpha", "quantity", 3)).get("affectedRows")).intValue());
        assertEquals(1, ((Number) service.insertRow(php, connection, tableRef,
                Map.of("id", 2, "name", "beta", "quantity", 5)).get("affectedRows")).intValue());

        List<?> databases = (List<?>) service.getDatabases(php, connection).get("databases");
        assertEquals("main", ((Map<?, ?>) databases.get(0)).get("name"));

        List<?> tables = (List<?>) service.getTables(
                php, connection, SqlObjectRef.catalog("main")).get("tables");
        Map<?, ?> inventory = tables.stream().map(Map.class::cast)
                .filter(item -> "inventory".equals(item.get("name"))).findFirst().orElseThrow();
        assertFalse(inventory.containsKey("rowCount"));
        assertEquals("table", inventory.get("kind"));
        assertEquals("inventory", ((Map<?, ?>) inventory.get("ref")).get("name"));

        List<?> columns = (List<?>) service.getTableColumns(php, connection, tableRef).get("columns");
        assertEquals(List.of("id", "name", "quantity"), columns.stream()
                .map(item -> String.valueOf(((Map<?, ?>) item).get("name"))).toList());
        assertTrue(Boolean.TRUE.equals(((Map<?, ?>) columns.get(0)).get("primaryKey")));

        Map<String, Object> page = service.queryTable(php, connection, tableRef,
                1, 1, List.of("id", "name", "quantity"),
                List.of(Map.of("field", "id", "direction", "DESC")), List.of(), true, null);
        assertEquals(2L, ((Number) ((Map<?, ?>) page.get("pagination")).get("total")).longValue());
        List<?> pageRows = (List<?>) page.get("rows");
        assertEquals(1, pageRows.size());
        assertEquals("beta", ((Map<?, ?>) pageRows.get(0)).get("name"));
        assertFalse(((List<?>) page.get("columns")).isEmpty());

        assertEquals(1, ((Number) service.updateRows(php, connection, tableRef,
                Map.of("type", "pk", "values", Map.of("id", 1)),
                Map.of("quantity", 9)).get("affectedRows")).intValue());
        Map<String, Object> query = service.executeSql(php, connection,
                "SELECT quantity FROM inventory WHERE id = 1");
        assertEquals(9, ((Number) ((Map<?, ?>) ((List<?>) query.get("rows")).get(0)).get("quantity")).intValue());

        assertEquals(1, ((Number) service.deleteRows(php, connection, tableRef,
                Map.of("type", "pk", "values", Map.of("id", 2))).get("affectedRows")).intValue());
    }

}
