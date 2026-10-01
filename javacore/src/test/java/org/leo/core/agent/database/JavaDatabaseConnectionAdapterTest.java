package org.leo.core.agent.database;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaDatabaseConnectionAdapterTest {

    private final JavaDatabaseConnectionAdapter adapter = new JavaDatabaseConnectionAdapter();

    @Test
    void createsJdbcParametersFromCanonicalMysqlConnection() {
        Map<String, Object> result = adapter.adapt(DatabaseConnectionSpec.fromMap(Map.of(
                "dialect", "mysql", "connectionMode", "standard",
                "host", "db.internal", "port", 3307, "database", "inventory",
                "username", "app", "password", "secret", "options", Map.of("useSSL", false))));

        assertEquals("jdbc", result.get("provider"));
        assertEquals("com.mysql.cj.jdbc.Driver", result.get("driverClass"));
        assertTrue(String.valueOf(result.get("jdbcUrl")).startsWith("jdbc:mysql://db.internal:3307/inventory?"));
        assertEquals("app", result.get("username"));
    }

    @Test
    void supportsOracleSidAndExplicitJavaOverride() {
        Map<String, Object> oracle = adapter.adapt(DatabaseConnectionSpec.fromMap(Map.of(
                "dialect", "oracle", "connectionMode", "standard",
                "variant", "sid", "host", "oracle.internal", "sid", "ORCL")));
        assertEquals("jdbc:oracle:thin:@oracle.internal:1521:ORCL", oracle.get("jdbcUrl"));

        Map<String, Object> overridden = adapter.adapt(DatabaseConnectionSpec.fromMap(Map.of(
                "dialect", "sqlite", "connectionMode", "custom",
                "runtimeOptions", Map.of("java", Map.of(
                        "driverClass", "custom.Driver", "jdbcUrl", "jdbc:custom:value")))));
        assertEquals("custom.Driver", overridden.get("driverClass"));
        assertEquals("jdbc:custom:value", overridden.get("jdbcUrl"));
    }

    @Test
    void createsOfficialDmAndKingbaseJdbcParameters() {
        Map<String, Object> dm = adapter.adapt(DatabaseConnectionSpec.fromMap(Map.of(
                "dialect", "dm", "connectionMode", "standard",
                "host", "dm.internal", "database", "APP")));
        assertEquals("dm.jdbc.driver.DmDriver", dm.get("driverClass"));
        assertEquals("jdbc:dm://dm.internal:5236", dm.get("jdbcUrl"));

        Map<String, Object> kingbase = adapter.adapt(DatabaseConnectionSpec.fromMap(Map.of(
                "dialect", "kingbasees", "connectionMode", "standard",
                "host", "kes.internal", "database", "inventory")));
        assertEquals("com.kingbase8.Driver", kingbase.get("driverClass"));
        assertEquals("jdbc:kingbase8://kes.internal:54321/inventory", kingbase.get("jdbcUrl"));
    }

    @Test
    void forwardsGenericJdbcPropertiesAndExecutionLimits() {
        Map<String, Object> result = adapter.adapt(DatabaseConnectionSpec.fromMap(Map.of(
                "dialect", "generic", "connectionMode", "custom",
                "username", "app",
                "options", Map.of("tenant", "north"),
                "runtimeOptions", Map.of("java", Map.of(
                        "driverClass", "vendor.CustomDriver",
                        "jdbcUrl", "jdbc:custom://db.internal/main",
                        "properties", Map.of("sslMode", "verify-full"),
                        "connectionProperties", Map.of("tenant", "override"),
                        "queryTimeoutSeconds", 12,
                        "maxRows", 500,
                        "maxResultBytes", 2048,
                        "maxCellBytes", 512,
                        "fetchSize", 50)))));

        assertEquals("vendor.CustomDriver", result.get("driverClass"));
        assertEquals("jdbc:custom://db.internal/main", result.get("jdbcUrl"));
        Map<?, ?> properties = (Map<?, ?>) result.get("connectionProperties");
        assertEquals("override", properties.get("tenant"));
        assertEquals("verify-full", properties.get("sslMode"));
        assertEquals(12, result.get("queryTimeoutSeconds"));
        assertEquals(500, result.get("maxRows"));
        assertEquals(2048, result.get("maxResultBytes"));
        assertEquals(512, result.get("maxCellBytes"));
        assertEquals(50, result.get("fetchSize"));
    }
}
