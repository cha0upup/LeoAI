package org.leo.web.service;

import org.junit.jupiter.api.Test;
import org.leo.core.entity.PuppetDatabaseConnection;
import org.leo.core.entity.User;
import org.leo.core.puppet.database.DatabaseConnectionSpec;
import org.leo.dao.mapper.PuppetDatabaseConnectionMapper;
import org.leo.service.DatabaseConnectionProfileService;
import org.leo.service.PuppetDatabaseConnectionService;
import org.leo.service.security.DatabaseCredentialCryptoService;
import org.leo.service.sql.dialect.SqlDialectRegistry;
import org.leo.web.exception.ApiException;

import java.util.LinkedHashMap;
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

class DatabaseConnectionManagementServiceTest {

    private final PuppetDatabaseConnectionMapper mapper = mock(PuppetDatabaseConnectionMapper.class);
    private final DatabaseCredentialCryptoService crypto = new DatabaseCredentialCryptoService("management-key", "unused");
    private final PuppetDatabaseConnectionService persistence = new PuppetDatabaseConnectionService(mapper, crypto);
    private final DatabaseConnectionManagementService management = management(persistence);

    @Test
    void savesPuppetOwnedProfilesWithoutVisibilityScope() {
        doAnswer(invocation -> {
            PuppetDatabaseConnection saved = invocation.getArgument(0);
            assertEquals("puppet-1", saved.getPuppetId());
            assertEquals("mysql", saved.getDialect());
            assertTrue(crypto.isEncrypted(saved.getPassword()));
            return 1;
        }).when(mapper).insert(any(PuppetDatabaseConnection.class));

        Map<String, Object> result = management.save(user("creator"),
                "puppet-1", params(null, "secret"));

        assertEquals("inventory", result.get("connectionName"));
        assertEquals("mysql", connection(result).get("dialect"));
        assertFalse(result.containsKey("scope"));
        assertFalse(result.containsKey("canManage"));
        assertFalse(String.valueOf(result).contains("secret"));
    }

    @Test
    void anyUserConnectedToTheSamePuppetCanUpdateAndReuseStoredPassword() {
        PuppetDatabaseConnection existing = existing(persistence, crypto);
        when(mapper.selectById("connection-1")).thenReturn(existing);
        when(mapper.update(any(PuppetDatabaseConnection.class))).thenReturn(1);

        Map<String, Object> result = management.save(user("teammate"),
                "puppet-1", params("connection-1", ""));

        assertEquals("existing-secret", persistence.toConnectionSpec(existing).getPassword());
        assertEquals("mysql", connection(result).get("dialect"));
    }

    @Test
    void managementOperationsRejectConnectionsOwnedByAnotherPuppet() {
        PuppetDatabaseConnection existing = existing(persistence, crypto);
        when(mapper.selectById("connection-1")).thenReturn(existing);

        ApiException deleteError = assertThrows(ApiException.class,
                () -> management.delete("connection-1", "puppet-2", user("teammate")));
        ApiException statusError = assertThrows(ApiException.class,
                () -> management.setEnabled("connection-1", "puppet-2", false, user("teammate")));

        assertEquals(403, deleteError.getHttpStatus().value());
        assertEquals(403, statusError.getHttpStatus().value());
    }

    @Test
    void mutationsRemainScopedToTheResolvedPuppetAtTheMapperBoundary() {
        PuppetDatabaseConnection existing = existing(persistence, crypto);
        when(mapper.selectById("connection-1")).thenReturn(existing);
        when(mapper.updateStatusByPuppet("connection-1", "puppet-1", 0)).thenReturn(1);
        when(mapper.deleteByIdAndPuppet("connection-1", "puppet-1")).thenReturn(1);

        management.setEnabled("connection-1", "puppet-1", false, user("teammate"));
        management.delete("connection-1", "puppet-1", user("teammate"));

        verify(mapper).updateStatusByPuppet("connection-1", "puppet-1", 0);
        verify(mapper).deleteByIdAndPuppet("connection-1", "puppet-1");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> connection(Map<String, Object> view) {
        return (Map<String, Object>) view.get("connection");
    }

    private PuppetDatabaseConnection existing(PuppetDatabaseConnectionService persistence,
                                                DatabaseCredentialCryptoService crypto) {
        PuppetDatabaseConnection connection = new PuppetDatabaseConnection();
        connection.setConnectionId("connection-1");
        connection.setConnectionName("inventory");
        connection.setPuppetId("puppet-1");
        connection.setCreateUserId("creator");
        persistence.applyConnectionSpec(connection, DatabaseConnectionSpec.fromMap(Map.of(
                "dialect", "mysql", "connectionMode", "standard",
                "host", "db.internal", "port", 3306,
                "database", "inventory", "username", "app", "password", "existing-secret")));
        connection.setPassword(crypto.encrypt("existing-secret"));
        return connection;
    }

    private Map<String, Object> params(String connectionId, String password) {
        Map<String, Object> params = new LinkedHashMap<String, Object>();
        if (connectionId != null) params.put("connectionId", connectionId);
        params.put("connectionName", "inventory");
        params.put("connection", Map.of(
                "dialect", "mysql", "connectionMode", "standard",
                "host", "db.internal", "port", 3306,
                "database", "inventory", "username", "app", "password", password));
        return params;
    }

    private User user(String id) {
        User user = new User();
        user.setUserId(id);
        user.setPrivilege("normal");
        return user;
    }

    private DatabaseConnectionManagementService management(
            PuppetDatabaseConnectionService persistence) {
        return new DatabaseConnectionManagementService(
                new DatabaseConnectionProfileService(persistence, new SqlDialectRegistry()));
    }
}
