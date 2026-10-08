package org.leo.service.sql;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.leo.core.agent.capability.SqlCapable;
import org.leo.service.sql.dialect.SqlDialectRegistry;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AgentNodeSqlServiceExternalDatabaseContractTest {

    @Test
    void mysqlContractWhenTestConnectionIsConfigured() throws Exception {
        Assumptions.assumeTrue(PhpComponentSqlCapable.phpAvailable(), "PHP CLI is not installed");
        Assumptions.assumeTrue(
                PhpComponentSqlCapable.pdoDriverAvailable("mysql"), "pdo_mysql is not installed");
        Map<String, Object> connection = externalConnection("mysql", 3306);
        runContract(connection, String.valueOf(connection.get("database")), "`", "`", "VARCHAR(120)");
    }

    @Test
    void postgresqlContractWhenTestConnectionIsConfigured() throws Exception {
        Assumptions.assumeTrue(PhpComponentSqlCapable.phpAvailable(), "PHP CLI is not installed");
        Assumptions.assumeTrue(
                PhpComponentSqlCapable.pdoDriverAvailable("pgsql"), "pdo_pgsql is not installed");
        Map<String, Object> connection = externalConnection("postgresql", 5432);
        String schema = configuration("postgresql", "schema", "public");
        runContract(connection, schema, "\"", "\"", "VARCHAR(120)");
    }

    private void runContract(Map<String, Object> connection,
                             String namespace,
                             String quoteStart,
                             String quoteEnd,
                             String textType) throws Exception {
        AgentNodeSqlService service = new AgentNodeSqlService(new SqlDialectRegistry());
        SqlCapable php = PhpComponentSqlCapable.create();
        Map<String, Object> tested = service.testConnection(php, connection);
        assertEquals(true, tested.get("success"), String.valueOf(tested.get("message")));

        String table = "leo_contract_" + Long.toUnsignedString(System.nanoTime(), 36).toLowerCase(Locale.ROOT);
        boolean postgresql = "postgresql".equals(connection.get("dialect"));
        SqlObjectRef tableRef = postgresql
                ? SqlObjectRef.table(null, namespace, table)
                : SqlObjectRef.table(namespace, null, table);
        String qualifiedTable = quoteStart + namespace.replace(quoteEnd, quoteEnd + quoteEnd) + quoteEnd
                + "." + quoteStart + table + quoteEnd;
        try {
            service.createTable(php, connection, tableRef, List.of(
                    Map.of("name", "id", "type", "BIGINT", "nullable", false, "primaryKey", true),
                    Map.of("name", "value", "type", textType, "nullable", false),
                    Map.of("name", "note", "type", "TEXT", "nullable", true)));

            Map<String, Object> first = new LinkedHashMap<String, Object>();
            first.put("id", 1);
            first.put("value", "O'Reilly");
            first.put("note", null);
            service.insertRow(php, connection, tableRef, first);
            service.insertRow(php, connection, tableRef,
                    Map.of("id", 2, "value", "中文值", "note", ""));

            Map<String, Object> result = service.queryTable(
                    php, connection, tableRef, 1, 20,
                    List.of("id", "value", "note"),
                    List.of(Map.of("field", "id", "direction", "DESC")),
                    List.of(Map.of("field", "id", "operator", "in", "value", List.of(1, 2))),
                    true, null);
            assertEquals(2L, ((Number) ((Map<?, ?>) result.get("pagination")).get("total")).longValue());
            assertEquals("中文值", ((Map<?, ?>) ((List<?>) result.get("rows")).get(0)).get("value"));

            List<?> columns = (List<?>) service.getTableColumns(
                    php, connection, tableRef).get("columns");
            assertEquals(List.of("id", "value", "note"), columns.stream()
                    .map(item -> String.valueOf(((Map<?, ?>) item).get("name"))).toList());

            service.updateRows(php, connection, tableRef,
                    Map.of("type", "pk", "values", Map.of("id", 1)),
                    Map.of("value", "updated", "note", "ok"));
            assertEquals(1, ((Number) service.deleteRows(php, connection, tableRef,
                    Map.of("type", "pk", "values", Map.of("id", 2)))
                    .get("affectedRows")).intValue());

            Map<String, Object> remaining = service.executeSql(
                    php, connection, "SELECT COUNT(*) AS total FROM " + qualifiedTable);
            assertEquals(1, ((Number) ((Map<?, ?>) ((List<?>) remaining.get("rows")).get(0))
                    .get("total")).intValue());
        } finally {
            service.executeSql(php, connection, "DROP TABLE IF EXISTS " + qualifiedTable);
        }
    }

    private Map<String, Object> externalConnection(String dialect, int defaultPort) {
        String host = configuration(dialect, "host", "");
        String database = configuration(dialect, "database", "");
        String username = configuration(dialect, "username", "");
        Assumptions.assumeTrue(!host.isBlank() && !database.isBlank() && !username.isBlank(),
                "external " + dialect + " contract is not configured");

        Map<String, Object> connection = new LinkedHashMap<String, Object>();
        connection.put("dialect", dialect);
        connection.put("connectionMode", "standard");
        connection.put("variant", "default");
        connection.put("host", host);
        connection.put("port", Integer.parseInt(configuration(
                dialect, "port", String.valueOf(defaultPort))));
        connection.put("database", database);
        connection.put("username", username);
        connection.put("password", configuration(dialect, "password", ""));
        connection.put("timeoutSeconds", 10);
        return connection;
    }

    private String configuration(String dialect, String key, String defaultValue) {
        String property = System.getProperty("leo.test." + dialect + "." + key);
        if (property != null) return property.trim();
        String environment = System.getenv(
                "LEO_TEST_" + dialect.toUpperCase(Locale.ROOT) + "_" + key.toUpperCase(Locale.ROOT));
        return environment == null ? defaultValue : environment.trim();
    }
}
