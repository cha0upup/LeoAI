package org.leo.web.init;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.leo.core.init.DatabaseInitializer;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.sql.init.SqlDataSourceScriptDatabaseInitializer;
import org.springframework.boot.autoconfigure.sql.init.SqlInitializationAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/** Covers schema creation, baseline validation and non-destructive restart in one place. */
class SchemaInitializationTest {
    @TempDir Path temp;

    @Test
    void freshSchemaMatchesTheCurrentBaseline() throws Exception {
        var source = initializedSource();
        var startupSource = mock(DataSource.class, delegatesTo(source));
        new DatabaseInitializer(startupSource).run();
        verify(startupSource, times(2)).getConnection(); // WAL and validation/seed each use one connection.
        var jdbc = new JdbcTemplate(source);
        assertEquals(Set.of(
                        "agent_database_connections", "agent_ssh_connections", "agents",
                        "ai_events", "ai_messages", "ai_model_capabilities", "ai_model_capability_observations",
                        "ai_model_configs", "ai_providers", "ai_runs", "ai_subagent_invocations",
                        "ai_thread_leases", "ai_threads", "ai_turns", "ai_user_input_requests",
                        "audit_logs", "project_agents", "projects", "scan_endpoint_results", "scan_evidence",
                        "scan_fingerprint_results", "scan_observations", "scan_tasks", "system_configs", "teams", "users"),
                Set.copyOf(jdbc.queryForList(
                        "SELECT name FROM sqlite_schema WHERE type='table' AND name NOT GLOB 'sqlite_*'", String.class)));

        assertColumns(jdbc, "agents",
                "agent_id", "agent_name", "parent_agent_id", "create_by_user_id", "team_id", "conn_link", "protocol",
                "headers", "req_disguise_id", "resp_disguise_id", "payload_key", "proxy_enabled", "proxy_type",
                "proxy_host", "proxy_port", "max_req_count", "permission", "last_connected_at", "create_time",
                "update_time", "remark", "url_strategy", "padding_strategy", "header_noise_strategy",
                "tls_fingerprint_strategy", "component_class_name_strategy", "type");
        assertColumns(jdbc, "agent_database_connections",
                "connection_id", "connection_name", "agent_id", "dialect", "connection_spec", "username", "password",
                "status", "test_status", "last_test_time", "last_test_message", "timeout_seconds", "create_user_id",
                "create_time", "update_time", "description", "remark");
        assertColumns(jdbc, "ai_threads",
                "thread_id", "scope", "user_id", "agent_id", "host_id", "session_id", "title", "config_id", "config_name",
                "config_protocol", "config_model", "config_base_url", "config_completions_path", "config_max_output_tokens",
                "created_at", "last_active_at", "message_count", "run_status", "parent_thread_id", "context_summary",
                "context_checkpoint_json");
        assertColumns(jdbc, "ai_subagent_invocations",
                "invocation_id", "parent_thread_id", "child_thread_id", "task", "summary", "status",
                "created_at", "completed_at");
        assertColumns(jdbc, "ai_messages",
                "message_id", "thread_id", "turn_id", "run_id", "message_seq", "status", "role", "content",
                "recovery_context", "timestamp", "attachments_json", "nodes_json", "review_json", "plan_json");
        assertEquals(1, column(jdbc, "ai_runs", "runtime_json"));
        assertEquals(1, column(jdbc, "ai_runs", "trace_json"));

        assertExplicitIndexes(jdbc, "ai_runs", "idx_ai_runs_thread_time");
        assertExplicitIndexes(jdbc, "ai_events",
                "idx_ai_events_run_seq", "idx_ai_events_turn_started", "idx_ai_events_turn_completed");
        assertExplicitIndexes(jdbc, "scan_tasks", "idx_scan_tasks_owner_cursor", "idx_scan_tasks_owner_active_cursor");
        for (String name : Set.of("idx_ai_messages_run", "uk_ai_turns_active_thread", "uk_users_user_name_nocase")) {
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM sqlite_master WHERE type='index' AND name=?",
                    Integer.class, name), name);
        }

        assertEquals(Set.of("audit.mode", "max.file.upload.size.mb", "security.password.min.length",
                        "security.login.max.attempts", "security.login.lock.seconds"),
                Set.copyOf(jdbc.queryForList("SELECT config_key FROM system_configs", String.class)));
        assertTrue(jdbc.queryForObject(
                "SELECT COUNT(*) FROM ai_model_capabilities WHERE source='system'", Integer.class) > 0);
        for (String table : Set.of("users", "teams", "projects", "ai_providers")) {
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class), table);
        }
    }

    @Test
    void restartingSchemaAndSeedsPreservesUserData() throws Exception {
        var source = initializedSource();
        var initializer = new DatabaseInitializer(source);
        initializer.run();
        var jdbc = new JdbcTemplate(source);
        jdbc.update("UPDATE system_configs SET config_value='write' WHERE config_key='audit.mode'");
        new TransactionTemplate(new DataSourceTransactionManager(source)).executeWithoutResult(status -> {
            jdbc.update("""
                    INSERT INTO users(user_id,user_name,password,create_time,update_time,team_id)
                    VALUES ('fixture','fixture','hash',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,'team-a')
                    """);
            jdbc.update("""
                    INSERT INTO teams(team_id,team_name,leader_id,create_time,update_time)
                    VALUES ('team-a','Team A','fixture',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                    """);
        });
        jdbc.update("""
                INSERT INTO agents(agent_id,agent_name,parent_agent_id,create_by_user_id,conn_link,
                  req_disguise_id,resp_disguise_id,permission,create_time,update_time,team_id)
                VALUES ('team-agent','test','root','fixture','/','request','response','team',
                  CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,'team-a')
                """);

        runner(source).run(context -> assertNull(context.getStartupFailure()));
        initializer.run();

        assertEquals("hash", jdbc.queryForObject("SELECT password FROM users WHERE user_id='fixture'", String.class));
        assertEquals("team-a", jdbc.queryForObject("SELECT team_id FROM agents WHERE agent_id='team-agent'", String.class));
        assertEquals("write", jdbc.queryForObject("SELECT config_value FROM system_configs WHERE config_key='audit.mode'", String.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM teams", Integer.class));
        var error = assertThrows(DataAccessException.class, () -> jdbc.update("""
                INSERT INTO agents(agent_id,agent_name,parent_agent_id,create_by_user_id,conn_link,
                  req_disguise_id,resp_disguise_id,permission,create_time,update_time)
                VALUES ('invalid','test','root','fixture','/','request','response','protected',
                  CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                """));
        assertTrue(error.getCause().getMessage().contains("CHECK constraint"));
    }

    @ParameterizedTest
    @CsvSource({
            "ai_threads, context_checkpoint_json", "ai_turns, answer_to_question_id",
            "ai_user_input_requests, confirmation_consumed_at", "agents, payload_key",
            "scan_tasks, error_message", "scan_tasks, stage_json",
            "scan_endpoint_results, fingerprint_json", "scan_endpoint_results, response_size",
            "scan_fingerprint_results, rule_hash", "ai_events, item_id"
    })
    void rejectsMissingColumnsWithoutMigrating(String table, String name) {
        var source = initializedSource();
        var jdbc = new JdbcTemplate(source);
        jdbc.execute("ALTER TABLE " + table + " DROP COLUMN " + name);
        var error = assertThrows(IllegalStateException.class, () -> new DatabaseInitializer(source).run());
        assertTrue(error.getMessage().contains(table));
        assertTrue(error.getMessage().contains(name));
        assertEquals(0, column(jdbc, table, name));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void rejectsMissingOrNonNullableMessageRunIdBeforeSeeding(boolean present) {
        var source = initializedSource();
        var jdbc = new JdbcTemplate(source);
        jdbc.execute("DROP TABLE ai_messages");
        jdbc.execute("""
                CREATE TABLE ai_messages (
                    message_id TEXT PRIMARY KEY, thread_id TEXT, turn_id TEXT, message_seq INTEGER,
                    status TEXT, role TEXT, recovery_context TEXT%s
                )
                """.formatted(present ? ", run_id TEXT NOT NULL" : ""));

        var error = assertThrows(IllegalStateException.class, () -> new DatabaseInitializer(source).run());
        assertEquals(present ? "AI 数据库结构不符合约束，ai_messages.run_id 必须允许 NULL"
                : "数据库结构不完整，缺少 ai_messages.[run_id]", error.getMessage());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM system_configs", Integer.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"ai_runs", "ai_events"})
    void rejectsIncompleteDatabasesWithoutSeeding(String table) {
        var source = initializedSource();
        var jdbc = new JdbcTemplate(source);
        jdbc.execute("DROP TABLE " + table);
        var error = assertThrows(IllegalStateException.class, () -> new DatabaseInitializer(source).run());
        assertTrue(error.getMessage().contains(table));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM system_configs", Integer.class));
    }

    @Test
    void rejectsMissingTeamForeignKeyWithoutAddingIt() {
        var source = source();
        var jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE users (user_id TEXT PRIMARY KEY, team_id TEXT, account_revision INTEGER)");
        var error = assertThrows(IllegalStateException.class, () -> new DatabaseInitializer(source).run());

        assertTrue(error.getMessage().contains("users → teams"));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM pragma_foreign_key_list('users')", Integer.class));
    }

    @ParameterizedTest
    @CsvSource({"false,true,users", "true,false,teams", "false,false,users"})
    void rejectsProjectsMissingForeignKeysBeforeSeeding(boolean hasOwnerKey, boolean hasTeamKey, String missingTarget) {
        var source = initializedSource();
        var jdbc = new JdbcTemplate(source);
        jdbc.execute("DROP TABLE projects");
        jdbc.execute("""
                CREATE TABLE projects (
                    project_id TEXT PRIMARY KEY, owner_user_id TEXT%s, team_id TEXT%s
                )
                """.formatted(hasOwnerKey ? " REFERENCES users(user_id)" : "",
                hasTeamKey ? " REFERENCES teams(team_id)" : ""));

        var error = assertThrows(IllegalStateException.class, () -> new DatabaseInitializer(source).run());
        assertTrue(error.getMessage().contains("projects → " + missingTarget));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM system_configs", Integer.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"project_agents", "ai_model_configs"})
    void rejectsUnexpectedColumnsWithoutAlteringExactSchemaTables(String table) {
        var source = initializedSource();
        var jdbc = new JdbcTemplate(source);
        jdbc.execute("ALTER TABLE " + table + " ADD COLUMN unexpected_column TEXT");

        var error = assertThrows(IllegalStateException.class, () -> new DatabaseInitializer(source).run());
        assertTrue(error.getMessage().contains(table));
        assertEquals(1, column(jdbc, table, "unexpected_column"));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM system_configs", Integer.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"spring.sql.init.mode=never", "spring.sql.init.enabled=false"})
    void disabledInitializationDoesNotCreateTables(String setting) {
        var source = source();
        runner(source).withPropertyValues(setting).run(context -> {
            assertNull(context.getStartupFailure());
            if (setting.endsWith("false")) {
                assertTrue(context.getBeansOfType(SqlDataSourceScriptDatabaseInitializer.class).isEmpty());
            }
        });
        assertEquals(0, new JdbcTemplate(source).queryForObject("SELECT COUNT(*) FROM sqlite_master WHERE type='table'", Integer.class));
    }

    private int column(JdbcTemplate jdbc, String table, String name) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM pragma_table_info(?) WHERE name=?", Integer.class, table, name);
    }

    private void assertColumns(JdbcTemplate jdbc, String table, String... names) {
        assertEquals(Set.of(names),
                Set.copyOf(jdbc.queryForList("SELECT name FROM pragma_table_info(?)", String.class, table)), table);
    }

    private void assertExplicitIndexes(JdbcTemplate jdbc, String table, String... names) {
        assertEquals(Set.of(names), Set.copyOf(jdbc.queryForList(
                "SELECT name FROM sqlite_schema WHERE type='index' AND tbl_name=? AND sql IS NOT NULL",
                String.class, table)), table);
    }

    private SQLiteDataSource initializedSource() {
        var source = source();
        runner(source).run(context -> {
            assertNull(context.getStartupFailure());
            assertEquals(1, context.getBeansOfType(SqlDataSourceScriptDatabaseInitializer.class).size());
        });
        return source;
    }

    private ApplicationContextRunner runner(DataSource source) {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(SqlInitializationAutoConfiguration.class))
                .withBean(DataSource.class, () -> source)
                .withPropertyValues("spring.sql.init.mode=always", "spring.sql.init.schema-locations=classpath:sql/schema.sql");
    }

    private SQLiteDataSource source() {
        var config = new SQLiteConfig();
        config.enforceForeignKeys(true);
        var source = new SQLiteDataSource(config);
        source.setUrl("jdbc:sqlite:" + temp.resolve("test.db"));
        return source;
    }
}
