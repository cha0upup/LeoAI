package org.leo.service.sql;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.leo.core.agent.capability.SqlCapable;
import org.leo.core.agent.database.DatabaseConnectionSpec;
import org.leo.core.agent.database.SqlCommand;
import org.leo.service.sql.dialect.SqlDialectRegistry;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AgentNodeSqlServiceResultBoundaryTest {

    @ParameterizedTest
    @ValueSource(strings = {"create", "insert", "update", "delete"})
    void tableMutationsShareResultShapeWithoutLosingBoundParameters(String operation) throws Exception {
        AgentNodeSqlService service = new AgentNodeSqlService(new SqlDialectRegistry());
        SqlObjectRef table = SqlObjectRef.table("main", null, "orders");
        AtomicReference<SqlCommand> executed = new AtomicReference<>();
        SqlCapable node = sqlNode((connection, command) -> {
            executed.set(command);
            return Map.of("code", 200, "affectedRows", "7");
        });

        Map<String, Object> result = mutateTable(service, node, table, operation);

        assertEquals(Map.of("objectRef", table.toMap(), "sql", executed.get().sql(), "affectedRows", 7), result);
        assertEquals(switch (operation) {
            case "insert" -> List.of("O'Reilly");
            case "update" -> List.of("O'Reilly", 9);
            case "delete" -> List.of(9);
            default -> List.of();
        }, executed.get().parameters());
    }

    @ParameterizedTest
    @ValueSource(strings = {"create", "insert", "update", "delete"})
    void tableMutationsPreserveStructuredExecutionErrors(String operation) {
        AgentNodeSqlService service = new AgentNodeSqlService(new SqlDialectRegistry());
        Map<String, Object> failure = Map.of("code", 409, "msg", "constraint conflict",
                "errorCategory", "CONSTRAINT", "sqlState", "23000", "vendorCode", 19);
        SqlExecutionException error = assertThrows(SqlExecutionException.class, () -> mutateTable(
                service, sqlNode((connection, command) -> failure),
                SqlObjectRef.table("main", null, "orders"), operation));
        assertEquals(409, error.getStatusCode());
        assertEquals("constraint conflict", error.getMessage());
        assertEquals("23000", error.details().get("sqlState"));
        assertEquals(19, error.details().get("vendorCode"));
    }

    private static Map<String, Object> mutateTable(AgentNodeSqlService service, SqlCapable node,
                                                   SqlObjectRef table, String operation) throws Exception {
        Map<String, Object> connection = Map.of("dialect", "sqlite", "connectionMode", "standard", "file", ":memory:");
        Map<String, Object> where = Map.of("type", "pk", "values", Map.of("id", 9));
        return switch (operation) {
            case "create" -> service.createTable(node, connection, table,
                    List.of(Map.of("name", "name", "type", "TEXT")));
            case "insert" -> service.insertRow(node, connection, table, Map.of("name", "O'Reilly"));
            case "update" -> service.updateRows(node, connection, table, where, Map.of("name", "O'Reilly"));
            case "delete" -> service.deleteRows(node, connection, table, where);
            default -> throw new IllegalArgumentException(operation);
        };
    }

    @Test
    void rejectsOldStringStatusCodesFromNodeSqlResults() {
        AgentNodeSqlService service = new AgentNodeSqlService(new SqlDialectRegistry());
        SqlCapable agent = sqlNode((connection, command) ->
                Map.of("code", "200", "rows", List.of(Map.of("value", 1))));

        assertThrows(SqlExecutionException.class,
                () -> service.executeSql(agent,
                        Map.of("dialect", "sqlite", "connectionMode", "standard", "file", ":memory:"),
                        "SELECT 1", null));
    }

    @Test
    void tableMetadataReturnsStructuredRefsWithoutPerTableCountQueries() throws Exception {
        AgentNodeSqlService service = new AgentNodeSqlService(new SqlDialectRegistry());
        AtomicInteger executions = new AtomicInteger();
        SqlCapable agent = sqlNode((connection, sqlScript) -> {
            executions.incrementAndGet();
            return Map.of(
                    "code", 200,
                    "rows", List.of(
                            Map.of("name", "orders", "schema_name", "sales"),
                            Map.of("name", "customers", "schema_name", "sales")));
        });

        Map<String, Object> result = service.getTables(agent,
                Map.of("dialect", "postgresql", "connectionMode", "standard",
                        "host", "localhost", "database", "app"),
                SqlObjectRef.schema("sales"));

        assertEquals(1, executions.get());
        List<?> tables = (List<?>) result.get("tables");
        assertEquals(2, tables.size());
        Map<?, ?> first = (Map<?, ?>) tables.get(0);
        assertEquals("sales", ((Map<?, ?>) first.get("ref")).get("schema"));
        assertEquals("orders", ((Map<?, ?>) first.get("ref")).get("name"));
    }

    @Test
    void skipsCountQueryWhenCallerAlreadyHasPaginationTotal() throws Exception {
        AgentNodeSqlService service = new AgentNodeSqlService(new SqlDialectRegistry());
        AtomicInteger executions = new AtomicInteger();
        SqlCapable agent = sqlNode((connection, sqlScript) -> {
            executions.incrementAndGet();
            return Map.of("code", 200, "rows", List.of(Map.of("id", 1)));
        });

        Map<String, Object> result = service.queryTable(
                agent,
                Map.of("dialect", "postgresql", "connectionMode", "standard",
                        "host", "localhost", "database", "app"),
                SqlObjectRef.table(null, "sales", "orders"),
                2,
                20,
                List.of("id"),
                List.of(),
                List.of(),
                false,
                null);

        assertEquals(1, executions.get());
        assertEquals(false, ((Map<?, ?>) result.get("pagination")).containsKey("total"));
    }

    @Test
    void exposesRuntimeResultBoundariesToApiCallers() throws Exception {
        AgentNodeSqlService service = new AgentNodeSqlService(new SqlDialectRegistry());
        SqlCapable agent = new SqlCapable() {
            @Override
            public Map<String, Object> inspectDatabaseRuntime(Map<String, Object> connection) {
                return Map.of("code", 200, "available", true);
            }

            @Override
            public Map<String, Object> executeSql(DatabaseConnectionSpec connection, SqlCommand command) {
                return Map.of(
                        "code", 200,
                        "columns", List.of(Map.of("name", "value", "label", "value", "type", "TEXT")),
                        "rows", List.of(Map.of("value", "partial")),
                        "rowCount", 1,
                        "affectedRows", 0,
                        "generatedKey", "42",
                        "truncated", true,
                        "truncationReason", "MAX_RESULT_BYTES",
                        "resultBytes", 2048,
                        "runtimeMetadata", Map.of("provider", "jdbc"));
            }
        };

        Map<String, Object> result = service.executeSql(agent,
                Map.of("dialect", "sqlite", "connectionMode", "standard", "file", ":memory:"),
                "SELECT value", null);

        assertEquals(1, result.get("rowCount"));
        assertEquals("42", result.get("generatedKey"));
        assertEquals(true, result.get("truncated"));
        assertEquals("MAX_RESULT_BYTES", result.get("truncationReason"));
        assertEquals(2048, result.get("resultBytes"));
        assertEquals("jdbc", ((Map<?, ?>) result.get("runtimeMetadata")).get("provider"));
    }

    @Test
    void genericDialectUsesConfiguredHealthCheckAndRejectsStructuredMetadata() throws Exception {
        AgentNodeSqlService service = new AgentNodeSqlService(new SqlDialectRegistry());
        String[] executedSql = new String[1];
        SqlCapable agent = sqlNode((connection, sqlScript) -> {
            executedSql[0] = sqlScript.sql();
            return Map.of("code", 200, "rows", List.of(Map.of("status", "ok")));
        });
        Map<String, Object> connection = Map.of(
                "dialect", "generic",
                "connectionMode", "custom",
                "dialectOptions", Map.of("testSql", "VALUES 'ok'"),
                "runtimeOptions", Map.of("java", Map.of(
                        "driverClass", "com.vendor.Driver",
                        "jdbcUrl", "jdbc:vendor:test")));

        Map<String, Object> result = service.testConnection(agent, connection);

        assertEquals("VALUES 'ok'", executedSql[0]);
        assertEquals("ok", result.get("databaseVersion"));
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.getDatabases(agent, connection));
        assertEquals("Generic SQL 方言不支持能力 listDatabases，请直接执行厂商 SQL", error.getMessage());
    }

    @Test
    void canonicalizesDialectAliasesBeforeRuntimeInspectionAndExecution() throws Exception {
        AgentNodeSqlService service = new AgentNodeSqlService(new SqlDialectRegistry());
        String[] inspectedDialect = new String[1];
        String[] executedDialect = new String[1];
        SqlCapable agent = new SqlCapable() {
            @Override
            public Map<String, Object> inspectDatabaseRuntime(Map<String, Object> connection) {
                inspectedDialect[0] = String.valueOf(connection.get("dialect"));
                return Map.of("code", 200, "available", true);
            }

            @Override
            public Map<String, Object> executeSql(DatabaseConnectionSpec connection, SqlCommand command) {
                executedDialect[0] = connection.getDialect();
                return Map.of("code", 200, "rows", List.of(Map.of("version", "DM8")));
            }
        };

        Map<String, Object> result = service.testConnection(agent,
                Map.of("dialect", "dameng", "connectionMode", "standard",
                        "host", "dm.internal", "port", 5236, "database", "APP"));

        assertEquals(true, result.get("success"));
        assertEquals("dm", inspectedDialect[0]);
        assertEquals("dm", executedDialect[0]);
    }

    @Test
    void unavailableDriverStopsBeforeConnectionAndReturnsActionableDiagnostics() throws Exception {
        AgentNodeSqlService service = new AgentNodeSqlService(new SqlDialectRegistry());
        AtomicBoolean executed = new AtomicBoolean();
        SqlCapable agent = new SqlCapable() {
            @Override
            public Map<String, Object> executeSql(DatabaseConnectionSpec connection, SqlCommand command) {
                executed.set(true);
                return Map.of("code", 200);
            }

            @Override
            public Map<String, Object> inspectDatabaseRuntime(Map<String, Object> connection) {
                return Map.of(
                        "code", 200,
                        "runtime", "java",
                        "provider", "jdbc",
                        "available", true,
                        "requestedDriver", Map.of(
                                "id", "missing.Driver",
                                "available", false,
                                "message", "JDBC 驱动类不可用"));
            }
        };

        Map<String, Object> result = service.testConnection(agent,
                Map.of("dialect", "sqlite", "connectionMode", "standard", "file", ":memory:"));

        assertEquals(false, result.get("success"));
        assertEquals("driver", result.get("failureStage"));
        assertEquals("DRIVER_NOT_FOUND", result.get("errorCategory"));
        assertEquals(false, executed.get());
        assertEquals("failed", ((Map<?, ?>) ((List<?>) result.get("diagnostics")).get(0))
                .get("status"));
    }

    @Test
    void classifiesProviderAuthenticationAndNetworkFailuresByStage() throws Exception {
        AgentNodeSqlService service = new AgentNodeSqlService(new SqlDialectRegistry());
        SqlCapable missingProvider = new SqlCapable() {
            @Override
            public Map<String, Object> executeSql(DatabaseConnectionSpec connection, SqlCommand command) {
                throw new AssertionError("provider preflight must stop execution");
            }

            @Override
            public Map<String, Object> inspectDatabaseRuntime(Map<String, Object> connection) {
                return Map.of("code", 200, "runtime", "php", "provider", "pdo",
                        "available", false, "msg", "PDO extension is missing");
            }
        };
        Map<String, Object> providerResult = service.testConnection(missingProvider,
                Map.of("dialect", "sqlite", "connectionMode", "standard", "file", ":memory:"));
        assertEquals("PROVIDER_NOT_FOUND", providerResult.get("errorCategory"));
        assertEquals("driver", providerResult.get("failureStage"));

        Map<String, Object> connection = Map.of(
                "dialect", "sqlite", "connectionMode", "standard", "file", ":memory:");
        SqlCapable authenticationFailure = sqlNode((spec, sql) -> Map.of(
                "code", 401, "errorCategory", "AUTHENTICATION_FAILED",
                "msg", "invalid credentials"));
        Map<String, Object> authenticationResult =
                service.testConnection(authenticationFailure, connection);
        assertEquals("authentication", authenticationResult.get("failureStage"));

        SqlCapable networkFailure = sqlNode((spec, sql) -> Map.of(
                "code", 503, "errorCategory", "CONNECTION_TIMEOUT",
                "msg", "connection timed out"));
        Map<String, Object> networkResult = service.testConnection(networkFailure, connection);
        assertEquals("network", networkResult.get("failureStage"));
    }

    @Test
    void preservesStructuredRuntimeErrorsForTheApiBoundary() {
        AgentNodeSqlService service = new AgentNodeSqlService(new SqlDialectRegistry());
        SqlCapable agent = sqlNode((spec, sql) -> Map.of(
                "code", 504,
                "msg", "statement timed out",
                "errorCategory", "QUERY_TIMEOUT",
                "sqlState", "HYT00",
                "retryable", true,
                "vendorCode", 17));

        SqlExecutionException error = assertThrows(SqlExecutionException.class,
                () -> service.executeSql(agent,
                        Map.of("dialect", "sqlite", "connectionMode", "standard", "file", ":memory:"),
                        "SELECT 1", null));

        assertEquals(504, error.getStatusCode());
        assertEquals("QUERY_TIMEOUT", error.details().get("errorCategory"));
        assertEquals("HYT00", error.details().get("sqlState"));
        assertEquals(true, error.details().get("retryable"));
        assertEquals(17, error.details().get("vendorCode"));
    }

    @Test
    void appliesValidatedRequestLevelQueryTimeoutsWithoutMutatingTheConnection() throws Exception {
        AgentNodeSqlService service = new AgentNodeSqlService(new SqlDialectRegistry());
        DatabaseConnectionSpec[] captured = new DatabaseConnectionSpec[1];
        SqlCapable agent = sqlNode((spec, sql) -> {
            captured[0] = spec;
            return Map.of("code", 200, "rows", List.of(Map.of("value", 1)));
        });
        Map<String, Object> connection = Map.of(
                "dialect", "sqlite",
                "connectionMode", "standard",
                "file", ":memory:",
                "timeoutSeconds", 30);

        service.executeSql(agent, connection, "SELECT 1", 45);

        assertEquals(45, captured[0].getTimeoutSeconds());
        assertEquals(45, captured[0].runtimeOptions("java").get("queryTimeoutSeconds"));
        assertEquals(30, connection.get("timeoutSeconds"));
        assertThrows(IllegalArgumentException.class,
                () -> service.executeSql(agent, connection, "SELECT 1", 301));
    }

    @Test
    void failedRuntimeInspectionStopsBeforeSqlExecution() throws Exception {
        SqlCapable node = new SqlCapable() {
            @Override
            public Map<String, Object> executeSql(DatabaseConnectionSpec connection, SqlCommand command) {
                throw new AssertionError("failed inspection must stop execution");
            }

            @Override
            public Map<String, Object> inspectDatabaseRuntime(Map<String, Object> connection) {
                return Map.of("code", 501, "msg", "unsupported");
            }
        };
        Map<String, Object> result = new AgentNodeSqlService(new SqlDialectRegistry()).testConnection(
                node, Map.of("dialect", "sqlite", "connectionMode", "standard", "file", ":memory:"));
        assertEquals(false, result.get("success"));
        assertEquals("CAPABILITY_INSPECTION_FAILED", result.get("errorCategory"));
    }

    private static SqlCapable sqlNode(BiFunction<DatabaseConnectionSpec, SqlCommand, Map<String, Object>> execute) {
        return new SqlCapable() {
            @Override
            public Map<String, Object> executeSql(DatabaseConnectionSpec connection, SqlCommand command) {
                return execute.apply(connection, command);
            }

            @Override
            public Map<String, Object> inspectDatabaseRuntime(Map<String, Object> connection) {
                return Map.of("code", 200, "available", true);
            }
        };
    }

}
