package org.leo.web.controller.platform;

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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.leo.core.entity.User;
import org.leo.core.util.PasswordUtil;
import org.leo.dao.mapper.SystemConfigMapper;
import org.leo.dao.mapper.UserMapper;
import org.leo.service.AgentService;
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
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Real password policy, SQLite/MyBatis, interceptors and HTTP binding share one contract. */
class PasswordRoundTripIntegrationTest {

    @TempDir Path temp;
    private final ObjectMapper json = new ObjectMapper();
    private SqlSession sqlSession;
    private UserService users;
    private MockMvc mvc;
    private MockHttpSession adminSession;

    @BeforeEach
    void setUp() {
        SQLiteConfig sqlite = new SQLiteConfig();
        sqlite.enforceForeignKeys(true);
        SQLiteDataSource source = new SQLiteDataSource(sqlite);
        source.setUrl("jdbc:sqlite:" + temp.resolve("passwords.db"));
        new ResourceDatabasePopulator(new ClassPathResource("sql/schema.sql")).execute(source);
        Configuration configuration = new Configuration(new Environment(
                "test", new JdbcTransactionFactory(), source));
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(UserMapper.class);
        configuration.addMapper(SystemConfigMapper.class);
        sqlSession = new SqlSessionFactoryBuilder().build(configuration).openSession(true);
        users = new UserService(sqlSession.getMapper(UserMapper.class));
        SystemConfigService config = new SystemConfigService(sqlSession.getMapper(SystemConfigMapper.class));
        PasswordPolicy passwords = new PasswordPolicy(config);
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
    @ValueSource(strings = {" edge-fixture-pass ", "\tfixture-pass\t", " 密码-fixture-口令 "})
    void preservesEveryPasswordCharacterAcrossCreationResetUpdateChangeAndLogin(String password) throws Exception {
        createUser(password);
        assertLogin("member", password);
        String userId = users.getUserByName("member").getUserId();

        String reset = "\treset-" + password + " ";
        mvc.perform(body("/platform/admin/users/reset-password",
                        Map.of("userId", userId, "newPassword", reset)).session(adminSession))
                .andExpect(status().isOk());
        assertLogin("member", reset);

        String updated = " " + password + "-updated\t";
        mvc.perform(body("/platform/admin/users/update",
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

    @Test
    void appliesPasswordLengthLimitsToTheOriginalValue() throws Exception {
        String maximum = " " + "x".repeat(254) + " ";
        createUser(maximum);
        assertLogin("member", maximum);
        String oversized = maximum + " ";
        mvc.perform(body("/platform/user/login", Map.of("username", "member", "password", oversized)))
                .andExpect(status().isBadRequest());
        mvc.perform(body("/platform/admin/users", Map.of(
                        "userName", "oversized", "password", oversized, "privilege", "normal"))
                        .session(adminSession))
                .andExpect(status().isBadRequest());
    }

    @Test
    void emptyOptionalPasswordKeepsTheHashWhileBlankPasswordIsRejected() throws Exception {
        createUser("current-fixture-password");
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

    private void createUser(String password) throws Exception {
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
