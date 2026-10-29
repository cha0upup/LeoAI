package org.leo.web.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.LocalCacheScope;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.leo.core.entity.Agent;
import org.leo.dao.mapper.AgentDatabaseConnectionMapper;
import org.leo.dao.mapper.AgentMapper;
import org.leo.service.AgentDatabaseConnectionService;
import org.leo.service.AgentService;
import org.leo.service.DatabaseConnectionProfileService;
import org.leo.service.security.DatabaseCredentialCryptoService;
import org.leo.service.sql.dialect.SqlDialectRegistry;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Current DTO, SQLite and MyBatis contracts; connection history is owned by the server. */
class ConfigurationPersistenceTest {
    @TempDir Path temp;
    private SqlSession session;
    private AgentMapper mapper;
    private AgentService agents;
    private AgentDatabaseConnectionMapper connections;
    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void setUp() {
        SQLiteConfig sqlite = new SQLiteConfig();
        sqlite.enforceForeignKeys(true);
        SQLiteDataSource source = new SQLiteDataSource(sqlite);
        source.setUrl("jdbc:sqlite:" + temp.resolve("configuration.db"));
        new ResourceDatabasePopulator(new ClassPathResource("sql/schema.sql")).execute(source);
        Configuration config = new Configuration(new Environment("test", new JdbcTransactionFactory(), source));
        config.setMapUnderscoreToCamelCase(true);
        config.setLocalCacheScope(LocalCacheScope.STATEMENT);
        config.addMapper(AgentMapper.class);
        config.addMapper(AgentDatabaseConnectionMapper.class);
        session = new SqlSessionFactoryBuilder().build(config).openSession(true);
        mapper = session.getMapper(AgentMapper.class);
        agents = new AgentService(mapper);
        connections = session.getMapper(AgentDatabaseConnectionMapper.class);
    }

    @AfterEach
    void close() { if (session != null) session.close(); }

    @Test
    void newConfigurationsHaveNoLiveConnectionHistoryAndOnlyExposeCurrentFields() {
        Agent imported = agent("node");
        imported.setLastConnectedAt("2000-01-01 00:00:00");
        assertTrue(agents.insertAgent(imported));
        Agent stored = agents.findAgentById("node");
        assertNull(stored.getLastConnectedAt());
        var tree = json.valueToTree(stored);
        assertTrue(tree.has("lastConnectedAt"));
        assertFalse(tree.has("lastHeartbeat"));
        assertFalse(tree.has("heartbeatInterval"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void editsPreserveTheLatestServerConnectionTimestamp(boolean staleClient) {
        assertTrue(agents.insertAgent(agent("node")));
        Agent edit = staleClient ? agents.findAgentById("node") : agent("node");
        if (staleClient) edit.setLastConnectedAt("2000-01-01 00:00:00");
        assertTrue(mapper.updateLastConnectedAt("node", "2026-10-28 12:00:00", "2026-10-28 12:00:00"));
        edit.setRemark("edited");
        assertTrue(agents.updateAgentById(edit));
        Agent stored = agents.findAgentById("node");
        assertEquals("edited", stored.getRemark());
        assertEquals("2026-10-28 12:00:00", stored.getLastConnectedAt());
        assertTrue(agents.updateLastConnectedAt("node"));
        assertNotEquals("2026-10-28 12:00:00", agents.findAgentById("node").getLastConnectedAt());
    }

    @Test
    void profileServicePersistsTheEffectiveTimeoutWithoutUnusedPoolSettings() {
        assertTrue(agents.insertAgent(agent("node")));
        var persistence = new AgentDatabaseConnectionService(connections,
                new DatabaseCredentialCryptoService("profile-fixture-key", "unused"));
        var profiles = new DatabaseConnectionProfileService(persistence, new SqlDialectRegistry());
        Map<String, Object> created = profiles.create("owner", "node", Map.of(
                "connection", Map.of("dialect", "sqlite", "connectionMode", "standard", "file", "fixture.db"),
                "timeoutSeconds", 45));
        String id = String.valueOf(created.get("connectionId"));
        assertEquals(45, connections.selectById(id).getTimeoutSeconds());
        assertFalse(json.valueToTree(connections.selectById(id)).has("maxConnections"));
        profiles.update("owner", "node", id, Map.of("timeoutSeconds", 60));
        assertEquals(60, connections.selectById(id).getTimeoutSeconds());
        assertEquals(60, profiles.resolveActive("owner", "node", id).getTimeoutSeconds());
    }

    private Agent agent(String id) {
        Agent agent = new Agent();
        agent.setAgentId(id);
        agent.setAgentName("fixture");
        agent.setCreateByUserId("owner");
        agent.setParentAgentId("root");
        agent.setConnLink("http://127.0.0.1:1/fixture");
        agent.setReqDisguiseId("request");
        agent.setRespDisguiseId("response");
        agent.setPayloadKey("0123456789abcdef");
        return agent;
    }
}
