package org.leo.web.controller.platform.project;

import jakarta.servlet.http.HttpServletRequest;
import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.leo.core.entity.Agent;
import org.leo.core.entity.Project;
import org.leo.core.entity.User;
import org.leo.core.session.AgentNodeSession;
import org.leo.core.session.AgentNodeSessionContainer;
import org.leo.dao.mapper.ProjectMapper;
import org.leo.dao.mapper.TeamMapper;
import org.leo.dao.mapper.UserMapper;
import org.leo.service.agentnode.AgentService;
import org.leo.service.discovery.NetworkProbeWorkflowService;
import org.leo.service.project.ProjectService;
import org.leo.service.team.TeamService;
import org.leo.service.user.UserService;
import org.leo.web.application.agent.AgentDirectoryService;
import org.leo.web.application.agent.SessionLifecycleManager;
import org.leo.web.dto.platform.project.ProjectDtos.AgentMembershipsRequest;
import org.leo.web.dto.platform.project.ProjectDtos.ProjectAgentsRequest;
import org.leo.web.dto.platform.project.ProjectDtos.ProjectIdRequest;
import org.leo.web.dto.platform.project.ProjectDtos.ProjectMembershipSummary;
import org.leo.web.dto.platform.project.ProjectDtos.ProjectOwnerTransferRequest;
import org.leo.web.dto.platform.project.ProjectDtos.ProjectSummary;
import org.leo.web.dto.platform.project.ProjectDtos.RelationMutationResponse;
import org.leo.web.interceptor.LoginInterceptor;
import org.leo.web.exception.ApiException;
import org.leo.web.security.PermissionService;
import org.leo.web.testing.SqliteTestDatabase;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 使用实际 SQLite schema 和 MyBatis 映射验证项目读写契约及查询次数。 */
class ProjectListIntegrationTest {

    @TempDir Path temp;
    private JdbcTemplate jdbc;
    private SqlSession sqlSession;
    private ProjectController controller;
    private ProjectService projectService;
    private PermissionService permissions;
    private AgentService agents;
    private UserService users;
    private final QueryCounter queries = new QueryCounter();

    @BeforeEach
    void setUp() {
        SQLiteDataSource dataSource = SqliteTestDatabase.create(temp.resolve("projects.db"));
        jdbc = new JdbcTemplate(dataSource);
        new TransactionTemplate(new DataSourceTransactionManager(dataSource)).executeWithoutResult(status -> {
            jdbc.update("""
                    INSERT INTO users (user_id, user_name, password, team_id, create_time, update_time)
                    VALUES ('lead-1', 'lead-1', 'fixture-hash', 'team-1', '2026-10-01', '2026-10-01'),
                           ('lead-2', 'lead-2', 'fixture-hash', 'team-2', '2026-10-01', '2026-10-01'),
                           ('user-1', 'user-1', 'fixture-hash', 'team-1', '2026-10-01', '2026-10-01'),
                           ('user-2', 'user-2', 'fixture-hash', 'team-2', '2026-10-01', '2026-10-01')
                    """);
            jdbc.update("""
                    INSERT INTO teams (team_id, team_name, leader_id, create_time, update_time)
                    VALUES ('team-1', 'First', 'lead-1', '2026-10-01', '2026-10-01'),
                           ('team-2', 'Second', 'lead-2', '2026-10-01', '2026-10-01')
                    """);
        });
        Configuration configuration = SqliteTestDatabase.myBatisConfiguration(dataSource);
        configuration.addMapper(ProjectMapper.class);
        configuration.addMapper(TeamMapper.class);
        configuration.addMapper(UserMapper.class);
        configuration.addInterceptor(queries);
        sqlSession = new SqlSessionFactoryBuilder().build(configuration).openSession(true);
        agents = mock(AgentService.class);
        users = mock(UserService.class);
        permissions = new PermissionService(agents, users);
        TeamService teams = new TeamService(sqlSession.getMapper(TeamMapper.class), users);
        projectService = new ProjectService(sqlSession.getMapper(ProjectMapper.class), agents, users, teams);
        controller = new ProjectController(
                projectService, permissions,
                new AgentDirectoryService(agents), new SessionLifecycleManager(mock(NetworkProbeWorkflowService.class)));
    }

    @AfterEach
    void tearDown() {
        if (sqlSession != null) sqlSession.close();
        AgentNodeSessionContainer.clearAllSessions();
    }

