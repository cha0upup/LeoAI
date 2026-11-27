package org.leo.web.controller.platform;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.leo.ai.tools.platform.UserTools;
import org.leo.core.entity.User;
import org.leo.core.util.PasswordUtil;
import org.leo.dao.mapper.SystemConfigMapper;
import org.leo.dao.mapper.UserMapper;
import org.leo.service.agentnode.AgentService;
import org.leo.service.config.SystemConfigService;
import org.leo.service.team.TeamService;
import org.leo.service.user.PasswordPolicy;
import org.leo.service.user.SystemInitializationService;
import org.leo.service.user.UserService;
import org.leo.web.config.ApiResponseStatusAdvice;
import org.leo.web.config.GlobalExceptionHandler;
import org.leo.web.controller.platform.admin.UserController;
import org.leo.web.interceptor.LoginInterceptor;
import org.leo.web.security.LoginAttemptService;
import org.leo.web.security.PermissionService;
import org.leo.web.testing.SqliteTestDatabase;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** AI and HTTP password writes share the real policy, SQLite/MyBatis and login contract. */
class PasswordRoundTripIntegrationTest {

    @TempDir Path temp;
    private final ObjectMapper json = new ObjectMapper();
    private SqlSessionFactory factory;
    private SqlSession sqlSession;
    private UserService users;
    private UserTools tools;
    private MockMvc mvc;
    private MockHttpSession adminSession;

    @BeforeEach
    void setUp() {
        SQLiteDataSource source = SqliteTestDatabase.create(temp.resolve("passwords.db"));
        Configuration configuration = SqliteTestDatabase.myBatisConfiguration(source);
        configuration.addMapper(UserMapper.class);
        configuration.addMapper(SystemConfigMapper.class);
        factory = new SqlSessionFactoryBuilder().build(configuration);
        sqlSession = factory.openSession(true);
        users = new UserService(sqlSession.getMapper(UserMapper.class));
        SystemConfigService config = new SystemConfigService(sqlSession.getMapper(SystemConfigMapper.class));
        PasswordPolicy passwords = new PasswordPolicy(config);
        tools = new UserTools(users, passwords);
        PermissionService permissions = new PermissionService(mock(AgentService.class), users);
        mvc = MockMvcBuilders.standaloneSetup(
                        new LoginController(users, mock(TeamService.class), permissions,
                                new LoginAttemptService(config), passwords, mock(SystemInitializationService.class)),
                        new UserController(users, passwords))
                .addMappedInterceptors(new String[]{"/platform/admin/**", "/platform/user/change-password"},
                        new LoginInterceptor(permissions))
                .setControllerAdvice(new GlobalExceptionHandler(), new ApiResponseStatusAdvice())
                .build();

        User admin = new User();
        admin.setUserId("fixture-admin");
        admin.setUserName("fixture-admin");
        admin.setPrivilege(UserService.PRIVILEGE_ADMIN);
        admin.setPassword(PasswordUtil.hash("fixture-admin-password"));
        admin.setStatus(1);
        users.addUser(admin);
        adminSession = new MockHttpSession();
        adminSession.setAttribute("user", admin);
    }

