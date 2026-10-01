package org.leo.web.security;

import org.junit.jupiter.api.Test;
import org.leo.core.entity.AgentDatabaseConnection;
import org.leo.core.entity.User;
import org.leo.core.agent.database.DatabaseConnectionSpec;
import org.leo.service.AgentDatabaseConnectionService;
import org.leo.web.exception.ApiException;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DatabaseConnectionResolverTest {

    @Test
    void keepsInlineConnectionsIndependentFromPersistence() {
        AgentDatabaseConnectionService service = mock(AgentDatabaseConnectionService.class);
        DatabaseConnectionResolver resolver = new DatabaseConnectionResolver(service);
        Map<String, Object> supplied = new LinkedHashMap<String, Object>();
        supplied.put("dialect", "mysql");
        supplied.put("connectionMode", "standard");
        supplied.put("host", "db.internal");

        Map<String, Object> resolved = resolver.resolve(supplied, "agent-1", null);

        assertEquals(supplied, resolved);
        assertNotSame(supplied, resolved);
        verifyNoInteractions(service);
    }

    @Test
    void resolvesConnectionReferencesOnlyAfterAgentBindingChecks() {
        AgentDatabaseConnectionService service = mock(AgentDatabaseConnectionService.class);
        DatabaseConnectionResolver resolver = new DatabaseConnectionResolver(service);
        AgentDatabaseConnection saved = new AgentDatabaseConnection();
        saved.setConnectionId("connection-1");
        saved.setAgentId("agent-1");
        saved.setCreateUserId("owner");
        saved.setStatus(1);
        User owner = user("owner");
        DatabaseConnectionSpec spec = DatabaseConnectionSpec.fromMap(Map.of(
                "dialect", "mysql", "connectionMode", "standard",
                "host", "db.internal", "port", 3306,
                "database", "inventory", "username", "app", "password", "secret"));
        when(service.findById("connection-1")).thenReturn(saved);
        when(service.toActiveConnectionSpec(saved)).thenReturn(spec);

        Map<String, Object> resolved = resolver.resolve(
                Map.of("connectionId", " connection-1 "), "agent-1", owner);

        assertEquals("db.internal", resolved.get("host"));
        assertEquals("connection-1", resolver.reference(Map.of("connectionId", " connection-1 ")));
    }

    @Test
    void rejectsMissingProfilesCrossAgentUseAndAnonymousCredentialResolution() {
        AgentDatabaseConnectionService service = mock(AgentDatabaseConnectionService.class);
        DatabaseConnectionResolver resolver = new DatabaseConnectionResolver(service);
        AgentDatabaseConnection saved = new AgentDatabaseConnection();
        saved.setConnectionId("connection-1");
        saved.setAgentId("agent-1");
        saved.setCreateUserId("owner");
        when(service.findById("connection-1")).thenReturn(saved);

        ApiException anonymous = assertThrows(ApiException.class,
                () -> resolver.resolve(Map.of("connectionId", "connection-1"), "agent-1", null));
        ApiException wrongAgent = assertThrows(ApiException.class,
                () -> resolver.resolve(Map.of("connectionId", "connection-1"), "agent-2", user("owner")));
        when(service.findById("missing")).thenReturn(null);
        ApiException missing = assertThrows(ApiException.class,
                () -> resolver.resolve(Map.of("connectionId", "missing"), "agent-1", user("owner")));

        assertEquals(401, anonymous.getHttpStatus().value());
        assertEquals(403, wrongAgent.getHttpStatus().value());
        assertEquals(404, missing.getHttpStatus().value());
    }

    private User user(String id) {
        User user = new User();
        user.setUserId(id);
        user.setPrivilege("normal");
        user.setTeamId("team-a");
        return user;
    }
}