    @ParameterizedTest
    @CsvSource({
            "false, ' public ', active, 403", "true, ' public ', active, 403",
            "false, private, invalid, 400", "true, private, invalid, 400"
    })
    void rejectsInvalidProjectWritesWithoutChangingStoredData(boolean update, String permission, String status, int code) {
        if (update) project("owned", "user-1", "private", "active", "Original");
        Project input = new Project();
        input.setProjectId("owned");
        input.setProjectName("Changed");
        input.setPermission(permission);
        input.setStatus(status);
        MockHttpServletRequest request = requestAs("normal");

        ApiException error = assertThrows(ApiException.class, () -> {
            if (update) controller.updateProject(request, input);
            else controller.createProject(request, input);
        });

        assertEquals(code, error.getCode());
        assertEquals(code == 403 ? "只有管理员可以设置公开项目" : "项目状态值无效", error.getMessage());
        assertEquals(update ? 1 : 0, jdbc.queryForObject("SELECT COUNT(*) FROM projects", Integer.class));
        if (update) {
            assertEquals(Map.of("permission", "private", "project_name", "Original", "status", "active"),
                    jdbc.queryForMap("SELECT permission, project_name, status FROM projects WHERE project_id='owned'"));
        }
    }

    @ParameterizedTest
    @CsvSource(value = {
            "false, admin, ' public ', public",
            "true, admin, ' public ', public",
            "false, normal, ' team ', team",
            "true, normal, ' team ', team",
            "false, normal, null, private",
            "true, normal, null, private"
    }, nullValues = "null")
    void authorizedWritesPersistNormalizedPermission(boolean update, String privilege,
                                                     String permission, String expectedPermission) {
        if (update) project("owned", "user-1", "private", "active", "Original");
        Project input = new Project();
        input.setProjectId("owned");
        input.setProjectName(" Changed ");
        input.setPermission(permission);
        input.setTeamId("team-2");
        MockHttpServletRequest request = requestAs(privilege);

        var response = update ? controller.updateProject(request, input) : controller.createProject(request, input);

        assertEquals(200, response.get("code"));
        Project saved = (Project) response.get("data");
        assertEquals(expectedPermission, saved.getPermission());
        assertEquals("team".equals(expectedPermission) ? "team-1" : null, saved.getTeamId());
        assertEquals("Changed", saved.getProjectName());
        assertEquals(expectedPermission, jdbc.queryForObject(
                "SELECT permission FROM projects WHERE project_id=?", String.class, saved.getProjectId()));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM projects", Integer.class));
    }

    @Test
    void administratorSharesAPrivateProjectWithItsOwnersTeam() throws Exception {
        project("owned", "user-1", "private", "active", "Original");
        requestAs("normal");
        Project input = new Project();
        input.setProjectId("owned");
        input.setProjectName("Edited");
        input.setPermission("team");
        input.setTeamId("team-2");

        var admin = requestAs("admin", "manager", "team-2");
        Project saved = (Project) controller.updateProject(admin, input).get("data");

        assertEquals("team-1", saved.getTeamId());
        assertEquals(List.of("owned"), listAs(requestAs("normal", "colleague", "team-1")).stream()
                .map(item -> item.project().getProjectId()).toList());
        assertTrue(listAs(requestAs("normal", "outsider", "team-2")).isEmpty());
    }

    @Test
    void rejectsTeamSharingWhenTheOwnersTeamDoesNotExist() {
        Project input = new Project();
        input.setProjectName("Shared");
        input.setPermission("team");
        var request = requestAs("normal", "user-1", "missing-team");

        ApiException error = assertThrows(ApiException.class, () -> controller.createProject(request, input));

        assertEquals(400, error.getCode());
        assertEquals("项目所属团队不存在，请检查团队归属或调整共享范围", error.getMessage());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM projects", Integer.class));
    }

    @Test
    void archivePreservesProjectDetailsUpdatedAfterTheOriginalRead() {
        project("owned", "user-1", "private", "active", "Original");
        Project previous = projectService.findById("owned");
        jdbc.update("""
                UPDATE projects SET project_name='Renamed', project_code='current', description='Updated',
                    permission='team', team_id='team-1' WHERE project_id='owned'
                """);

        int statementsBeforeArchive = queries.sql.size();
        Project archived = projectService.archive(previous);

        assertEquals(1, queries.sql.size() - statementsBeforeArchive);
        assertEquals("archived", archived.getStatus());
        assertEquals("Renamed", archived.getProjectName());
        assertEquals("current", archived.getProjectCode());
        assertEquals("Updated", archived.getDescription());
        assertEquals("team", archived.getPermission());
        assertEquals("team-1", archived.getTeamId());
        assertEquals(Map.of("project_name", "Renamed", "project_code", "current", "description", "Updated",
                        "permission", "team", "team_id", "team-1", "status", "archived", "update_time", archived.getUpdateTime()),
                jdbc.queryForMap("""
                        SELECT project_name, project_code, description, permission, team_id, status, update_time
                        FROM projects WHERE project_id='owned'
                        """));
    }

