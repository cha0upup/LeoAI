package org.leo.phpcore.database;

import org.junit.jupiter.api.Test;
import org.leo.core.agent.database.DatabaseConnectionSpec;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PhpDatabaseConnectionAdapterTest {

    private final PhpDatabaseConnectionAdapter adapter = new PhpDatabaseConnectionAdapter();

    @Test
    void createsPdoParametersWithoutJdbcFields() {
        Map<String, Object> result = adapter.adapt(DatabaseConnectionSpec.fromMap(Map.of(
                "dialect", "mysql", "connectionMode", "standard",
                "host", "db.internal", "database", "inventory",
                "username", "app", "password", "secret", "options", Map.of("charset", "utf8mb4"))));

        assertEquals("pdo", result.get("provider"));
        assertEquals("mysql", result.get("pdoDriver"));
        assertEquals("mysql:host=db.internal;port=3306;dbname=inventory;charset=utf8mb4", result.get("dsn"));
        assertFalse(result.containsKey("jdbcUrl"));
        assertFalse(result.containsKey("driverClass"));
    }

    @Test
    void createsNativeSqliteAndOracleServiceDsns() {
        Map<String, Object> sqlite = adapter.adapt(DatabaseConnectionSpec.fromMap(Map.of(
                "dialect", "sqlite", "connectionMode", "standard",
                "variant", "file", "file", "/tmp/example.sqlite")));
        assertEquals("sqlite:/tmp/example.sqlite", sqlite.get("dsn"));

        Map<String, Object> oracle = adapter.adapt(DatabaseConnectionSpec.fromMap(Map.of(
                "dialect", "oracle", "connectionMode", "standard",
                "variant", "service", "host", "oracle.internal", "service", "ORCLPDB1")));
        assertTrue(String.valueOf(oracle.get("dsn")).startsWith("oci:dbname=//oracle.internal:1521/ORCLPDB1"));
    }

    @Test
    void doesNotInventStandardPdoContractsForDmOrKingbase() {
        IllegalArgumentException dm = assertThrows(IllegalArgumentException.class,
                () -> adapter.adapt(DatabaseConnectionSpec.fromMap(Map.of(
                        "dialect", "dm", "connectionMode", "standard", "host", "dm.internal"))));
        assertTrue(dm.getMessage().contains("PHP 不支持数据库类型"));

        IllegalArgumentException kingbase = assertThrows(IllegalArgumentException.class,
                () -> adapter.adapt(DatabaseConnectionSpec.fromMap(Map.of(
                        "dialect", "kingbasees", "connectionMode", "standard",
                        "host", "kes.internal", "database", "inventory"))));
        assertTrue(kingbase.getMessage().contains("PHP 不支持数据库类型"));
    }

    @Test
    void usesCustomPdoConnectorForAnUnknownDatabase() {
        Map<String, Object> result = adapter.adapt(DatabaseConnectionSpec.fromMap(Map.of(
                "dialect", "generic",
                "connectionMode", "custom",
                "username", "app",
                "password", "secret",
                "runtimeOptions", Map.of("php", Map.of(
                        "pdoDriver", "odbc",
                        "dsn", "odbc:Warehouse")))));

        assertEquals("odbc", result.get("pdoDriver"));
        assertEquals("odbc:Warehouse", result.get("dsn"));
        assertEquals("app", result.get("username"));
    }
}
