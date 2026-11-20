package org.leo.service.team;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.leo.ai.tools.platform.TeamTools;
import org.leo.ai.tools.platform.UserTools;
import org.leo.core.entity.Team;
import org.leo.core.entity.User;
import org.leo.dao.mapper.TeamMapper;
import org.leo.dao.mapper.UserMapper;
import org.leo.service.agentnode.AgentService;
import org.leo.service.user.PasswordPolicy;
import org.leo.service.user.UserService;
import org.leo.service.user.UserViews;
import org.leo.web.config.GlobalExceptionHandler;
import org.leo.web.controller.platform.admin.TeamController;
import org.leo.web.controller.platform.admin.UserController;
import org.leo.web.security.PermissionService;
import org.leo.web.testing.SqliteTestDatabase;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class TeamManagementIntegrationTest {
    @TempDir Path directory;
    private AnnotationConfigApplicationContext context;
    private JdbcTemplate jdbc;
    private TeamService teams;
    private UserService users;
    private TeamTools tools;

    @BeforeEach
    void setUp() throws Exception {
        var source = SqliteTestDatabase.create(directory.resolve("teams.db"));
        jdbc = new JdbcTemplate(source);
        var factory = new SqlSessionFactoryBean();
        factory.setDataSource(source);
        var mybatis = new org.apache.ibatis.session.Configuration();
        mybatis.setMapUnderscoreToCamelCase(true);
        mybatis.addMapper(TeamMapper.class);
        mybatis.addMapper(UserMapper.class);
        factory.setConfiguration(mybatis);
        var template = new SqlSessionTemplate(factory.getObject());
        context = new AnnotationConfigApplicationContext();
        context.register(Transactions.class);
        context.registerBean(DataSourceTransactionManager.class, () -> new DataSourceTransactionManager(source));
        context.registerBean(TeamMapper.class, () -> template.getMapper(TeamMapper.class));
        context.registerBean(UserMapper.class, () -> template.getMapper(UserMapper.class));
        context.register(TeamService.class, UserService.class, TeamTools.class);
        context.refresh();
        teams = context.getBean(TeamService.class);
        users = context.getBean(UserService.class);
        tools = context.getBean(TeamTools.class);
    }

    @AfterEach
    void close() { if (context != null) context.close(); }

    @ParameterizedTest
    @CsvSource({"false,normal", "true,normal", "false,admin", "true,admin"})
    void creationAssignsTheLeaderAndKeepsAdministratorPrivileges(boolean ai, String privilege) {
        seedUser("leader", privilege, null);
        create(ai);

        assertEquals("research", teams.getTeamById("team").getTeamName());
        User leader = users.getUserById("leader");
        assertEquals("team", leader.getTeamId());
        assertEquals(privilege.equals("admin") ? "admin" : "leader", leader.getPrivilege());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void failedCreationRollsBackTheLeadersMembershipAndRole(boolean ai) {
        seedUser("leader", "normal", null);
        jdbc.execute("CREATE TRIGGER reject_create BEFORE INSERT ON teams BEGIN SELECT RAISE(ABORT, 'fixture rejected'); END");

        assertThrows(RuntimeException.class, () -> create(ai));

        assertNull(teams.getTeamById("team"));
        assertAccount("leader", "normal", null);
    }

    @ParameterizedTest
    @CsvSource({"false,system-admin,protected", "true,system-admin,protected",
            "false,protected,系统管理员", "true,protected,系统管理员"})
    void protectedTeamsAreRejectedBeforeAnyMemberIsDetached(boolean ai, String id, String name) {
        seedTeam(id, name, "leader");

        assertThrows(RuntimeException.class, () -> delete(ai, id));

        assertNotNull(teams.getTeamById(id));
        assertAccount("leader", "leader", id);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void deletionDetachesMembersAndDemotesOnlyLeaders(boolean ai) {
        seedMembership();
        seedUser("unassigned", "normal", null);
        User staleMember = users.getUserById("member");
        delete(ai, " team ");

        assertNull(teams.getTeamById("team"));
        assertAccount("leader", "normal", null);
        assertAccount("member", "normal", null);
        assertAccount("admin", "admin", null);
        assertFalse(users.updateUser(staleMember));
        assertEquals(0L, users.getUserById("unassigned").getAccountRevision());
    }

    @Test
    void sharedProjectBlocksTeamDeletionWithoutChangingData() throws Exception {
        seedMembership();
        jdbc.update("""
                INSERT INTO projects (project_id, project_name, owner_user_id, team_id, permission,
                                      status, create_time, update_time)
                VALUES ('shared', 'Shared project', 'leader', 'team', 'team', 'archived', 'fixture', 'fixture')
                """);
        var session = new MockHttpSession();
        session.setAttribute("user", users.getUserById("admin"));
        var controller = new TeamController(teams, new PermissionService(mock(AgentService.class), users));
        var mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new GlobalExceptionHandler()).build();

        mvc.perform(post("/platform/admin/teams/delete").session(session)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"id\":\"team\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409));

        assertNotNull(teams.getTeamById("team"));
        assertAccount("leader", "leader", "team");
        assertAccount("member", "normal", "team");
        assertAccount("admin", "admin", "team");
        assertEquals(Map.of("team_id", "team", "permission", "team", "status", "archived"),
                jdbc.queryForMap("SELECT team_id, permission, status FROM projects WHERE project_id='shared'"));
    }

    @ParameterizedTest
    @CsvSource({"false,member", "true,team"})
    void failedDeletionRollsBackEveryMemberChange(boolean ai, String failurePoint) {
        seedMembership();
        jdbc.execute("member".equals(failurePoint)
                ? "CREATE TRIGGER skip_detach BEFORE UPDATE ON users WHEN OLD.user_id='member' BEGIN SELECT RAISE(IGNORE); END"
                : "CREATE TRIGGER reject_delete BEFORE DELETE ON teams BEGIN SELECT RAISE(ABORT, 'fixture rejected'); END");

        assertThrows(RuntimeException.class, () -> delete(ai, "team"));

        assertNotNull(teams.getTeamById("team"));
        assertAccount("leader", "leader", "team");
        assertAccount("member", "normal", "team");
        assertAccount("admin", "admin", "team");
    }

    @ParameterizedTest
    @ValueSource(strings = {"normal", "admin"})
    void leaderReplacementPreservesPatchSemanticsAndToolResultFields(String privilege) {
        seedMembership();
        seedUser("replacement", privilege, null);
        jdbc.update("UPDATE teams SET description='old', remark='old' WHERE team_id='team'");

        Map<String, Object> result = tools.updateTeam("team", " research ", " replacement ", " ", null, "");

        assertEquals(Map.of("status", "updated", "success", true, "teamId", "team", "teamName", "research"), result);
        Team updated = teams.getTeamById("team");
        assertEquals("replacement", updated.getLeaderId());
        assertEquals(1, updated.getStatus());
        assertNull(updated.getDescription());
        assertNull(updated.getRemark());
        assertAccount("leader", "normal", "team");
        assertAccount("replacement", privilege.equals("admin") ? "admin" : "leader", "team");
    }

    @Test
    void failedLeaderReplacementRollsBackBothRolesAndMemberships() {
        seedMembership();
        seedUser("replacement", "normal", null);
        jdbc.execute("CREATE TRIGGER reject_update BEFORE UPDATE ON teams BEGIN SELECT RAISE(ABORT, 'fixture rejected'); END");

        assertThrows(RuntimeException.class, () -> tools.updateTeam("team", null, "replacement", null, null, null));

        assertEquals("leader", teams.getTeamById("team").getLeaderId());
        assertAccount("leader", "leader", "team");
        assertAccount("replacement", "normal", null);
    }

    @ParameterizedTest
    @ValueSource(strings = {"admin", "leader", "normal", "unassigned"})
    void teamListingKeepsRoleAndMembershipBoundaries(String role) {
        seedTeam("team", "research", "leader");
        seedTeam("other", "other", "someone-else");
        User caller = new User();
        caller.setUserId("leader");
        caller.setPrivilege(role.equals("unassigned") ? "normal" : role);
        caller.setTeamId(role.equals("unassigned") ? null : "team");

        List<String> expected = role.equals("admin") ? List.of("other", "team")
                : role.equals("unassigned") ? List.of() : List.of("team");
        assertEquals(expected, teams.getTeamsForUser(caller).stream().map(Team::getTeamId).sorted().toList());
    }

    @Test
    void bulkUserListingsKeepAccountMetadataWithoutReadingPasswordHashes() {
        seedMembership();
        seedUser("unassigned", "normal", null);
        jdbc.update("""
                UPDATE users SET email='member@example.test', phone='123456789', status=0,
                    last_login_time='2026-10-01 00:00:00', login_count=3,
                    password_change_required=1, account_revision=4, remark='fixture-remark'
                WHERE user_id='member'
                """);

        List<User> all = users.getAllUsers();
        List<User> team = users.getUsersByTeamId("team");
        assertEquals(List.of("admin", "leader", "member", "unassigned"),
                all.stream().map(User::getUserId).sorted().toList());
        assertEquals(List.of("admin", "leader", "member"), team.stream().map(User::getUserId).sorted().toList());

        User stored = users.getUserById("member");
        assertEquals("fixture-hash", stored.getPassword());
        assertEquals("fixture-hash", users.getUserByName("member").getPassword());
        for (List<User> listing : List.of(all, team)) {
            for (User entry : listing) assertNull(entry.getPassword(), entry.getUserId());
            User member = listing.stream().filter(user -> "member".equals(user.getUserId())).findFirst().orElseThrow();
            assertEquals(UserViews.profile(stored), UserViews.profile(member));
            assertEquals(stored.getPasswordChangeRequired(), member.getPasswordChangeRequired());
            assertEquals(stored.getAccountRevision(), member.getAccountRevision());
        }
    }

    @Test
    void unassignedUserListingPreservesBlankTeamIdSemantics() {
        seedMembership();
        seedUser("unassigned", "normal", null);
        seedTeam("", "Empty ID", "empty-id");
        seedTeam(" \t\u3000 ", "Blank ID", "blank-id");
        seedTeam("\u00a0", "Non-breaking space ID", "nonbreaking-id");

        List<User> result = users.getUsersWithoutTeam();

        assertEquals(List.of("unassigned", "empty-id", "blank-id"), result.stream().map(User::getUserId).toList());
        for (User user : result) assertNull(user.getPassword(), user.getUserId());
    }

    @Test
    void accountCreationAndRenameRejectPaddedCaseInsensitiveDuplicateNames() {
        seedUser("original", "normal", null);
        seedUser("member", "normal", null);
        var policy = mock(PasswordPolicy.class);
        var controller = new UserController(users, policy);
        var userTools = new UserTools(users, policy);
        var request = new MockHttpServletRequest();
        User admin = new User();
        admin.setUserId("fixture-admin");
        admin.setPrivilege("admin");
        request.getSession().setAttribute("user", admin);
        User input = new User();
        input.setUserName(" ORIGINAL ");
        input.setPassword("fixture-password");

        var creation = controller.addUser(request, input);
        var rename = controller.updateUser(request, new HashMap<>(Map.of("userId", "member", "userName", " ORIGINAL ")));
        assertEquals(400, creation.get("code"));
        assertEquals("用户名已存在", creation.get("msg"));
        assertEquals(400, rename.get("code"));
        assertEquals("用户名已存在", rename.get("msg"));
        assertEquals("用户名已存在", assertThrows(IllegalArgumentException.class, () -> userTools.addUser(
                " ORIGINAL ", "fixture-password", null, null, null, null, null, null, null)).getMessage());
        assertEquals("用户名已存在", assertThrows(IllegalArgumentException.class, () -> userTools.updateUser(
                "member", " ORIGINAL ", null, null, null, null, null, null, null)).getMessage());
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM users", Integer.class));
        assertEquals("member", users.getUserById("member").getUserName());
    }

    @Test
    void anUnsuccessfulMemberWriteRollsBackThePreviousLeaderDemotion() {
        seedMembership();
        seedUser("replacement", "normal", null);
        jdbc.execute("CREATE TRIGGER skip_member BEFORE UPDATE ON users WHEN NEW.user_id='replacement' BEGIN SELECT RAISE(IGNORE); END");

        assertThrows(IllegalStateException.class, () -> tools.updateTeam("team", null, "replacement", null, null, null));

        assertEquals("leader", teams.getTeamById("team").getLeaderId());
        assertAccount("leader", "leader", "team");
        assertAccount("replacement", "normal", null);
    }

    private void create(boolean ai) {
        if (ai) {
            assertEquals(Map.of("status", "created", "success", true, "teamId", "team", "teamName", "research"),
                    tools.addTeam(" team ", " research ", " leader ", null, null, null));
        } else teams.createWithLeader(team(" team ", " research ", " leader "));
    }

    private void delete(boolean ai, String id) {
        if (ai) {
            var result = tools.deleteTeam(id);
            assertEquals("deleted", result.get("status"));
            assertEquals(true, result.get("success"));
        } else teams.deleteWithMembers(id);
    }

    private void seedMembership() {
        seedTeam("team", "research", "leader");
        seedUser("member", "normal", "team");
        seedUser("admin", "admin", "team");
    }

    private void seedTeam(String id, String name, String leader) {
        new TransactionTemplate(context.getBean(DataSourceTransactionManager.class)).executeWithoutResult(status -> {
            seedUser(leader, "leader", id);
            assertTrue(teams.addTeam(team(id, name, leader)));
        });
    }

    private Team team(String id, String name, String leader) {
        Team team = new Team();
        team.setTeamId(id);
        team.setTeamName(name);
        team.setLeaderId(leader);
        return team;
    }

    private void seedUser(String id, String privilege, String team) {
        jdbc.update("INSERT INTO users (user_id,user_name,password,privilege,team_id,create_time,update_time) VALUES (?,?,?,?,?,'fixture','fixture')",
                id, id, "fixture-hash", privilege, team);
    }

    private void assertAccount(String id, String privilege, String team) {
        User user = users.getUserById(id);
        assertEquals(privilege, user.getPrivilege());
        assertEquals(team, user.getTeamId());
    }

    @Configuration
    @EnableTransactionManagement
    static class Transactions {}
}
