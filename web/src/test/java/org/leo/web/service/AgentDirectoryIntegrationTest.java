package org.leo.web.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.leo.core.entity.User;
import org.leo.dao.mapper.AgentMapper;
import org.leo.dao.mapper.ProjectMapper;
import org.leo.service.AgentService;
import org.leo.service.UrlProbeService;
import org.leo.service.project.ProjectService;
import org.leo.service.user.UserService;
import org.leo.web.controller.platform.agent.AgentManageController;
import org.leo.web.controller.platform.project.ProjectController;
import org.leo.web.dto.platform.agent.AgentDirectoryEntry;
import org.leo.web.dto.platform.project.ProjectDtos.ProjectChildrenRequest;
import org.leo.web.security.PermissionService;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.mock.web.MockHttpServletRequest;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 使用真实 SQLite/MyBatis 和目录接口验证子机标记，无需新增数据库列。 */
class AgentDirectoryIntegrationTest {
    @TempDir Path temp;
    private SqlSession session;
    private JdbcTemplate jdbc;
    private AgentDirectoryService directory;
    private AgentService agents;
    private PermissionService permissions;
    private User user;
    private MockHttpServletRequest request;
    private AgentManageController global;
    private ProjectController projects;

    @BeforeEach
    void setUp() {
        SQLiteConfig sqlite = new SQLiteConfig();
        sqlite.enforceForeignKeys(true);
        SQLiteDataSource source = new SQLiteDataSource(sqlite);
        source.setUrl("jdbc:sqlite:" + temp.resolve("directory.db"));
        new ResourceDatabasePopulator(new ClassPathResource("sql/schema.sql")).execute(source);
        jdbc = new JdbcTemplate(source);
        Configuration config = new Configuration(new Environment("test", new JdbcTransactionFactory(), source));
        config.setMapUnderscoreToCamelCase(true);
        config.addMapper(AgentMapper.class);
        config.addMapper(ProjectMapper.class);
        session = new SqlSessionFactoryBuilder().build(config).openSession(true);
        // Disable query caching so fresh-directory checks observe inserts/deletes from JdbcTemplate.
        config.setLocalCacheScope(org.apache.ibatis.session.LocalCacheScope.STATEMENT);
        agents = new AgentService(session.getMapper(AgentMapper.class));
        UserService users = mock(UserService.class);
        user = new User();
        user.setUserId("owner");
        user.setStatus(1);
        user.setPrivilege("normal");
        when(users.getUserById("owner")).thenReturn(user);
        permissions = new PermissionService(agents, users);
        directory = new AgentDirectoryService(agents);
        ProjectService projectService = new ProjectService(session.getMapper(ProjectMapper.class), agents);
        global = new AgentManageController(agents, users, mock(UrlProbeService.class), projectService, directory);
        projects = new ProjectController(projectService, permissions, directory);
        request = new MockHttpServletRequest();
        request.getSession(true).setAttribute("user", user);
        host("parent", "root", "owner");
        host("leaf", "root", "owner");
        host("child", "parent", "owner");
        host("grandchild", "child", "owner");
        host("hidden-child", "leaf", "other-owner");
        jdbc.update("INSERT INTO projects (project_id, project_name, owner_user_id, permission, status, create_time, update_time) "
                + "VALUES ('project', 'Project', 'owner', 'private', 'active', '2026-10-04', '2026-10-04')");
        jdbc.update("INSERT INTO project_agents (project_id, agent_id, added_by_user_id, create_time) "
                + "VALUES ('project', 'parent', 'owner', '2026-10-04')");
    }

    @AfterEach
    void tearDown() { if (session != null) session.close(); }

    @Test
    void marksOnlyVisibleBranchesInTheGlobalRootDirectory() {
        List<AgentDirectoryEntry> result = entries(global.getAgent(request));
        assertEquals(List.of("parent", "leaf"), ids(result));
        assertEquals(List.of(true, false), result.stream().map(AgentDirectoryEntry::hasChildren).toList());
    }

    @Test
    void globalDirectoryStillExcludesHiddenRootsAndNonRootEntries() {
        host("hidden-root", "root", "other-owner");
        host("orphan", "missing-parent", "owner");
        assertEquals(List.of("parent", "leaf"), ids(entries(global.getAgent(request))));
    }