    @AfterEach
    void tearDown() {
        if (sqlSession != null) sqlSession.close();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void preservesEveryPasswordCharacterAcrossCreationResetUpdateChangeAndLogin(boolean ai) throws Exception {
        String password = "\t 密码-fixture-口令 \t";
        createUser(password, ai);
        assertLogin("member", password);
        String userId = users.getUserByName("member").getUserId();

        String reset = "\treset-" + password + " ";
        mvc.perform(body("/platform/admin/users/reset-password",
                        Map.of("userId", userId, "newPassword", reset)).session(adminSession))
                .andExpect(status().isOk());
        assertLogin("member", reset);

        String updated = " " + password + "-updated\t";
        if (ai) assertEquals(true, tools.updateUser(userId, null, updated, null, null, null, null, null, null).get("success"));
        else mvc.perform(body("/platform/admin/users/update",
                            Map.of("userId", userId, "password", updated)).session(adminSession))
                    .andExpect(status().isOk());
        MockHttpSession memberSession = assertLogin("member", updated);

        String changed = "\t" + password + "-self-change ";
        mvc.perform(body("/platform/user/change-password",
                        Map.of("oldPassword", updated, "newPassword", changed)).session(memberSession))
                .andExpect(status().isOk());
        assertEquals(0, users.getUserById(userId).getPasswordChangeRequired());
        assertLogin("member", changed);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void appliesPasswordLengthLimitsToTheOriginalValue(boolean ai) throws Exception {
        String maximum = " " + "x".repeat(254) + " ";
        createUser(maximum, ai);
        assertLogin("member", maximum);
        String oversized = maximum + " ";
        mvc.perform(body("/platform/user/login", Map.of("username", "member", "password", oversized)))
                .andExpect(status().isBadRequest());
        if (ai) assertThrows(IllegalArgumentException.class,
                () -> tools.addUser("oversized", oversized, null, null, null, null, null, null, null));
        else mvc.perform(body("/platform/admin/users", Map.of(
                            "userName", "oversized", "password", oversized, "privilege", "normal"))
                            .session(adminSession))
                    .andExpect(status().isBadRequest());
    }

    @Test
    void emptyOptionalPasswordKeepsTheHashWhileBlankPasswordIsRejected() throws Exception {
        createUser("current-fixture-password", false);
        User member = users.getUserByName("member");
        mvc.perform(body("/platform/admin/users/update",
                        Map.of("userId", member.getUserId(), "password", "")).session(adminSession))
                .andExpect(status().isOk());
        assertEquals(member.getPassword(), users.getUserById(member.getUserId()).getPassword());
        mvc.perform(body("/platform/admin/users/update",
                        Map.of("userId", member.getUserId(), "password", " \t ")).session(adminSession))
                .andExpect(status().isBadRequest());
        mvc.perform(body("/platform/admin/users/reset-password",
                        Map.of("userId", member.getUserId(), "newPassword", " \t ")).session(adminSession))
                .andExpect(status().isBadRequest());
        mvc.perform(body("/platform/user/login", Map.of("username", "member", "password", " \t ")))
                .andExpect(status().isBadRequest());
        assertEquals(member.getPassword(), users.getUserById(member.getUserId()).getPassword());
    }

    @Test
    void creationReportsFailureWhenTheDatabaseInsertsNoUser() throws Exception {
        try (var statement = sqlSession.getConnection().createStatement()) {
            statement.execute("""
                    CREATE TRIGGER skip_fixture_user BEFORE INSERT ON users
                    WHEN NEW.user_name = 'skipped-fixture'
                    BEGIN SELECT RAISE(IGNORE); END
                    """);
        }

        mvc.perform(body("/platform/admin/users", Map.of("userName", "skipped-fixture",
                        "password", "fixture-password", "privilege", "normal")).session(adminSession))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value(500))
                .andExpect(jsonPath("$.msg").value("创建用户失败"));
        assertNull(users.getUserByName("skipped-fixture"));
    }

    @Test
    void staleProfileWriteDoesNotUndoAnAccountChange() throws Exception {
        createUser("current-fixture-password", false);
        User member = users.getUserByName("member");
        member.setPasswordChangeRequired(0);
        assertTrue(users.updateUser(member));
        String userId = member.getUserId();
        try (SqlSession request = factory.openSession(true)) {
            UserService editor = new UserService(request.getMapper(UserMapper.class));
            User profile = editor.getUserById(userId);

            mvc.perform(body("/platform/admin/users/reset-password",
                            Map.of("id", userId, "newPassword", "ignored-fixture-password"))
                            .session(adminSession))
                    .andExpect(status().isBadRequest());
            mvc.perform(body("/platform/admin/users/reset-password",
                            Map.of("userId", userId, "newPassword", "replacement-fixture-password"))
                            .session(adminSession))
                    .andExpect(status().isOk());
            assertNull(editor.updateProfile(profile, "member@example.test", null, null));
        }
        User stored = users.getUserById(userId);
        assertTrue(PasswordUtil.verify("replacement-fixture-password", stored.getPassword()));
        assertEquals(1, stored.getPasswordChangeRequired());
        assertNull(stored.getEmail());
    }

    @Test
    void aProfileSavedFromAnOlderTabDoesNotOverwriteNewerChanges() throws Exception {
        String path = "/platform/user/profile";
        String snapshot = mvc.perform(get(path).session(adminSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accountRevision").isNumber())
                .andReturn().getResponse().getContentAsString();
        long revision = json.readTree(snapshot).path("data").path("accountRevision").asLong();

        mvc.perform(body(path, Map.of("accountRevision", revision, "email", "latest@example.test"))
                        .session(adminSession))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.accountRevision").value(revision + 1));
        mvc.perform(body(path, Map.of("accountRevision", revision, "remark", "Stale draft"))
                        .session(adminSession))
                .andExpect(status().isConflict());
        mvc.perform(body(path, Map.of("remark", "Draft without a revision")).session(adminSession))
                .andExpect(status().isBadRequest());

        User stored = users.getUserById("fixture-admin");
        assertEquals("latest@example.test", stored.getEmail());
        assertNull(stored.getRemark());
    }

    @Test
    void editingAnAccountDoesNotOverwriteLoginStatisticsFromAConcurrentLogin() throws Exception {
        createUser("current-fixture-password", false);
        try (SqlSession request = factory.openSession(true)) {
            UserService editor = new UserService(request.getMapper(UserMapper.class));
            User profile = editor.getUserByName("member");
            assertEquals(0, profile.getLoginCount());

            assertLogin("member", "current-fixture-password");
            profile.setEmail("member@example.test");
            assertTrue(editor.updateUser(profile));
        }
        sqlSession.clearCache();
        User stored = users.getUserByName("member");
        assertEquals("member@example.test", stored.getEmail());
        assertEquals(1, stored.getLoginCount());
        assertNotNull(stored.getLastLoginTime());
    }

    @Test
    void profileEndpointValidatesAndSavesOnlyEditableFieldsWithoutChangingCredentials() throws Exception {
        User verified = users.getUserById("fixture-admin");
        assertNotNull(users.recordSuccessfulLogin(verified.getUserId(), verified.getPassword()));
        String path = "/platform/user/profile";

        long revision = verified.getAccountRevision();
        mvc.perform(body(path, Map.of("email", "invalid-email", "accountRevision", revision)).session(adminSession))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.msg").value("邮箱格式不正确"));
        mvc.perform(body(path, Map.of("email", "x".repeat(101), "accountRevision", revision)).session(adminSession))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.msg").value("邮箱不能超过 100 个字符"));

        mvc.perform(body(path, Map.of("email", " example@example.test ", "phone", " 13800138000 ", "remark", "  ",
                        "accountRevision", revision))
                        .session(adminSession))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.email").value("example@example.test"))
                .andExpect(jsonPath("$.data.phone").value("13800138000"))
                .andExpect(jsonPath("$.data.remark").hasJsonPath())
                .andExpect(jsonPath("$.data.loginCount").value(1))
                .andExpect(jsonPath("$.data.lastLoginTime").isNotEmpty())
                .andExpect(jsonPath("$.data.password").doesNotHaveJsonPath());

        User sessionUser = (User) adminSession.getAttribute("user");
        assertNull(sessionUser.getEmail());
        assertEquals(verified.getAccountRevision(), sessionUser.getAccountRevision());
        assertEquals(verified.getPassword(), sessionUser.getPassword());
        User stored = users.getUserById(verified.getUserId());
        assertEquals("example@example.test", stored.getEmail());
        assertEquals("13800138000", stored.getPhone());
        assertNull(stored.getRemark());
        assertEquals(verified.getAccountRevision() + 1, stored.getAccountRevision());
        assertEquals(verified.getPassword(), stored.getPassword());
        assertEquals(verified.getPrivilege(), stored.getPrivilege());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aChangedPasswordOrDisabledAccountRejectsStaleCredentialsAndInvalidatesAnExistingSession(boolean disable)
            throws Exception {
        createUser("current-fixture-password", false);
        MockHttpSession existingSession = assertLogin("member", "current-fixture-password");
        User verified = (User) existingSession.getAttribute("user");
        assertEquals(1, verified.getLoginCount());
        assertNotNull(verified.getLastLoginTime());

        try (SqlSession request = factory.openSession(true)) {
            UserService editor = new UserService(request.getMapper(UserMapper.class));
            User changed = editor.getUserById(verified.getUserId());
            if (disable) changed.setStatus(0);
            else changed.setPassword(PasswordUtil.hash("replacement-fixture-password"));
            assertTrue(editor.updateUser(changed));
        }

        assertNull(users.recordSuccessfulLogin(verified.getUserId(), verified.getPassword()));
        mvc.perform(get("/platform/user/profile").session(existingSession))
                .andExpect(status().isUnauthorized());
        assertTrue(existingSession.isInvalid());
        User stored = users.getUserById(verified.getUserId());
        assertEquals(1, stored.getLoginCount());
        assertEquals(verified.getLastLoginTime(), stored.getLastLoginTime());
    }

    private void createUser(String password, boolean ai) throws Exception {
        if (ai) {
            assertEquals(true, tools.addUser("member", password, null, null, null, null, null, null, null).get("success"));
            return;
        }
        mvc.perform(body("/platform/admin/users", Map.of(
                        "userName", "member", "password", password, "privilege", "normal"))
                        .session(adminSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    private MockHttpSession assertLogin(String username, String password) throws Exception {
        if (!password.equals(password.trim())) {
            mvc.perform(body("/platform/user/login", Map.of("username", username, "password", password.trim())))
                    .andExpect(status().isUnauthorized());
        }
        var result = mvc.perform(body("/platform/user/login",
                        Map.of("username", " " + username + " ", "password", password)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.userName").value(username))
                .andReturn();
        MockHttpSession session = (MockHttpSession) result.getRequest().getSession(false);
        assertNotNull(session);
        return session;
    }

    private MockHttpServletRequestBuilder body(String path, Map<String, ?> body) throws Exception {
        return post(path).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(body));
    }
}
