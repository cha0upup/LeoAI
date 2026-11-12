package org.leo.web.init;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.leo.core.init.DatabaseInitializer;
import org.sqlite.SQLiteDataSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.sql.init.SqlDataSourceScriptDatabaseInitializer;
import org.springframework.boot.autoconfigure.sql.init.SqlInitializationAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Covers schema creation, baseline validation and non-destructive restart in one place. */
class SchemaInitializationTest {
    @TempDir Path temp;

    @Test
    void freshSchemaMatchesTheCurrentBaseline() throws Exception {
        var source = initializedSource();
        new DatabaseInitializer(source).run();
        var jdbc = new JdbcTemplate(source);
        for (var entry : Map.of(
                "ai_messages", new String[]{"nodes_json", "recovery_context"},
                "ai_threads", new String[]{"host_id"},
                "ai_runs", new String[]{"runtime_json", "trace_json"},
                "agents", new String[]{"last_connected_at"}).entrySet()) {
            for (String name : entry.getValue()) assertEquals(1, column(jdbc, entry.getKey(), name), name);
        }
        for (var entry : Map.of(
                "agents", new String[]{"last_heartbeat", "heartbeat_interval"},
                "agent_database_connections", new String[]{"max_connections"},
                "ai_threads", new String[]{"root_plan_id", "profile"},
                "ai_subagent_invocations", new String[]{"parent_message_id", "input_json", "profile"},
                "ai_messages", new String[]{"thinking_logs_json", "tool_calls_json"}).entrySet()) {
            for (String name : entry.getValue()) assertEquals(0, column(jdbc, entry.getKey(), name), name);
        }
        for (String name : new String[]{"idx_ai_messages_run", "uk_ai_turns_active_thread",
                "uk_users_user_name_nocase", "idx_scan_tasks_owner_cursor", "idx_scan_tasks_owner_active_cursor"}) {
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM sqlite_master WHERE type='index' AND name=?",
                    Integer.class, name), name);
        }
        for (String name : new String[]{"sessions", "agent_sessions", "ai_operation_assessments",
                "idx_ai_runs_turn", "idx_ai_events_thread_seq", "idx_scan_tasks_session_cursor", "idx_scan_tasks_active_cursor"}) {
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM sqlite_master WHERE name=?", Integer.class, name), name);
        }
        assertEquals(Set.of("audit.mode", "max.file.upload.size.mb", "security.password.min.length",
                        "security.login.max.attempts", "security.login.lock.seconds"),
                Set.copyOf(jdbc.queryForList("SELECT config_key FROM system_configs", String.class)));
    }

    @Test
    void restartingSchemaAndSeedsPreservesUserData() throws Exception {
        var source = initializedSource();
        var initializer = new DatabaseInitializer(source);
        initializer.run();
        var jdbc = new JdbcTemplate(source);
        jdbc.update("UPDATE system_configs SET config_value='write' WHERE config_key='audit.mode'");
        jdbc.update("""
                INSERT INTO users(user_id,user_name,password,create_time,update_time,team_id)
                VALUES ('fixture','fixture','hash',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,'team-a')
                """);
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
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM teams", Integer.class));
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
    @ValueSource(strings = {"ai_runs", "ai_events"})
    void rejectsIncompleteDatabasesWithoutSeeding(String table) {
        var source = initializedSource();
        var jdbc = new JdbcTemplate(source);
        jdbc.execute("DROP TABLE " + table);
        var error = assertThrows(IllegalStateException.class, () -> new DatabaseInitializer(source).run());
        assertTrue(error.getMessage().contains(table));
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
        var source = new SQLiteDataSource();
        source.setUrl("jdbc:sqlite:" + temp.resolve("test.db"));
        return source;
    }
}
