package org.leo.web.controller.platform;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
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
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** AI and HTTP password writes share the real policy, SQLite/MyBatis and login contract. */
class PasswordRoundTripIntegrationTest {

    @TempDir Path temp;
    private final ObjectMapper json = new ObjectMapper();
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
        sqlSession = new SqlSessionFactoryBuilder().build(configuration).openSession(true);
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
    @MethodSource("passwordInputs")
    void preservesEveryPasswordCharacterAcrossCreationResetUpdateChangeAndLogin(boolean ai, String password) throws Exception {
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

    private static Stream<Arguments> passwordInputs() {
        return Stream.of(" edge-fixture-pass ", "\tfixture-pass\t", " 密码-fixture-口令 ")
                .flatMap(password -> Stream.of(Arguments.of(false, password), Arguments.of(true, password)));
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
        User stored = users.getUserByName(username);
        assertTrue(PasswordUtil.verify(password, stored.getPassword()));
        if (!password.equals(password.trim())) {
            assertFalse(PasswordUtil.verify(password.trim(), stored.getPassword()));
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
