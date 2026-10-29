package org.leo.service;

import org.junit.jupiter.api.Test;
import org.leo.core.entity.AgentDatabaseConnection;
import org.leo.core.agent.database.DatabaseConnectionSpec;
import org.leo.dao.mapper.AgentDatabaseConnectionMapper;
import org.leo.service.security.DatabaseCredentialCryptoService;
import org.leo.service.sql.dialect.SqlDialectRegistry;

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

class DatabaseConnectionProfileServiceTest {

    @Test
    void createsSanitizedAgentOwnedProfile() {
        Fixture fixture = fixture();
        doAnswer(invocation -> {
            AgentDatabaseConnection saved = invocation.getArgument(0);
            assertEquals("agent-1", saved.getAgentId());
            assertEquals("user-1", saved.getCreateUserId());
            return 1;
        }).when(fixture.mapper()).insert(any(AgentDatabaseConnection.class));

        Map<String, Object> result = fixture.profile().create(
                "user-1", "agent-1", Map.of(
                        "connectionName", "inventory",
                        "connection", mysqlConnection("db.internal", "secret")));

        assertEquals("inventory", result.get("connectionName"));
        assertFalse(String.valueOf(result).contains("secret"));
    }

    @Test
    void newProfilesKeepTheEffectiveTimeout() {
        Fixture fixture = fixture();
        doAnswer(invocation -> {
            AgentDatabaseConnection saved = invocation.getArgument(0);
            assertEquals(45, saved.getTimeoutSeconds());
            return 1;
        }).when(fixture.mapper()).insert(any(AgentDatabaseConnection.class));

        Map<String, Object> result = fixture.profile().create("user-1", "agent-1", Map.of(
                "connection", mysqlConnection("db.internal", "secret"),
                "timeoutSeconds", 45));

        assertFalse(result.containsKey("maxConnections"));
        assertEquals(45, result.get("timeoutSeconds"));
    }

    @Test
    void patchUpdateRetainsUnspecifiedConnectionFieldsAndStoredPassword() {
        Fixture fixture = fixture();
        AgentDatabaseConnection existing = existing(fixture.persistence(), fixture.crypto());
        when(fixture.mapper().selectById("connection-1")).thenReturn(existing);
        doAnswer(invocation -> {
            AgentDatabaseConnection persisted = invocation.getArgument(0);
            assertEquals("existing-secret", fixture.crypto().decrypt(persisted.getPassword()));
            return 1;
        }).when(fixture.mapper()).update(any(AgentDatabaseConnection.class));

        fixture.profile().update("user-2", "agent-1", "connection-1", Map.of(
                "description", "collected from application.yml",
                "connection", Map.of("host", "db-new.internal")));

        DatabaseConnectionSpec restored = fixture.persistence().toConnectionSpec(existing);
        assertEquals("db-new.internal", restored.getHost());
        assertEquals("inventory", restored.getDatabase());
        assertEquals("existing-secret", restored.getPassword());
        assertEquals("collected from application.yml", existing.getDescription());
    }

    @Test
    void rejectsUnknownStandardDialectAndExplainsGenericCustomFallback() {
        Fixture fixture = fixture();

        DatabaseConnectionProfileException error = assertThrows(
                DatabaseConnectionProfileException.class,
                () -> fixture.profile().create("user-1", "agent-1", Map.of(
                        "connection", Map.of(
                                "dialect", "inventeddb",
                                "connectionMode", "standard",
                                "host", "db.internal"))));

        assertEquals(DatabaseConnectionProfileException.Kind.VALIDATION, error.getKind());
        assertTrue(error.getMessage().contains("dialect=generic"));
        assertTrue(error.getMessage().contains("driverClass"));
        assertTrue(error.getMessage().contains("jdbcUrl"));
    }

