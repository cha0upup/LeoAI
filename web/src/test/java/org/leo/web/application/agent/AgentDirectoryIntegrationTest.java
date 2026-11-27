package org.leo.web.application.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.leo.core.entity.User;
import org.leo.dao.mapper.AgentMapper;
import org.leo.dao.mapper.ProjectMapper;
import org.leo.service.agentnode.AgentService;
import org.leo.service.discovery.UrlProbeService;
import org.leo.service.project.ProjectService;
import org.leo.service.team.TeamService;
import org.leo.service.user.UserService;
import org.leo.web.application.project.ProjectReadService;
import org.leo.web.controller.platform.agent.AgentManageController;
import org.leo.web.controller.platform.project.ProjectController;
import org.leo.web.dto.platform.agent.AgentDirectoryEntry;
import org.leo.web.dto.platform.project.ProjectDtos.ProjectChildrenRequest;
import org.leo.web.security.PermissionService;
import org.leo.web.testing.SqliteTestDatabase;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
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
    private AnnotationConfigApplicationContext context;
    private JdbcTemplate jdbc;
    private ProjectMapper projectMapper;
    private AgentDirectoryService directory;
    private AgentService agents;
    private PermissionService permissions;
    private User user;
    private MockHttpServletRequest request;
    private AgentManageController global;
    private ProjectController projects;

    @BeforeEach
    void setUp() {
        SQLiteDataSource source = SqliteTestDatabase.create(temp.resolve("directory.db"));
        jdbc = new JdbcTemplate(source);
        Configuration config = SqliteTestDatabase.myBatisConfiguration(source);
        config.setEnvironment(new Environment("directory-test", new SpringManagedTransactionFactory(), source));
        config.addMapper(AgentMapper.class);
        config.addMapper(ProjectMapper.class);
        var session = new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(config));
        projectMapper = session.getMapper(ProjectMapper.class);
        agents = spy(new AgentService(session.getMapper(AgentMapper.class)));
        UserService users = mock(UserService.class);
        user = new User();
        user.setUserId("owner");
        user.setStatus(1);
        user.setPrivilege("normal");
        when(users.getUserById("owner")).thenReturn(user);
        permissions = new PermissionService(agents, users);
        directory = new AgentDirectoryService(agents);
        ProjectService projectService = new ProjectService(projectMapper, agents, users, mock(TeamService.class));
        context = new AnnotationConfigApplicationContext();
        context.register(DirectoryTransactions.class);
        context.registerBean(PlatformTransactionManager.class, () -> new DataSourceTransactionManager(source));
        context.registerBean(ProjectReadService.class, () -> new ProjectReadService(projectService, permissions, directory));
        context.refresh();
        global = new AgentManageController(agents, users, mock(UrlProbeService.class), projectService, directory);
        projects = new ProjectController(projectService, permissions, mock(SessionLifecycleManager.class),
                context.getBean(ProjectReadService.class));
        request = new MockHttpServletRequest();
        request.getSession(true).setAttribute("user", user);
        host("parent", "root", "owner");
        host("leaf", "root", "owner");
        host("child", "parent", "owner");
        host("grandchild", "child", "owner");
        host("hidden-child", "leaf", "other-owner");
        jdbc.update("""
                INSERT INTO users (user_id, user_name, password, create_time, update_time)
                VALUES ('owner', 'owner', 'fixture-hash', '2026-10-04', '2026-10-04')
                """);
        jdbc.update("INSERT INTO projects (project_id, project_name, owner_user_id, permission, status, create_time, update_time) "
                + "VALUES ('project', 'Project', 'owner', 'private', 'active', '2026-10-04', '2026-10-04')");
        jdbc.update("INSERT INTO project_agents (project_id, agent_id, added_by_user_id, create_time) "
                + "VALUES ('project', 'parent', 'owner', '2026-10-04')");
    }

    @AfterEach
    void tearDown() { if (context != null) context.close(); }

    @Test
    void queriesOnlyGlobalRootsAndMarksOnlyVisibleBranches() {
        List<AgentDirectoryEntry> result = entries(global.getAgent(request));
        assertEquals(List.of("parent", "leaf"), ids(result));
        assertEquals(List.of(true, false), result.stream().map(AgentDirectoryEntry::hasChildren).toList());
        verify(agents).findAgentsByParentAgentId("root");
        verify(agents, never()).getAllAgents();
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

    @ParameterizedTest
    @ValueSource(strings = {"project", " project "})
    void preservesProjectAndUnassignedWorkspaceBoundaries(String projectId) {
        assertTrue(projectMapper.existsAgentRelation("project", "parent"));
        assertFalse(projectMapper.existsAgentRelation("project", "child"));
        assertFalse(projectMapper.existsAgentRelation("other-project", "parent"));
        jdbc.update("""
                INSERT INTO project_agents (project_id, agent_id, added_by_user_id, create_time)
                VALUES ('project', 'child', 'owner', '2026-10-04')
                """);
        List<AgentDirectoryEntry> assigned = entries(projects.listRootAgents(request, projectId));
        assertEquals(List.of("parent"), ids(assigned));
        assertTrue(assigned.get(0).hasChildren());
        assertEquals(ids(assigned), ids(entries(projects.listChildren(
                request, projectId, new ProjectChildrenRequest("root")))));
        List<AgentDirectoryEntry> unassigned = entries(projects.listUnassignedAgents(request));
        assertEquals(List.of("leaf"), ids(unassigned));
        assertFalse(unassigned.get(0).hasChildren());
        assertEquals(List.of("child"), ids(entries(projects.listChildren(
                request, projectId, new ProjectChildrenRequest("parent")))));
        assertTrue(entries(projects.listChildren(request, projectId, new ProjectChildrenRequest("leaf"))).isEmpty());
    }

    @Test
    void doesNotRevealChildrenHiddenByTheCurrentPermissionRules() {
        var result = directory.describe(agents.findAgentsByParentAgentId("root"),
                agent -> permissions.canAccessAgentChain(agent, user));
        assertFalse(result.stream().filter(row -> "leaf".equals(row.agent().getAgentId())).findFirst().orElseThrow().hasChildren());
        assertEquals(List.of("grandchild"),
                ids(entries(projects.listChildren(request, "project", new ProjectChildrenRequest("child")))));
    }

    @Test
    void projectRowsAndChildMarkersUseTheSameSnapshot() {
        assertEquals("wal", jdbc.queryForObject("PRAGMA journal_mode=WAL", String.class));
        var source = jdbc.getDataSource();
        doAnswer(call -> {
            // 在根节点已读出、子节点尚未查询时，通过另一个连接提交变更。
            try (var writer = source.getConnection(); var statement = writer.createStatement()) {
                statement.executeUpdate("UPDATE agents SET create_by_user_id='other-owner' WHERE agent_id='child'");
            }
            return call.callRealMethod();
        }).doCallRealMethod().when(agents).findAgentsByParentAgentIds(List.of("parent"));

        List<AgentDirectoryEntry> first = entries(projects.listRootAgents(request, "project"));
        assertEquals(List.of("parent"), ids(first));
        assertTrue(first.get(0).hasChildren());

        List<AgentDirectoryEntry> next = entries(projects.listRootAgents(request, "project"));
        assertEquals(List.of("parent"), ids(next));
        assertFalse(next.get(0).hasChildren());
        assertTrue(entries(projects.listChildren(request, "project", new ProjectChildrenRequest("parent"))).isEmpty());
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

    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    static class DirectoryTransactions {
    }
}
