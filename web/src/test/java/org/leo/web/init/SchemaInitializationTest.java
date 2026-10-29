package org.leo.web.init;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.sql.init.SqlDataSourceScriptDatabaseInitializer;
import org.springframework.boot.autoconfigure.sql.init.SqlInitializationAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** The standard Spring initializer creates the current schema directly, without upgrade beans. */
class SchemaInitializationTest {
    @TempDir Path temp;

    @Test
    void freshSchemaContainsAllCurrentColumnsAndNoRetiredMetadata() {
        var source = source("fresh.db");
        runner(source).run(context -> {
            assertNull(context.getStartupFailure());
            assertEquals(1, context.getBeansOfType(SqlDataSourceScriptDatabaseInitializer.class).size());
        });
        var jdbc = new JdbcTemplate(source);
        assertEquals(1, column(jdbc, "ai_messages", "recovery_context"));
        assertEquals(1, column(jdbc, "ai_threads", "host_id"));
        for (String name : new String[]{"host_id", "user_id", "agent_id", "project_id"}) {
            assertEquals(1, column(jdbc, "scan_tasks", name));
        }
        assertEquals(1, column(jdbc, "agents", "last_connected_at"));
        assertEquals(0, column(jdbc, "agents", "last_heartbeat"));
        assertEquals(0, column(jdbc, "agents", "heartbeat_interval"));
        assertEquals(0, column(jdbc, "agent_database_connections", "max_connections"));
        assertEquals(0, column(jdbc, "ai_threads", "root_plan_id"));
        assertEquals(0, column(jdbc, "ai_threads", "profile"));
        for (String name : new String[]{"parent_message_id", "input_json", "profile"}) {
            assertEquals(0, column(jdbc, "ai_subagent_invocations", name));
        }
        for (String name : new String[]{"thinking_logs_json", "tool_calls_json"}) {
            assertEquals(0, column(jdbc, "ai_messages", name));
        }
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM sqlite_master WHERE type='index' "
                + "AND name IN ('idx_ai_runs_turn','idx_ai_events_thread_seq')", Integer.class));
    }

    @Test
    void restartingTheCurrentSchemaDoesNotResetCurrentUserData() {
        var source = source("restart.db");
        runner(source).run(context -> assertNull(context.getStartupFailure()));
        var jdbc = new JdbcTemplate(source);
        jdbc.update("INSERT INTO users(user_id,user_name,password,create_time,update_time) VALUES ('fixture','fixture','hash',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        runner(source).run(context -> assertNull(context.getStartupFailure()));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE user_id='fixture'", Integer.class));
    }

    @Test
    void disabledModeDoesNotCreateTables() {
        var source = source("disabled-mode.db");
        runner(source).withPropertyValues("spring.sql.init.mode=never")
                .run(context -> assertNull(context.getStartupFailure()));
        assertEquals(0, new JdbcTemplate(source).queryForObject("SELECT COUNT(*) FROM sqlite_master WHERE type='table'", Integer.class));
    }

    @Test
    void disabledInitializationDoesNotRegisterAnInitializer() {
        var source = source("disabled.db");
        runner(source).withPropertyValues("spring.sql.init.enabled=false").run(context -> {
            assertNull(context.getStartupFailure());
            assertTrue(context.getBeansOfType(SqlDataSourceScriptDatabaseInitializer.class).isEmpty());
        });
    }

    private int column(JdbcTemplate jdbc, String table, String name) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM pragma_table_info(?) WHERE name=?", Integer.class, table, name);
    }

    private ApplicationContextRunner runner(DataSource source) {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(SqlInitializationAutoConfiguration.class))
                .withBean(DataSource.class, () -> source)
                .withPropertyValues("spring.sql.init.mode=always", "spring.sql.init.schema-locations=classpath:sql/schema.sql");
    }

    private SQLiteDataSource source(String filename) {
        var source = new SQLiteDataSource();
        source.setUrl("jdbc:sqlite:" + temp.resolve(filename));
        return source;
    }
}