    @Test
    void administratorTransfersOwnershipWithoutChangingTheSharingTeam() {
        project("owned", "user-1", "team", "archived", "Original");
        ProjectMapper projectMapper = sqlSession.getMapper(ProjectMapper.class);
        Project beforeTransfer = projectMapper.findProjectById("owned");
        UserService storedUsers = new UserService(sqlSession.getMapper(UserMapper.class));
        User previousOwner = storedUsers.getUserById("user-1");
        assertNotNull(previousOwner);
        assertFalse(storedUsers.deleteUser(previousOwner));

        var transfer = new ProjectOwnerTransferRequest("owned", "user-2");
        assertEquals(403, assertThrows(ApiException.class,
                () -> controller.transferProject(requestAs("normal"), transfer)).getCode());
        var admin = requestAs("admin", "manager", "team-2");
        jdbc.update("UPDATE users SET status=0 WHERE user_id='user-2'");
        assertEquals(409, assertThrows(ApiException.class,
                () -> controller.transferProject(admin, transfer)).getCode());
        jdbc.update("UPDATE users SET status=1 WHERE user_id='user-2'");

        assertEquals(200, controller.transferProject(admin, transfer).get("code"));
        assertEquals(Map.of("owner_user_id", "user-2", "team_id", "team-1", "status", "archived"),
                jdbc.queryForMap("SELECT owner_user_id, team_id, status FROM projects WHERE project_id='owned'"));
        assertFalse(projectMapper.updateProject(beforeTransfer));
        assertThrows(IllegalStateException.class, () -> projectService.archive(beforeTransfer));
        assertEquals(0, projectMapper.deleteProject("owned", beforeTransfer.getOwnerUserId()));
        assertTrue(storedUsers.deleteUser(previousOwner));
        assertFalse(storedUsers.deleteUser(storedUsers.getUserById("user-2")));
    }

    @Test
    void schemaRejectsInvalidOwnersAndSharingTeams() {
        assertThrows(DataAccessException.class, () -> project("orphan", "missing", "private", "active", "Orphan"));
        project("shared", "user-1", "team", "active", "Shared");
        assertThrows(DataAccessException.class, () -> jdbc.update(
                "UPDATE projects SET team_id='missing' WHERE project_id='shared'"));
        assertThrows(DataAccessException.class, () -> jdbc.update(
                "UPDATE projects SET team_id=NULL WHERE project_id='shared'"));
        assertThrows(DataAccessException.class, () -> jdbc.update(
                "UPDATE projects SET permission='private' WHERE project_id='shared'"));
        assertThrows(DataAccessException.class, () -> jdbc.update("DELETE FROM users WHERE user_id='user-1'"));
    }

    @Test
    void preservesVisibilityHostTotalsOrderingAndOwnerOnlySessionsWithTwoQueries() throws Exception {
        project("owned", "user-1", "private", "active", "zebra");
        project("team", "user-2", "team", "active", "Alpha");
        project("hidden", "user-2", "private", "active", "Hidden");
        project("public", "user-2", "public", "archived", "Public");
        host("shared");
        host("second");
        attach("owned", "shared");
        attach("owned", "second");
        attach("team", "shared");
        attach("hidden", "shared");
        session("own", "owned", "user-1");
        session("other", "owned", "user-2");
        session("team-own", "team", "user-1");
        session("team-other", "team", "user-2");
        session("unassigned", null, "user-1");
        session("hidden-own", "hidden", "user-1");

        List<ProjectSummary> result = listAs("normal");

        assertEquals(List.of("team", "owned", "public"),
                result.stream().map(item -> item.project().getProjectId()).toList());
        assertEquals(List.of(1, 2, 0), result.stream().map(ProjectSummary::hostCount).toList());
        assertEquals(List.of(1, 1, 0), result.stream().map(ProjectSummary::activeSessionCount).toList());
        assertEquals(List.of(false, true, false), result.stream().map(ProjectSummary::manageable).toList());
        assertEquals(List.of(true, true, false), result.stream().map(ProjectSummary::contentEditable).toList());
        assertTwoProjectQueries();
        verify(users).getUserById("user-1");
    }

