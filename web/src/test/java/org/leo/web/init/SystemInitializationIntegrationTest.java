package org.leo.web.init;

import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.leo.core.entity.User;
import org.leo.core.util.PasswordUtil;
import org.leo.dao.mapper.SystemConfigMapper;
import org.leo.dao.mapper.SystemInitializationMapper;
import org.leo.dao.mapper.TeamMapper;
import org.leo.dao.mapper.UserMapper;
import org.leo.service.config.SystemConfigService;
import org.leo.service.team.TeamService;
import org.leo.service.user.PasswordPolicy;
import org.leo.service.user.SystemInitializationService;
import org.leo.service.user.UserService;
import org.leo.service.AgentService;
import org.leo.web.config.ApiResponseStatusAdvice;
import org.leo.web.config.GlobalExceptionHandler;
import org.leo.web.config.SpaForwardController;
import org.leo.web.controller.platform.LoginController;
import org.leo.web.controller.platform.SystemSetupController;
import org.leo.web.security.LoginAttemptService;
import org.leo.web.security.PermissionService;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real SQLite, MyBatis and Spring transactions, including competing application instances. */
class SystemInitializationIntegrationTest {
    @TempDir Path temp;
    private SQLiteDataSource source;
    private AnnotationConfigApplicationContext context;
    private SystemInitializationService setup;
    private UserService users;
    private JdbcTemplate jdbc;
    private MockMvc mvc;

    @Configuration
    @EnableTransactionManagement(proxyTargetClass = true)
    static class Transactions {}

    @BeforeEach
    void setUp() {
        var config = new SQLiteConfig();
        config.enforceForeignKeys(true);
        config.setBusyTimeout(15000);
        config.setJournalMode(SQLiteConfig.JournalMode.WAL);
        source = new SQLiteDataSource(config);
        source.setUrl("jdbc:sqlite:" + temp.resolve("setup.db"));
        new ResourceDatabasePopulator(new ClassPathResource("sql/schema.sql")).execute(source);
        context = application();
        setup = context.getBean(SystemInitializationService.class);
        users = context.getBean(UserService.class);
        jdbc = new JdbcTemplate(source);
        mvc = MockMvcBuilders.standaloneSetup(new SystemSetupController(setup), new SpaForwardController(),
                        new LoginController(users, context.getBean(TeamService.class),
                                new PermissionService(mock(AgentService.class), users),
                                new LoginAttemptService(context.getBean(SystemConfigService.class)),
                                context.getBean(PasswordPolicy.class), setup))
                .setControllerAdvice(new GlobalExceptionHandler(), new ApiResponseStatusAdvice())
                .build();
    }

    private AnnotationConfigApplicationContext application() {
        var app = new AnnotationConfigApplicationContext();
        app.register(Transactions.class);
        app.registerBean(DataSource.class, () -> source);
        app.registerBean(PlatformTransactionManager.class, () -> new DataSourceTransactionManager(source));
        app.registerBean(SqlSessionFactory.class, () -> {
            var factory = new SqlSessionFactoryBean();
            factory.setDataSource(source);
            var config = new org.apache.ibatis.session.Configuration();
            config.setMapUnderscoreToCamelCase(true);
            config.addMappers("org.leo.dao.mapper");
            factory.setConfiguration(config);
            try { return factory.getObject(); }
            catch (Exception error) { throw new IllegalStateException(error); }
        });
        app.registerBean(SqlSessionTemplate.class, () -> new SqlSessionTemplate(app.getBean(SqlSessionFactory.class)));
        for (Class<?> type : List.of(UserMapper.class, TeamMapper.class, SystemConfigMapper.class,
                SystemInitializationMapper.class)) {
            registerMapper(app, type);
        }
        app.register(UserService.class, TeamService.class, PasswordPolicy.class, SystemConfigService.class,
                SystemInitializationService.class);
        app.refresh();
        return app;
    }

    private <T> void registerMapper(AnnotationConfigApplicationContext app, Class<T> type) {
        app.registerBean(type, () -> app.getBean(SqlSessionTemplate.class).getMapper(type));
    }

    @AfterEach
    void close() { if (context != null) context.close(); }