    @Test
    void acceptsDomesticStandardDialectAliasesAndPersistsCanonicalIds() {
        Fixture fixture = fixture();
        when(fixture.mapper().insert(any(AgentDatabaseConnection.class))).thenReturn(1);

        Map<String, Object> dm = fixture.profile().create(
                "user-1", "agent-1", Map.of(
                        "connection", Map.of(
                                "dialect", "dameng",
                                "connectionMode", "standard",
                                "host", "dm.internal")));

        assertEquals("dm", ((Map<?, ?>) dm.get("connection")).get("dialect"));
    }

    @Test
    void acceptsGenericCustomJdbcProfile() {
        Fixture fixture = fixture();
        when(fixture.mapper().insert(any(AgentDatabaseConnection.class))).thenReturn(1);

        Map<String, Object> result = fixture.profile().create(
                "user-1", "agent-1", Map.of(
                        "connection", Map.of(
                                "dialect", "generic",
                                "connectionMode", "custom",
                                "runtimeOptions", Map.of("java", Map.of(
                                        "driverClass", "dm.jdbc.driver.DmDriver",
                                        "jdbcUrl", "jdbc:dm://db.internal:5236/APP")))));

        assertEquals("generic", ((Map<?, ?>) result.get("connection")).get("dialect"));
    }

    @Test
    void resolvesOnlyEnabledProfilesBoundToTheCurrentAgent() {
        Fixture fixture = fixture();
        AgentDatabaseConnection existing = existing(fixture.persistence(), fixture.crypto());
        when(fixture.mapper().selectById("connection-1")).thenReturn(existing);

        DatabaseConnectionSpec resolved = fixture.profile().resolveActive(
                "user-1", "agent-1", "connection-1");
        assertEquals("existing-secret", resolved.getPassword());

        DatabaseConnectionProfileException mismatch = assertThrows(
                DatabaseConnectionProfileException.class,
                () -> fixture.profile().resolveActive(
                        "user-1", "agent-2", "connection-1"));
        assertEquals(DatabaseConnectionProfileException.Kind.FORBIDDEN, mismatch.getKind());

        existing.setStatus(0);
        DatabaseConnectionProfileException disabled = assertThrows(
                DatabaseConnectionProfileException.class,
                () -> fixture.profile().resolveActive(
                        "user-1", "agent-1", "connection-1"));
        assertEquals(DatabaseConnectionProfileException.Kind.VALIDATION, disabled.getKind());
    }

    @Test
    void deletesWithAgentScopeAtTheMapperBoundary() {
        Fixture fixture = fixture();
        AgentDatabaseConnection existing = existing(fixture.persistence(), fixture.crypto());
        when(fixture.mapper().selectById("connection-1")).thenReturn(existing);
        when(fixture.mapper().deleteByIdAndAgent("connection-1", "agent-1"))
                .thenReturn(1);

        fixture.profile().delete("user-1", "agent-1", "connection-1");

        verify(fixture.mapper()).deleteByIdAndAgent("connection-1", "agent-1");
    }

    @Test
    void editingThePublicViewRestoresMaskedAndOmittedSecretsInTheActualSavePath() {
        Fixture fixture = fixture();
        AgentDatabaseConnection existing = customExisting(fixture);
        when(fixture.mapper().selectById("connection-1")).thenReturn(existing);
        when(fixture.mapper().update(any(AgentDatabaseConnection.class))).thenReturn(1);
        @SuppressWarnings("unchecked")
        Map<String, Object> view = (Map<String, Object>) fixture.persistence()
                .toConnectionView(existing).get("connection");
        assertFalse(view.toString().contains("stored-token"));
        assertFalse(view.toString().contains("url-secret"));

        Map<String, Object> response = fixture.profile().update("user-1", "agent-1", "connection-1",
                Map.of("connection", view, "description", "edited"));

        DatabaseConnectionSpec restored = fixture.persistence().toConnectionSpec(existing);
        assertEquals("stored-token", restored.getOptions().get("apiToken"));
        assertEquals("jdbc:vendor://app:url-secret@db.internal/db?token=query-secret",
                restored.runtimeOptions("java").get("jdbcUrl"));
        assertEquals("stored-key", ((Map<?, ?>) restored.runtimeOptions("java")
                .get("connectionProperties")).get("privateKey"));
        assertEquals("existing-secret", restored.getPassword());
        assertEquals("edited", existing.getDescription());
        assertFalse(response.toString().contains("stored-token"));
        assertFalse(response.toString().contains("url-secret"));
        verify(fixture.mapper()).update(existing);
    }