    @ParameterizedTest
    @CsvSource(value = {
            "user-1, team-1, false, own/shared/public",
            "user-1, null, false, own/public",
            "admin, null, true, own/shared/other-team/hidden/public"
    }, nullValues = "null")
    void sqliteReturnsOnlyProjectsInTheCallersVisibilityScope(String userId, String teamId, boolean admin,
                                                              String expectedIds) {
        project("own", "user-1", "private", "active", "A own");
        project("shared", "user-2", "team", "active", "B shared");
        project("other-team", "user-2", "team", "active", "C other team");
        jdbc.update("UPDATE projects SET team_id='team-2' WHERE project_id='other-team'");
        project("hidden", "user-2", "private", "active", "D hidden");
        project("public", "user-2", "public", "archived", "Public");
        host("selected");
        host("unselected");
        for (String id : List.of("own", "shared", "other-team", "hidden", "public")) attach(id, "selected");
        attach("public", "unselected");
        ProjectMapper mapper = sqlSession.getMapper(ProjectMapper.class);

        List<String> expected = List.of(expectedIds.split("/"));
        assertEquals(expected, mapper.findVisibleProjectCandidates(userId, teamId, admin).stream()
                .map(Project::getProjectId).toList());
        assertEquals(expected, mapper.findVisibleMembershipsByEntryAgentIds("[\"selected\"]", userId, teamId, admin).stream()
                .map(ProjectMapper.MembershipRow::projectId).toList());
    }

    @Test
    void countsOnlyRequestedProjectsEvenWhenOtherProjectsHaveHosts() {
        project("visible", "user-1", "private", "active", "Visible");
        project("hidden", "user-2", "private", "active", "Hidden");
        host("shared");
        attach("visible", "shared");
        attach("hidden", "shared");

        assertEquals(Map.of("visible", 1), projectService.countAgentsByProject(List.of("visible")));
        assertEquals(1, queries.sql.size());
    }

    @Test
    void adminCountsEveryAssignedSessionAndSeesPrivateProjects() throws Exception {
        project("hidden", "user-2", "private", "active", "Hidden");
        session("first", "hidden", "user-2");
        session("second", "hidden", "user-3");
        session("no-owner", "hidden", null);
        session("unassigned", null, "user-2");

        List<ProjectSummary> result = listAs("admin");

        assertEquals(1, result.size());
        assertEquals(0, result.get(0).hostCount());
        assertEquals(3, result.get(0).activeSessionCount());
        assertTrue(result.get(0).manageable());
        assertTrue(result.get(0).contentEditable());
        assertTwoProjectQueries();
    }

    @Test
    void deletesProjectWithoutReservedSessionIndexAndRetainsSessionsAndHosts() {
        project("removed", "user-1", "private", "active", "Removed");
        project("kept", "user-1", "private", "active", "Kept");
        host("retained-host");
        attach("removed", "retained-host");
        attach("kept", "retained-host");
        session("retained-session", "removed", "user-1");
        session("other-session", "kept", "user-1");
        MockHttpServletRequest request = requestAs("normal");
        request.setMethod("POST");
        request.setRequestURI("/platform/projects/delete");

        var response = controller.deleteProject(request, new ProjectIdRequest("removed"));

        assertEquals(200, response.get("code"));
        assertEquals(Map.of("projectId", "removed", "detachedHostCount", 1, "retainedSessionCount", 1),
                response.get("data"));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='agent_sessions'", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM projects WHERE project_id='removed'", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM project_agents WHERE project_id='removed'", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM agents WHERE agent_id='retained-host'", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM project_agents WHERE project_id='kept'", Integer.class));
        assertNotNull(AgentNodeSessionContainer.getSession("retained-session"));
        assertNull(AgentNodeSessionContainer.getSession("retained-session").getProjectId());
        assertEquals("kept", AgentNodeSessionContainer.getSession("other-session").getProjectId());
    }

