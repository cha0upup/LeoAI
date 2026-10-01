package org.leo.core.agent.database;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatabaseConnectionSpecTest {

    @Test
    void parsesCanonicalConnectionWithoutRuntimeTechnology() {
        DatabaseConnectionSpec spec = DatabaseConnectionSpec.fromMap(Map.of(
                "dialect", "mysql", "connectionMode", "standard", "variant", "default",
                "host", "db.internal", "port", 3306,
                "database", "inventory", "username", "app", "password", "secret",
                "options", Map.of("charset", "utf8mb4")));

        assertEquals("mysql", spec.getDialect());
        assertEquals("standard", spec.getConnectionMode());
        assertEquals("db.internal", spec.getHost());
        assertEquals("inventory", spec.getDatabase());
        assertTrue(spec.getRuntimeOptions().isEmpty());
    }

    @Test
    void validatesRequiredRuntimeNeutralLocator() {
        assertThrows(IllegalArgumentException.class,
                () -> DatabaseConnectionSpec.fromMap(Map.of(
                        "dialect", "sqlite", "connectionMode", "standard")));
    }

    @Test
    void validatesCustomRuntimeConnectorPairs() {
        assertThrows(IllegalArgumentException.class,
                () -> DatabaseConnectionSpec.fromMap(Map.of(
                        "dialect", "generic", "connectionMode", "custom",
                        "runtimeOptions", Map.of("java", Map.of("driverClass", "custom.Driver")))));

        DatabaseConnectionSpec spec = DatabaseConnectionSpec.fromMap(Map.of(
                "dialect", "generic", "connectionMode", "custom",
                "runtimeOptions", Map.of("java", Map.of(
                        "driverClass", "custom.Driver", "jdbcUrl", "jdbc:custom:value"))));
        assertEquals("jdbc:custom:value", spec.runtimeOptions("java").get("jdbcUrl"));
    }
}