    @Test
    void includesGrandchildMetadataInLazyChildResponses() {
        var body = new HashMap<String, Object>();
        body.put("parentAgentId", "parent");
        List<AgentDirectoryEntry> children = entries(global.getChildrenByParentAgentId(request, body));
        assertEquals(List.of("child"), ids(children));
        assertTrue(children.get(0).hasChildren());
        body.put("parentAgentId", "child");
        List<AgentDirectoryEntry> leaves = entries(global.getChildrenByParentAgentId(request, body));
        assertEquals(List.of("grandchild"), ids(leaves));
        assertFalse(leaves.get(0).hasChildren());
    }

    @Test
    void preservesProjectAndUnassignedWorkspaceBoundaries() {
        List<AgentDirectoryEntry> assigned = entries(projects.listRootAgents(request, "project"));
        assertEquals(List.of("parent"), ids(assigned));
        assertTrue(assigned.get(0).hasChildren());
        List<AgentDirectoryEntry> unassigned = entries(projects.listUnassignedAgents(request));
        assertEquals(List.of("leaf"), ids(unassigned));
        assertFalse(unassigned.get(0).hasChildren());
        assertEquals(List.of("child"), ids(entries(projects.listChildren(
                request, "project", new ProjectChildrenRequest("parent")))));
        assertTrue(entries(projects.listChildren(request, "project", new ProjectChildrenRequest("leaf"))).isEmpty());
    }

    @Test
    void doesNotRevealChildrenHiddenByTheCurrentPermissionRules() {
        List<AgentDirectoryEntry> result = directory.describe(agents.findAgentsByParentAgentId("root"),
                agent -> permissions.canAccessAgentChain(agent, user));
        assertFalse(result.stream().filter(row -> "leaf".equals(row.agent().getAgentId())).findFirst().orElseThrow().hasChildren());
        assertTrue(entries(projects.listChildren(request, "project", new ProjectChildrenRequest("child")))
                .stream().allMatch(row -> "grandchild".equals(row.agent().getAgentId())));
    }

    @Test
    void metadataRefreshesAfterTheLastVisibleChildIsRemoved() {
        jdbc.update("DELETE FROM agents WHERE agent_id IN ('child', 'grandchild')");
        List<AgentDirectoryEntry> result = entries(global.getAgent(request));
        assertTrue(result.stream().noneMatch(AgentDirectoryEntry::hasChildren));
        host("new-child", "leaf", "owner");
        assertTrue(entries(global.getAgent(request)).stream()
                .filter(row -> "leaf".equals(row.agent().getAgentId())).findFirst().orElseThrow().hasChildren());
    }

    @Test
    void serializesMetadataAlongsideExistingAgentFieldsWithoutAnExtraWrapper() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        var json = mapper.readTree(mapper.writeValueAsBytes(entries(global.getAgent(request))));
        assertEquals("parent", json.get(0).get("agentId").asText());
        assertEquals("parent", json.get(0).get("agentName").asText());
        assertTrue(json.get(0).get("hasChildren").isBoolean());
        assertTrue(json.get(0).get("hasChildren").asBoolean());
        assertFalse(json.get(0).has("agent"));
    }

    @Test
    void emptyOrEntirelyHiddenDirectoriesSkipTheChildQuery() {
        AgentService service = mock(AgentService.class);
        AgentDirectoryService view = new AgentDirectoryService(service);
        assertEquals(List.of(), view.describe(null, agent -> true));
        assertEquals(List.of(), view.describe(List.of(), agent -> true));
        assertEquals(List.of(), view.describe(agents.findAgentsByParentAgentId("root"), agent -> false));
        verifyNoInteractions(service);
    }

    @SuppressWarnings("unchecked")
    private static List<AgentDirectoryEntry> entries(Map<String, Object> response) {
        assertEquals(200, response.get("code"));
        return (List<AgentDirectoryEntry>) response.get("data");
    }

    private static List<String> ids(List<AgentDirectoryEntry> rows) {
        return rows.stream().map(row -> row.agent().getAgentId()).toList();
    }

    private void host(String id, String parent, String owner) {
        jdbc.update("INSERT INTO agents (agent_id, agent_name, parent_agent_id, create_by_user_id, permission, conn_link, "
                + "req_disguise_id, resp_disguise_id, create_time, update_time) "
                + "VALUES (?, ?, ?, ?, 'private', 'local-fixture', 'request', 'response', '2026-10-04', '2026-10-04')",
                id, id, parent, owner);
    }
}