    @Test
    void attachesHostsInOneWritePreservingOmittedMetadataAndCountingOnlyChanges() {
        project("owned", "user-1", "private", "active", "Owned");
        host("existing");
        host("new");
        attach("owned", "existing");
        jdbc.update("UPDATE project_agents SET alias='entry', tags='blue', sort_order=7 "
                + "WHERE project_id='owned' AND agent_id='existing'");
        for (String id : List.of("existing", "new")) {
            Agent host = new Agent();
            host.setAgentId(id);
            host.setCreateByUserId("user-1");
            host.setParentAgentId("root");
            when(agents.findAgentById(id)).thenReturn(host);
        }
        MockHttpServletRequest request = requestAs("normal");
        var selection = new ProjectAgentsRequest(" owned ", List.of("existing", "new"), null, null);
        assertEquals(new RelationMutationResponse("owned", 1), controller.attachAgents(request, selection).get("data"));
        assertEquals(1, queries.sql.stream().filter(sql -> sql.startsWith("INSERT INTO project_agents")).count());
        assertEquals(new RelationMutationResponse("owned", 0), controller.attachAgents(request, selection).get("data"));
        var mark = new ProjectAgentsRequest("owned", List.of("existing", "new"), " updated ", null);
        assertEquals(new RelationMutationResponse("owned", 2), controller.attachAgents(request, mark).get("data"));
        assertEquals(new RelationMutationResponse("owned", 0), controller.attachAgents(request, mark).get("data"));

        assertEquals(Map.of("alias", "updated", "tags", "blue", "sort_order", 7),
                jdbc.queryForMap("SELECT alias, tags, sort_order FROM project_agents "
                        + "WHERE project_id='owned' AND agent_id='existing'"));

        var remove = new ProjectAgentsRequest(" owned ", List.of("new"), null, null);
        assertEquals(new RelationMutationResponse("owned", 1), controller.detachAgents(request, remove).get("data"));
    }

    @Test
    void detachesSelectedEntryHostsInOneWriteWithoutRemovingOtherRelationsOrAssets() {
        project("owned", "user-1", "private", "active", "Owned");
        project("other", "user-1", "private", "active", "Other");
        for (String id : List.of("first", "second", "unselected")) {
            host(id);
            attach("owned", id);
        }
        attach("other", "first");
        for (String id : List.of("child", "first", "second")) {
            Agent node = new Agent();
            node.setAgentId(id);
            node.setParentAgentId("child".equals(id) ? "first" : "root");
            when(agents.findAgentById(id)).thenReturn(node);
        }
        var request = requestAs("normal");
        var selection = new ProjectAgentsRequest("owned", List.of(" child ", "first", "second", "missing"), null, null);

        assertEquals(new RelationMutationResponse("owned", 2), controller.detachAgents(request, selection).get("data"));
        assertEquals(1, queries.sql.stream().filter(sql -> sql.startsWith("DELETE FROM project_agents")).count());
        assertEquals(new RelationMutationResponse("owned", 0), controller.detachAgents(request, selection).get("data"));

        assertEquals(List.of("unselected"), jdbc.queryForList(
                "SELECT agent_id FROM project_agents WHERE project_id='owned'", String.class));
        assertEquals(List.of("first"), jdbc.queryForList(
                "SELECT agent_id FROM project_agents WHERE project_id='other'", String.class));
        assertEquals(List.of("first", "second", "unselected"), jdbc.queryForList(
                "SELECT agent_id FROM agents ORDER BY agent_id", String.class));
    }

    @Test
    void readsMembershipsForMultipleEntryHostsInOneQueryWithoutChangingVisibilityOrOrder() {
        project("owned", "user-1", "private", "active", "zebra");
        project("team", "user-2", "team", "active", "Alpha");
        project("public", "user-2", "public", "archived", "A public");
        project("hidden", "user-2", "private", "active", "Hidden");
        jdbc.update("UPDATE projects SET project_code='shared-code' WHERE project_id='team'");
        host("first");
        host("second");
        for (String id : List.of("owned", "team", "public", "hidden")) attach(id, "first");
        attach("owned", "second");
        for (String id : List.of("child", "first", "second", "unassigned")) {
            Agent node = new Agent();
            node.setAgentId(id);
            node.setCreateByUserId("user-1");
            node.setParentAgentId("child".equals(id) ? "first" : "root");
            when(agents.findAgentById(id)).thenReturn(node);
        }
        var selection = new AgentMembershipsRequest(List.of("second", " child ", "first", "second", "unassigned"));

        var response = controller.listMemberships(requestAs("normal"), selection);

        assertEquals(200, response.get("code"));
        Map<?, ?> memberships = (Map<?, ?>) response.get("data");
        var owned = new ProjectMembershipSummary("owned", "zebra", null, "active");
        var first = List.of(new ProjectMembershipSummary("team", "Alpha", "shared-code", "active"), owned,
                new ProjectMembershipSummary("public", "A public", null, "archived"));
        assertEquals(List.of("second", "child", "first", "unassigned"), List.copyOf(memberships.keySet()));
        assertEquals(List.of(owned), memberships.get("second"));
        assertEquals(first, memberships.get("child"));
        assertEquals(first, memberships.get("first"));
        assertEquals(List.of(), memberships.get("unassigned"));
        assertEquals(1, queries.sql.size(), queries.sql.toString());
    }