    @Test
    void explicitCredentialChangesTakePrecedenceOverStoredProtectedValues() {
        Fixture fixture = fixture();
        AgentDatabaseConnection existing = customExisting(fixture);
        when(fixture.mapper().selectById("connection-1")).thenReturn(existing);
        var persistedSpec = new java.util.concurrent.atomic.AtomicReference<DatabaseConnectionSpec>();
        doAnswer(invocation -> {
            persistedSpec.set(fixture.persistence().toConnectionSpec(invocation.getArgument(0)));
            return 1;
        }).when(fixture.mapper()).update(any(AgentDatabaseConnection.class));

        fixture.profile().update("user-1", "agent-1", "connection-1", Map.of("connection", Map.of(
                "password", "new-password", "options", Map.of("apiToken", "new-token"),
                "runtimeOptions", Map.of("java", Map.of("jdbcUrl", "jdbc:vendor://new-host/db")))));

        DatabaseConnectionSpec restored = persistedSpec.get();
        assertEquals("new-password", restored.getPassword());
        assertEquals("new-token", restored.getOptions().get("apiToken"));
        assertEquals("jdbc:vendor://new-host/db", restored.runtimeOptions("java").get("jdbcUrl"));
    }

    private AgentDatabaseConnection customExisting(Fixture fixture) {
        AgentDatabaseConnection existing = existing(fixture.persistence(), fixture.crypto());
        fixture.persistence().applyConnectionSpec(existing, DatabaseConnectionSpec.fromMap(Map.of(
                "dialect", "generic", "connectionMode", "custom", "options", Map.of("apiToken", "stored-token"),
                "runtimeOptions", Map.of("java", Map.of("driverClass", "custom.Driver",
                        "jdbcUrl", "jdbc:vendor://app:url-secret@db.internal/db?token=query-secret",
                        "connectionProperties", Map.of("privateKey", "stored-key"))))));
        return existing;
    }

    private Fixture fixture() {
        AgentDatabaseConnectionMapper mapper = mock(AgentDatabaseConnectionMapper.class);
        DatabaseCredentialCryptoService crypto =
                new DatabaseCredentialCryptoService("profile-test-key", "unused");
        AgentDatabaseConnectionService persistence =
                new AgentDatabaseConnectionService(mapper, crypto);
        return new Fixture(mapper, crypto, persistence,
                new DatabaseConnectionProfileService(persistence, new SqlDialectRegistry()));
    }

    private AgentDatabaseConnection existing(AgentDatabaseConnectionService persistence,
                                                DatabaseCredentialCryptoService crypto) {
        AgentDatabaseConnection connection = new AgentDatabaseConnection();
        connection.setConnectionId("connection-1");
        connection.setConnectionName("inventory");
        connection.setAgentId("agent-1");
        connection.setCreateUserId("user-1");
        persistence.applyConnectionSpec(connection,
                DatabaseConnectionSpec.fromMap(
                        mysqlConnection("db.internal", "existing-secret")));
        connection.setPassword(crypto.encrypt("existing-secret"));
        return connection;
    }

    private Map<String, Object> mysqlConnection(String host, String password) {
        return Map.of(
                "dialect", "mysql",
                "connectionMode", "standard",
                "host", host,
                "port", 3306,
                "database", "inventory",
                "username", "app",
                "password", password);
    }

    private record Fixture(AgentDatabaseConnectionMapper mapper,
                           DatabaseCredentialCryptoService crypto,
                           AgentDatabaseConnectionService persistence,
                           DatabaseConnectionProfileService profile) {}
}
