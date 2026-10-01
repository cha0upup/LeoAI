package org.leo.web.controller.platform.project;

import jakarta.servlet.http.HttpServletRequest;
import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.leo.core.entity.User;
import org.leo.core.session.PuppetNodeSession;
import org.leo.core.session.PuppetNodeSessionContainer;
import org.leo.dao.mapper.ProjectMapper;
import org.leo.service.PuppetService;
import org.leo.service.project.ProjectService;
import org.leo.service.user.UserService;
import org.leo.web.dto.platform.project.ProjectDtos.ProjectSummary;
import org.leo.web.interceptor.LoginInterceptor;
import org.leo.web.security.PermissionService;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 使用实际 SQLite schema 和 MyBatis 映射验证列表契约及查询次数。 */
class ProjectListIntegrationTest {

    @TempDir Path temp;
    private JdbcTemplate jdbc;
    private SqlSession sqlSession;
    private ProjectController controller;
    private PermissionService permissions;
    private UserService users;
    private final QueryCounter queries = new QueryCounter();

    @BeforeEach
    void setUp() {
        SQLiteConfig sqlite = new SQLiteConfig();
        sqlite.enforceForeignKeys(true);
        SQLiteDataSource dataSource = new SQLiteDataSource(sqlite);
        dataSource.setUrl("jdbc:sqlite:" + temp.resolve("projects.db"));
        new ResourceDatabasePopulator(new ClassPathResource("sql/schema.sql")).execute(dataSource);
        jdbc = new JdbcTemplate(dataSource);
        Configuration configuration = new Configuration(new Environment(
                "test", new JdbcTransactionFactory(), dataSource));
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(ProjectMapper.class);
        configuration.addInterceptor(queries);
        sqlSession = new SqlSessionFactoryBuilder().build(configuration).openSession(true);
        PuppetService puppets = mock(PuppetService.class);
        users = mock(UserService.class);
        permissions = new PermissionService(puppets, users);
        controller = new ProjectController(
                new ProjectService(sqlSession.getMapper(ProjectMapper.class), puppets), permissions);
    }

    @AfterEach
    void tearDown() {
        if (sqlSession != null) sqlSession.close();
        PuppetNodeSessionContainer.clearAllSessions();
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

    @SuppressWarnings("unchecked")
    private List<ProjectSummary> listAs(String privilege) throws Exception {
        User user = new User();
        user.setUserId("user-1");
        user.setStatus(1);
        user.setTeamId("team-1");
        user.setPrivilege(privilege);
        when(users.getUserById("user-1")).thenReturn(user);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/platform/projects");
        request.getSession(true).setAttribute("user", user);
        HandlerMethod handler = new HandlerMethod(controller,
                ProjectController.class.getMethod("listProjects", HttpServletRequest.class));
        assertTrue(new LoginInterceptor(permissions).preHandle(request, new MockHttpServletResponse(), handler));
        var response = controller.listProjects(request);
        assertEquals(200, response.get("code"));
        return (List<ProjectSummary>) response.get("data");
    }

    private void assertTwoProjectQueries() {
        assertEquals(2, queries.sql.size(), queries.sql.toString());
        assertTrue(queries.sql.get(0).contains("FROM projects"));
        assertTrue(queries.sql.get(1).contains("GROUP BY project_id"));
    }

    private void project(String id, String owner, String permission, String status, String name) {
        jdbc.update("INSERT INTO projects (project_id, project_name, owner_user_id, team_id, permission, status, create_time, update_time) "
                + "VALUES (?, ?, ?, 'team-1', ?, ?, '2026-10-01', '2026-10-01')", id, name, owner, permission, status);
    }

    private void host(String id) {
        jdbc.update("INSERT INTO puppets (puppet_id, puppet_name, parent_puppet_id, create_by_user_id, conn_link, "
                + "req_disguise_id, resp_disguise_id, create_time, update_time) "
                + "VALUES (?, ?, 'root', 'user-1', 'local-fixture', 'request', 'response', '2026-10-01', '2026-10-01')", id, id);
    }

    private void attach(String projectId, String puppetId) {
        jdbc.update("INSERT INTO project_puppets (project_id, puppet_id, added_by_user_id, create_time) "
                + "VALUES (?, ?, 'user-1', '2026-10-01')", projectId, puppetId);
    }

    private void session(String id, String projectId, String owner) {
        PuppetNodeSession session = new PuppetNodeSession();
        session.setProjectId(projectId);
        session.setCreateByUser(owner);
        PuppetNodeSessionContainer.addSession(id, session);
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