    @Test
    void noVisibleProjectsNeedsOnlyTheProjectQuery() throws Exception {
        project("hidden", "user-2", "private", "active", "Hidden");

        assertEquals(List.of(), listAs("normal"));
        assertEquals(1, queries.sql.size());
        assertTrue(queries.sql.get(0).contains("FROM projects"));
    }

    @Test
    void groupedCountsDoNotTruncateLargeProjectListsOrRequirePerProjectBindParameters() throws Exception {
        host("shared");
        for (int index = 0; index < 600; index++) {
            String id = "project-" + index;
            project(id, "user-1", "private", "active", id);
            attach(id, "shared");
        }

        List<ProjectSummary> result = listAs("normal");

        assertEquals(600, result.size());
        assertTrue(result.stream().allMatch(item -> item.hostCount() == 1));
        assertTwoProjectQueries();
    }

    private List<ProjectSummary> listAs(String privilege) throws Exception {
        return listAs(requestAs(privilege));
    }

    @SuppressWarnings("unchecked")
    private List<ProjectSummary> listAs(MockHttpServletRequest request) throws Exception {
        HandlerMethod handler = new HandlerMethod(controller,
                ProjectController.class.getMethod("listProjects", HttpServletRequest.class));
        assertTrue(new LoginInterceptor(permissions).preHandle(request, new MockHttpServletResponse(), handler));
        var response = controller.listProjects(request);
        assertEquals(200, response.get("code"));
        return (List<ProjectSummary>) response.get("data");
    }

    private MockHttpServletRequest requestAs(String privilege) {
        return requestAs(privilege, "user-1", "team-1");
    }

    private MockHttpServletRequest requestAs(String privilege, String userId, String teamId) {
        User user = new User();
        user.setUserId(userId);
        user.setStatus(1);
        user.setTeamId(teamId);
        user.setPrivilege(privilege);
        when(users.getUserById(userId)).thenReturn(user);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/platform/projects");
        request.getSession(true).setAttribute("user", user);
        return request;
    }

    private void assertTwoProjectQueries() {
        assertEquals(2, queries.sql.size(), queries.sql.toString());
        assertTrue(queries.sql.get(0).contains("FROM projects"));
        assertTrue(queries.sql.get(1).contains("GROUP BY project_id"));
    }

    private void project(String id, String owner, String permission, String status, String name) {
        jdbc.update("INSERT INTO projects (project_id, project_name, owner_user_id, team_id, permission, status, create_time, update_time) "
                + "VALUES (?, ?, ?, ?, ?, ?, '2026-10-01', '2026-10-01')", id, name, owner,
                "team".equals(permission) ? "team-1" : null, permission, status);
    }

    private void host(String id) {
        jdbc.update("INSERT INTO agents (agent_id, agent_name, parent_agent_id, create_by_user_id, conn_link, "
                + "req_disguise_id, resp_disguise_id, create_time, update_time) "
                + "VALUES (?, ?, 'root', 'user-1', 'local-fixture', 'request', 'response', '2026-10-01', '2026-10-01')", id, id);
    }

    private void attach(String projectId, String agentId) {
        jdbc.update("INSERT INTO project_agents (project_id, agent_id, added_by_user_id, create_time) "
                + "VALUES (?, ?, 'user-1', '2026-10-01')", projectId, agentId);
    }

    private void session(String id, String projectId, String owner) {
        AgentNodeSession session = new AgentNodeSession();
        session.setProjectId(projectId);
        session.setCreateByUser(owner);
        AgentNodeSessionContainer.addSession(id, session);
    }

    @Intercepts(@Signature(type = StatementHandler.class, method = "prepare", args = {Connection.class, Integer.class}))
    private static class QueryCounter implements Interceptor {
        private final List<String> sql = new ArrayList<>();

        @Override
        public Object intercept(Invocation invocation) throws Throwable {
            sql.add(((StatementHandler) invocation.getTarget()).getBoundSql().getSql());
            return invocation.proceed();
        }
    }
}