    @Test
    void freshStartupWaitsForTheWizardThenStoresTheExactPasswordAndClosesSetup() {
        new DataInitializer(setup).init();
        assertTrue(setup.isInitializationRequired());
        assertEquals(0, count("users"));
        assertEquals(0, count("teams"));
        String password = " \tchosen-fixture-password\t ";
        setup.initializeAdmin(password, password);
        User admin = users.getUserByName("admin");
        assertTrue(PasswordUtil.verify(password, admin.getPassword()));
        assertFalse(PasswordUtil.verify(password.trim(), admin.getPassword()));
        assertEquals("admin", admin.getPrivilege());
        assertEquals(TeamService.ADMIN_TEAM_ID, admin.getTeamId());
        assertEquals(0, admin.getPasswordChangeRequired());
        assertEquals(1, admin.getStatus());
        assertFalse(setup.isInitializationRequired());
        assertEquals(1, count("teams"));
        assertThrows(SystemInitializationService.AlreadyInitializedException.class,
                () -> setup.initializeAdmin("replacement-password", "replacement-password"));
        assertEquals(admin.getPassword(), users.getUserByName("admin").getPassword());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" \t ", "short"})
    void invalidPasswordsRollBackTheClaimAndAllowRetry(String password) {
        assertThrows(IllegalArgumentException.class, () -> setup.initializeAdmin(password, password));
        assertEmptyInstallation();
        setup.initializeAdmin("valid-fixture-password", "valid-fixture-password");
        assertFalse(setup.isInitializationRequired());
    }

    @Test
    void mismatchedAndOverlongPasswordsLeaveNoPartialState() {
        assertThrows(IllegalArgumentException.class,
                () -> setup.initializeAdmin("valid-fixture-password", "different-password"));
        assertEmptyInstallation();
        String longPassword = "x".repeat(257);
        assertThrows(IllegalArgumentException.class, () -> setup.initializeAdmin(longPassword, longPassword));
        assertEmptyInstallation();
    }

    @Test
    void usesTheConfiguredPasswordPolicy() {
        context.getBean(SystemConfigMapper.class).upsert("security.password.min.length", "12", "number", "fixture");
        assertEquals(12, context.getBean(PasswordPolicy.class).getMinimumLength());
        assertThrows(IllegalArgumentException.class, () -> setup.initializeAdmin("12345678", "12345678"));
        assertTrue(setup.isInitializationRequired());
        setup.initializeAdmin("123456789012", "123456789012");
    }

    @Test
    void storageFailureRollsBackTheTeamAndCompletionMarker() {
        jdbc.execute("CREATE TRIGGER fixture_failure BEFORE INSERT ON users BEGIN SELECT RAISE(ABORT, 'fixture'); END");
        assertThrows(RuntimeException.class,
                () -> setup.initializeAdmin("valid-fixture-password", "valid-fixture-password"));
        assertEmptyInstallation();
        jdbc.execute("DROP TRIGGER fixture_failure");
        setup.initializeAdmin("valid-fixture-password", "valid-fixture-password");
        assertFalse(setup.isInitializationRequired());
    }

    @Test
    void startupDoesNotAdoptOrRepairAccountsOutsideTheSetupWorkflow() {
        User account = new User("fixture-account", "fixture-account", PasswordUtil.hash("fixture-password"),
                "admin", "2026-10-29");
        users.addUser(account);
        new DataInitializer(setup).init();
        assertFalse(setup.isInitializationRequired());
        assertThrows(SystemInitializationService.AlreadyInitializedException.class,
                () -> setup.initializeAdmin("replacement-password", "replacement-password"));
        assertEquals(0, count("teams"));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM system_configs WHERE config_key='system.initialization.completed'", Integer.class));
        assertEquals(account.getPassword(), users.getUserById("fixture-account").getPassword());
    }

    @Test
    void currentAccountsKeepTheirCredentialsAndPasswordChangeFlagAcrossRestart() {
        setup.initializeAdmin("chosen-fixture-password", "chosen-fixture-password");
        User admin = users.getUserById("admin");
        admin.setStatus(0);
        admin.setPasswordChangeRequired(1);
        users.updateUser(admin);
        new DataInitializer(setup).init();
        User stored = users.getUserById("admin");
        assertEquals(admin.getPassword(), stored.getPassword());
        assertEquals(0, stored.getStatus());
        assertEquals(1, stored.getPasswordChangeRequired());
        assertEquals(1, count("teams"));
        assertFalse(setup.isInitializationRequired());
    }

    @Test
    void accountRemovalAfterSetupAndRestartDoesNotReopenTheWizard() {
        setup.initializeAdmin("chosen-fixture-password", "chosen-fixture-password");
        jdbc.update("DELETE FROM users");
        try (var next = application()) {
            var restarted = next.getBean(SystemInitializationService.class);
            new DataInitializer(restarted).init();
            assertFalse(restarted.isInitializationRequired());
        }
    }

    @Test
    void competingInstancesCreateExactlyOneAdminAndNeverOverwriteItsPassword() throws Exception {
        try (var other = application()) {
            var second = other.getBean(SystemInitializationService.class);
            var start = new CountDownLatch(1);
            var executor = Executors.newFixedThreadPool(2);
            try {
                Callable<Boolean> firstCall = () -> attempt(setup, start, "first-fixture-password");
                Callable<Boolean> secondCall = () -> attempt(second, start, "second-fixture-password");
                var first = executor.submit(firstCall);
                var next = executor.submit(secondCall);
                start.countDown();
                assertNotEquals(first.get(20, TimeUnit.SECONDS), next.get(20, TimeUnit.SECONDS));
                assertEquals(1, count("users"));
                assertEquals(1, count("teams"));
                String hash = users.getUserByName("admin").getPassword();
                assertTrue(PasswordUtil.verify("first-fixture-password", hash)
                        || PasswordUtil.verify("second-fixture-password", hash));
            } finally { executor.shutdownNow(); }
        }
    }

    @Test
    void httpWizardCreatesAnAdminWithoutLoggingInOrRequiringAnotherPasswordChange() throws Exception {
        mvc.perform(get("/platform/user/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.initializationRequired").value(true))
                .andExpect(jsonPath("$.data.passwordMinLength").value(8));
        var result = mvc.perform(post("/platform/setup").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"chosen-fixture-password\",\"confirmPassword\":\"chosen-fixture-password\"}"))
                .andExpect(status().isOk()).andReturn();
        assertNull(result.getRequest().getSession(false));
        mvc.perform(get("/platform/user/status"))
                .andExpect(jsonPath("$.data.initializationRequired").value(false))
                .andExpect(jsonPath("$.data.isLoggedIn").value(false));
        mvc.perform(post("/platform/user/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"chosen-fixture-password\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.passwordChangeRequired").value(false));
        mvc.perform(post("/platform/setup").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"replacement-password\",\"confirmPassword\":\"replacement-password\"}"))
                .andExpect(status().isConflict());
        assertTrue(PasswordUtil.verify("chosen-fixture-password", users.getUserByName("admin").getPassword()));
    }

    @Test
    void httpSetupRejectsInvalidPasswordsAndMalformedOrNonJsonBodies() throws Exception {
        for (String body : List.of("null", "{}", "{", "{\"password\":\"short\",\"confirmPassword\":\"short\"}",
                "{\"password\":\"chosen-fixture-password\",\"confirmPassword\":\"different-password\"}")) {
            mvc.perform(post("/platform/setup").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
            assertEmptyInstallation();
        }
        mvc.perform(post("/platform/setup").contentType(MediaType.TEXT_PLAIN).content("{}"))
                .andExpect(status().isUnsupportedMediaType());
        assertEmptyInstallation();
    }

    private boolean attempt(SystemInitializationService target, CountDownLatch start, String password) throws Exception {
        start.await();
        try { target.initializeAdmin(password, password); return true; }
        catch (SystemInitializationService.AlreadyInitializedException expected) { return false; }
    }

    private int count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class); }

    private void assertEmptyInstallation() {
        assertTrue(setup.isInitializationRequired());
        assertEquals(0, count("users"));
        assertEquals(0, count("teams"));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM system_configs WHERE config_key='system.initialization.completed'", Integer.class));
    }
}
