package org.leo.ai.tools.agentnode;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.leo.ai.agent.AiToolContext;
import org.leo.core.entity.Agent;
import org.leo.core.entity.User;
import org.leo.core.agent.AbstractAgentNode;
import org.leo.core.agent.capability.SqlCapable;
import org.leo.core.agent.database.DatabaseConnectionSpec;
import org.leo.core.runtime.CapabilitySet;
import org.leo.core.session.AgentNodeSession;
import org.leo.core.session.AgentNodeSessionContainer;
import org.leo.service.sql.DatabaseConnectionProfileService;
import org.leo.service.audit.AgentAuditService;
import org.mockito.ArgumentCaptor;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;

class SqlToolsSavedConnectionTest {

    private static final String SESSION_ID = "sql-saved-connection-session";

    @AfterEach
    void cleanup() {
        AiToolContext.clear();
        AgentNodeSessionContainer.removeSession(SESSION_ID);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void commonExecutionKeepsQueryAndWriteAuditIdentities(boolean query) throws Exception {
        Fixture fixture = fixture();
        String sql = query ? "/* evidence */ SELECT ';' AS marker; -- trailing comment"
                : "UPDATE inventory SET count = 1";
        Map<String, Object> result = Map.of("code", 200);
        when(fixture.sqlNode().executeSql(fixture.spec(), sql)).thenReturn(result);

        assertSame(result, query
                ? fixture.tools().querySql(Map.of("connectionId", "connection-1"), sql)
                : fixture.tools().execSql(Map.of("connectionId", "connection-1"), sql));

        String name = query ? "AI查询SQL" : "AI执行SQL";
        ArgumentCaptor<Map<String, Object>> params = ArgumentCaptor.captor();
        verify(fixture.audit()).logSuccess(eq(SESSION_ID), same(fixture.node()),
                eq(query ? "SQL_QUERY" : "SQL_EXEC"), eq(name),
                eq("mysql://db.internal/inventory | " + sql), params.capture(), eq(name + "成功"));
        assertEquals(SESSION_ID, params.getValue().get("sessionId"));
        assertEquals("connection-1", params.getValue().get("connectionId"));
        assertEquals(sql, params.getValue().get("sql"));
        assertFalse(((Map<?, ?>) params.getValue().get("connection")).containsKey("password"));
        verify(fixture.profiles()).resolveActive("user-1", "agent-1", "connection-1");
        verify(fixture.sqlNode()).executeSql(fixture.spec(), sql);
        verifyNoMoreInteractions(fixture.audit());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void commonExecutionLogsFailureOnceAndPropagatesTheOriginalError(boolean query) throws Exception {
        Fixture fixture = fixture();
        String sql = query ? "SELECT 1" : "UPDATE inventory SET count = 1";
        Exception failure = new IllegalStateException("fixture failure");
        when(fixture.sqlNode().executeSql(fixture.spec(), sql)).thenThrow(failure);

        assertSame(failure, assertThrows(Exception.class, () -> {
            if (query) fixture.tools().querySql(Map.of("connectionId", "connection-1"), sql);
            else fixture.tools().execSql(Map.of("connectionId", "connection-1"), sql);
        }));
        verify(fixture.audit()).logFailure(eq(SESSION_ID), same(fixture.node()),
                eq(query ? "SQL_QUERY" : "SQL_EXEC"), eq(query ? "AI查询SQL" : "AI执行SQL"),
                anyString(), anyMap(), eq("fixture failure"));
        verifyNoMoreInteractions(fixture.audit());
    }

    @Test
    void rejectsWritableCteBeforeResolvingOrExecutingAConnection() throws Exception {
        Fixture fixture = fixture();
        String sql = "WITH deleted AS (DELETE FROM users RETURNING *) SELECT * FROM deleted";

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> fixture.tools().querySql(Map.of("connectionId", "connection-1"), sql));

        assertTrue(error.getMessage().contains("WITH/CTE"));
        verify(fixture.profiles(), never()).resolveActive(
                "user-1", "agent-1", "connection-1");
        verify(fixture.sqlNode(), never()).executeSql(fixture.spec(), sql);
    }

    @Test
    void rejectsMultipleStatementsAndUnprovenReadOnlyKeywords() {
        Fixture fixture = fixture();

        IllegalArgumentException multiple = assertThrows(IllegalArgumentException.class,
                () -> fixture.tools().querySql(Map.of(), "SELECT 1; DELETE FROM users"));
        IllegalArgumentException unknown = assertThrows(IllegalArgumentException.class,
                () -> fixture.tools().querySql(Map.of(), "VACUUM"));
        IllegalArgumentException explain = assertThrows(IllegalArgumentException.class,
                () -> fixture.tools().querySql(Map.of(), "EXPLAIN ANALYZE DELETE FROM users"));
        IllegalArgumentException pragma = assertThrows(IllegalArgumentException.class,
                () -> fixture.tools().querySql(Map.of(), "PRAGMA journal_mode=WAL"));

        assertTrue(multiple.getMessage().contains("只允许一条 SQL"));
        assertTrue(unknown.getMessage().contains("VACUUM"));
        assertTrue(explain.getMessage().contains("EXPLAIN"));
        assertTrue(pragma.getMessage().contains("PRAGMA"));
    }

    @Test
    void rejectsUnterminatedQuotedContent() {
        Fixture fixture = fixture();

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> fixture.tools().querySql(Map.of(), "SELECT 'unterminated"));

        assertTrue(error.getMessage().contains("未闭合"));
    }

    private Fixture fixture() {
        DatabaseConnectionProfileService profiles =
                mock(DatabaseConnectionProfileService.class);
        AgentAuditService audit = mock(AgentAuditService.class);
        SqlTools tools = new SqlTools(audit, profiles);
        AbstractAgentNode node = mock(AbstractAgentNode.class,
                withSettings().extraInterfaces(SqlCapable.class));
        SqlCapable sqlNode = (SqlCapable) node;
        Agent agent = new Agent();
        agent.setAgentId("agent-1");
        User user = new User();
        user.setUserId("user-1");
        when(node.getAgent()).thenReturn(agent);
        when(node.getUser()).thenReturn(user);
        when(node.getCapabilitySet()).thenReturn(CapabilitySet.empty());
        DatabaseConnectionSpec spec = DatabaseConnectionSpec.fromMap(Map.of(
                "dialect", "mysql",
                "connectionMode", "standard",
                "host", "db.internal",
                "port", 3306,
                "database", "inventory",
                "username", "app",
                "password", "secret"));
        when(profiles.resolveActive("user-1", "agent-1", "connection-1"))
                .thenReturn(spec);
        AgentNodeSession session = new AgentNodeSession();
        session.setSessionId(SESSION_ID);
        session.setAgentNode(node);
        session.setCreateByUser("user-1");
        AgentNodeSessionContainer.addSession(SESSION_ID, session);
        AiToolContext.setFromMemoryId(SESSION_ID);
        return new Fixture(tools, profiles, sqlNode, spec, audit, node);
    }

    private record Fixture(SqlTools tools,
                           DatabaseConnectionProfileService profiles,
                           SqlCapable sqlNode,
                           DatabaseConnectionSpec spec,
                           AgentAuditService audit,
                           AbstractAgentNode node) {}
}
