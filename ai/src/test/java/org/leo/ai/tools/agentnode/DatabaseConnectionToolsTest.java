package org.leo.ai.tools.agentnode;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.leo.ai.agent.AiToolContext;
import org.leo.core.entity.Agent;
import org.leo.core.entity.User;
import org.leo.core.agent.AbstractAgentNode;
import org.leo.core.session.AgentNodeSession;
import org.leo.core.session.AgentNodeSessionContainer;
import org.leo.service.DatabaseConnectionProfileService;
import org.leo.service.audit.AgentAuditService;
import org.leo.service.sql.dialect.SqlDialectRegistry;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DatabaseConnectionToolsTest {

    private static final String SESSION_ID = "database-tools-session";

    @AfterEach
    void cleanup() {
        AiToolContext.clear();
        AgentNodeSessionContainer.removeSession(SESSION_ID);
    }

    @Test
    void createsProfilesForTheCurrentSessionUserAndAgent() {
        DatabaseConnectionProfileService profile =
                mock(DatabaseConnectionProfileService.class);
        AgentAuditService audit = mock(AgentAuditService.class);
        DatabaseConnectionTools tools = new DatabaseConnectionTools(
                profile, audit, new SqlDialectRegistry());
        registerSession();
        AiToolContext.setFromMemoryId(SESSION_ID + ":thread-1");
        Map<String, Object> config = Map.of(
                "connectionName", "inventory",
                "connection", Map.of(
                        "dialect", "mysql",
                        "connectionMode", "standard",
                        "host", "db.internal"));
        when(profile.create("user-1", "agent-1", config))
                .thenReturn(Map.of("connectionId", "connection-1"));

        Map<String, Object> result = tools.createDatabaseConnection(config);

        assertEquals("connection-1", result.get("connectionId"));
        verify(profile).create("user-1", "agent-1", config);
        verify(audit).logSuccess(eq(SESSION_ID), any(),
                eq("DATABASE_CONNECTION_CREATE"), eq("AI新增数据库配置"),
                eq("create"), any(), eq("AI新增数据库配置成功"));
    }

    @Test
    void deletesOnlyThroughTheCurrentAgentScope() {
        DatabaseConnectionProfileService profile =
                mock(DatabaseConnectionProfileService.class);
        DatabaseConnectionTools tools = new DatabaseConnectionTools(
                profile, mock(AgentAuditService.class), new SqlDialectRegistry());
        registerSession();
        AiToolContext.setFromMemoryId(SESSION_ID);

        Map<String, Object> result = tools.deleteDatabaseConnection("connection-1");

        assertEquals(true, result.get("deleted"));
        verify(profile).delete("user-1", "agent-1", "connection-1");
    }

    @Test
    void exposesTheRegisteredDialectCatalogToTheModel() {
        DatabaseConnectionTools tools = new DatabaseConnectionTools(
                mock(DatabaseConnectionProfileService.class),
                mock(AgentAuditService.class),
                new SqlDialectRegistry());

        List<Map<String, Object>> catalog = tools.getDatabaseDialectCatalog();
        Set<String> dialects = catalog.stream()
                .map(item -> String.valueOf(item.get("type")))
                .collect(Collectors.toSet());

        assertEquals(Set.of(
                "mysql", "postgresql", "sqlserver", "oracle", "dm", "kingbasees", "sqlite", "generic"),
                dialects);
        Map<String, Object> dm = catalog.stream()
                .filter(item -> "dm".equals(item.get("type")))
                .findFirst()
                .orElseThrow();
        assertEquals(Map.of("java", true, "php", false), dm.get("runtimeSupport"));
    }

    private void registerSession() {
        AbstractAgentNode node = mock(AbstractAgentNode.class);
        Agent agent = new Agent();
        agent.setAgentId("agent-1");
        User user = new User();
        user.setUserId("user-1");
        when(node.getAgent()).thenReturn(agent);
        when(node.getUser()).thenReturn(user);
        AgentNodeSession session = new AgentNodeSession();
        session.setSessionId(SESSION_ID);
        session.setAgentNode(node);
        session.setCreateByUser("user-1");
        AgentNodeSessionContainer.addSession(SESSION_ID, session);
    }
}
