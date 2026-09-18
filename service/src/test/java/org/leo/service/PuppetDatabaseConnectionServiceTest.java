package org.leo.service;

import org.junit.jupiter.api.Test;
import org.leo.core.entity.PuppetDatabaseConnection;
import org.leo.core.puppet.database.DatabaseConnectionSpec;
import org.leo.dao.mapper.PuppetDatabaseConnectionMapper;
import org.leo.service.security.DatabaseCredentialCryptoService;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PuppetDatabaseConnectionServiceTest {

    private final PuppetDatabaseConnectionMapper mapper = mock(PuppetDatabaseConnectionMapper.class);
    private final DatabaseCredentialCryptoService crypto = new DatabaseCredentialCryptoService("service-key", "unused");
    private final PuppetDatabaseConnectionService service = new PuppetDatabaseConnectionService(mapper, crypto);

    @Test
    void encryptsPasswordAtMapperBoundaryAndKeepsCallerValuePlaintext() {
        PuppetDatabaseConnection connection = connection("plain-secret");
        doAnswer(invocation -> {
            PuppetDatabaseConnection persisted = invocation.getArgument(0);
            assertTrue(crypto.isEncrypted(persisted.getPassword()));
            assertEquals("plain-secret", crypto.decrypt(persisted.getPassword()));
            return 1;
        }).when(mapper).insert(any(PuppetDatabaseConnection.class));

        assertTrue(service.saveOrUpdate(connection));
        assertEquals("plain-secret", connection.getPassword());
    }

    @Test
    void doesNotEncryptAnExistingCiphertextAgainAtMapperBoundary() {
        String encrypted = crypto.encrypt("plain-secret");
        PuppetDatabaseConnection connection = connection(encrypted);
        connection.setConnectionId("connection-1");
        doAnswer(invocation -> {
            PuppetDatabaseConnection persisted = invocation.getArgument(0);
            assertEquals(encrypted, persisted.getPassword());
            assertEquals("plain-secret", crypto.decrypt(persisted.getPassword()));
            return 1;
        }).when(mapper).update(any(PuppetDatabaseConnection.class));

        assertTrue(service.saveOrUpdate(connection));
        assertEquals(encrypted, connection.getPassword());
    }

    @Test
    void keepsStoredPasswordEncryptedUntilRuntimeSpecResolution() {
        PuppetDatabaseConnection stored = connection(crypto.encrypt("plain-secret"));
        stored.setConnectionId("connection-1");
        when(mapper.selectById("connection-1")).thenReturn(stored);

        PuppetDatabaseConnection result = service.findById("connection-1");

        assertTrue(crypto.isEncrypted(result.getPassword()));
        assertEquals("plain-secret", service.toConnectionSpec(result).getPassword());
        assertFalse(String.valueOf(service.toConnectionView(result)).contains("plain-secret"));
    }

    @Test
    void persistsAndRestoresRuntimeNeutralConnectionSpec() {
        PuppetDatabaseConnection connection = connection("plain-secret");
        DatabaseConnectionSpec spec = DatabaseConnectionSpec.fromMap(Map.of(
                "dialect", "mysql", "connectionMode", "custom", "variant", "default",
                "host", "db.internal", "port", 3307,
                "database", "inventory", "username", "app", "password", "plain-secret",
                "options", Map.of("charset", "utf8mb4"),
                "runtimeOptions", Map.of(
                        "java", Map.of("jdbcUrl", "jdbc:custom:inventory", "driverClass", "custom.Driver"),
                        "php", Map.of("dsn", "custom:inventory", "pdoDriver", "custom"))));

        service.applyConnectionSpec(connection, spec);
        connection.setPassword(crypto.encrypt(connection.getPassword()));
        DatabaseConnectionSpec restored = service.toConnectionSpec(connection);

        assertEquals("db.internal", restored.getHost());
        assertEquals("inventory", restored.getDatabase());
        assertEquals("utf8mb4", restored.getOptions().get("charset"));
        assertEquals("jdbc:custom:inventory", restored.runtimeOptions("java").get("jdbcUrl"));
        assertEquals("custom:inventory", restored.runtimeOptions("php").get("dsn"));
        assertEquals("plain-secret", restored.getPassword());
        assertFalse(connection.getConnectionSpec().contains("plain-secret"));
        assertEquals(0, connection.getTestStatus());
    }

    @Test
    void preservesStoredPasswordWhenAnUpdateOmitsTheSecret() {
        String encrypted = crypto.encrypt("existing-secret");
        PuppetDatabaseConnection connection = connection(encrypted);
        connection.setConnectionId("connection-1");
        DatabaseConnectionSpec update = DatabaseConnectionSpec.fromMap(Map.of(
                "dialect", "mysql", "connectionMode", "standard",
                "host", "db.internal", "port", 3306,
                "database", "inventory", "username", "app", "password", ""));

        service.applyConnectionSpec(connection, update);

        assertEquals(encrypted, connection.getPassword());
        assertEquals("db.internal", service.toConnectionSpec(connection).getHost());
        assertEquals("existing-secret", service.toConnectionSpec(connection).getPassword());
    }

    @Test
    void removesNestedAndInlineSecretsFromConnectionViews() {
        PuppetDatabaseConnection connection = connection(crypto.encrypt("plain-secret"));
        connection.setConnectionSpec(new String(org.leo.core.util.json.PortableJsonCodec.encode(Map.of(
                "dialect", "mysql", "connectionMode", "custom", "variant", "default",
                "host", "db.internal", "port", 3306,
                "database", "db", "options", Map.of("apiToken", "nested-secret", "charset", "utf8mb4"),
                "runtimeOptions", Map.of("java", Map.of(
                        "jdbcUrl", "jdbc:mysql://app:url-secret@db.internal/db?password=query-secret",
                        "driverClass", "custom.Driver")))), java.nio.charset.StandardCharsets.UTF_8));

        String view = String.valueOf(service.toConnectionView(connection));

        assertFalse(view.contains("plain-secret"));
        assertFalse(view.contains("nested-secret"));
        assertFalse(view.contains("url-secret"));
        assertFalse(view.contains("query-secret"));
        assertTrue(view.contains("charset=utf8mb4"));
    }

    @Test
    void restoresMaskedAndOmittedSecretsWhenEditingAProfile() {
        PuppetDatabaseConnection connection = connection(crypto.encrypt("plain-secret"));
        connection.setConnectionSpec(new String(org.leo.core.util.json.PortableJsonCodec.encode(Map.of(
                "dialect", "generic", "connectionMode", "custom",
                "options", Map.of("apiToken", "stored-token", "region", "cn"),
                "runtimeOptions", Map.of("java", Map.of(
                        "jdbcUrl", "jdbc:vendor://app:url-secret@db.internal/db?token=query-secret",
                        "driverClass", "custom.Driver",
                        "connectionProperties", Map.of("privateKey", "stored-key"))))),
                java.nio.charset.StandardCharsets.UTF_8));
        @SuppressWarnings("unchecked")
        Map<String, Object> safeConnection = (Map<String, Object>) service
                .toConnectionView(connection).get("connection");

        Map<String, Object> merged = service.mergeProtectedValues(connection, safeConnection);
        DatabaseConnectionSpec restored = DatabaseConnectionSpec.fromMap(merged);

        assertEquals("stored-token", restored.getOptions().get("apiToken"));
        assertEquals("jdbc:vendor://app:url-secret@db.internal/db?token=query-secret",
                restored.runtimeOptions("java").get("jdbcUrl"));
        assertEquals("stored-key",
                ((Map<?, ?>) restored.runtimeOptions("java").get("connectionProperties")).get("privateKey"));
    }

    @Test
    void recordsConnectionTestResultWithBoundedMessage() {
        when(mapper.updateTestStatus(any(), any(), any())).thenReturn(1);

        String message = "x".repeat(1200);

        assertTrue(service.recordTestResult("connection-1", false, message));
        verify(mapper).updateTestStatus("connection-1", 2, "x".repeat(1000));
    }

    @Test
    void updatesEnabledStateAndRejectsInactiveProfilesAtExecutionBoundary() {
        when(mapper.updateStatusByPuppet("connection-1", "puppet-1", 0)).thenReturn(1);
        PuppetDatabaseConnection connection = connection("plain-secret");
        connection.setConnectionId("connection-1");
        connection.setStatus(0);

        assertTrue(service.setEnabled("connection-1", "puppet-1", false));
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class, () -> service.toActiveConnectionSpec(connection));

        assertEquals("数据库连接已停用", error.getMessage());
        verify(mapper).updateStatusByPuppet("connection-1", "puppet-1", 0);
    }

    private static PuppetDatabaseConnection connection(String password) {
        PuppetDatabaseConnection connection = new PuppetDatabaseConnection();
        connection.setConnectionName("test");
        connection.setPuppetId("puppet-1");
        connection.setCreateUserId("user-1");
        DatabaseConnectionSpec spec = DatabaseConnectionSpec.fromMap(Map.of(
                "dialect", "mysql", "connectionMode", "standard", "variant", "default",
                "host", "localhost", "port", 3306,
                "database", "db", "username", "app", "password", password));
        connection.setDialect(spec.getDialect());
        connection.setUsername(spec.getUsername());
        connection.setPassword(spec.getPassword());
        connection.setConnectionSpec(new String(org.leo.core.util.json.PortableJsonCodec.encode(
                Map.of("dialect", "mysql", "connectionMode", "standard",
                        "variant", "default", "host", "localhost", "port", 3306,
                        "database", "db", "username", "app")), java.nio.charset.StandardCharsets.UTF_8));
        return connection;
    }
}
