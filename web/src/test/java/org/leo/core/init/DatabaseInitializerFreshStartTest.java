package org.leo.core.init;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatabaseInitializerFreshStartTest {

    @TempDir
    Path tempDir;

    @Test
    void createsCurrentSchemaAndCanRunTwice() throws Exception {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + tempDir.resolve("fresh.db"));
        DatabaseInitializer initializer = new DatabaseInitializer(dataSource);

        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("sql/schema.sql"));
        }
        initializer.run();
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO users
                      (user_id, user_name, password, privilege, status,
                       create_time, update_time, team_id)
                    VALUES ('owner-1', 'owner-1', 'hash', 'normal', 1,
                            CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'team-a')
                    """);
            statement.executeUpdate("""
                    INSERT INTO puppets
                      (puppet_id, puppet_name, parent_puppet_id, create_by_user_id,
                       conn_link, req_disguise_id, resp_disguise_id, permission,
                       create_time, update_time, team_id)
                    VALUES ('team-puppet', 'team-puppet', 'root', 'owner-1', '/',
                            'request', 'response', 'team',
                            CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'team-a')
                    """);
        }
        initializer.run();

        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            assertEquals(1, scalar(statement,
                    "SELECT COUNT(*) FROM pragma_table_info('ai_messages') WHERE name='nodes_json'"));
            assertEquals(1, scalar(statement,
                    "SELECT COUNT(*) FROM pragma_table_info('ai_runs') WHERE name='runtime_json'"));
            assertEquals(1, scalar(statement,
                    "SELECT COUNT(*) FROM pragma_table_info('ai_runs') WHERE name='trace_id'"));
            assertEquals(1, scalar(statement,
                    "SELECT COUNT(*) FROM pragma_table_info('ai_runs') WHERE name='trace_json'"));
            assertEquals(1, scalar(statement,
                    "SELECT COUNT(*) FROM pragma_table_info('ai_messages') WHERE name='turn_id'"));
            assertEquals(1, scalar(statement,
                    "SELECT COUNT(*) FROM pragma_table_info('ai_messages') WHERE name='run_id'"));
            assertEquals(1, scalar(statement,
                    "SELECT COUNT(*) FROM pragma_table_info('ai_messages') WHERE name='message_seq'"));
            assertEquals(1, scalar(statement,
                    "SELECT COUNT(*) FROM pragma_table_info('ai_messages') WHERE name='status'"));
            assertEquals(1, scalar(statement,
                    "SELECT COUNT(*) FROM sqlite_master WHERE type='index' "
                            + "AND name='idx_ai_messages_run'"));
            assertEquals(1, scalar(statement,
                    "SELECT COUNT(*) FROM pragma_table_info('ai_threads') "
                            + "WHERE name='context_checkpoint_json'"));
            assertEquals(1, scalar(statement,
                    "SELECT COUNT(*) FROM pragma_table_info('ai_runs') WHERE name='turn_id'"));
            assertEquals(1, scalar(statement,
                    "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='ai_turns'"));
            assertEquals(1, scalar(statement,
                    "SELECT COUNT(*) FROM pragma_table_info('ai_turns') "
                            + "WHERE name='client_user_message_id'"));
            assertEquals(1, scalar(statement,
                    "SELECT COUNT(*) FROM sqlite_master WHERE type='index' "
                            + "AND name='uk_ai_turns_active_thread'"));
            assertEquals(1, scalar(statement,
                    "SELECT COUNT(*) FROM sqlite_master WHERE type='table' "
                            + "AND name='ai_thread_leases'"));
            assertEquals(1, scalar(statement,
                    "SELECT COUNT(*) FROM sqlite_master WHERE type='index' AND name='uk_users_user_name_nocase'"));
            assertEquals(1, scalar(statement,
                    "SELECT COUNT(*) FROM system_configs WHERE config_key='system.version' AND config_value='1.0.0'"));
            assertEquals(1, scalar(statement,
                    "SELECT COUNT(*) FROM puppets WHERE puppet_id='team-puppet' AND team_id='team-a'"));

            SQLException rejected = assertThrows(SQLException.class, () -> statement.executeUpdate("""
                    INSERT INTO puppets
                      (puppet_id, puppet_name, parent_puppet_id, create_by_user_id, conn_link,
                       req_disguise_id, resp_disguise_id, permission, create_time, update_time)
                    VALUES ('p-1', 'test', 'root', 'admin', '/', 'request', 'response', 'protected',
                            CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                    """));
            assertTrue(rejected.getMessage().contains("CHECK constraint"));
        }
    }

    @ParameterizedTest
    @CsvSource({
            "ai_threads, context_checkpoint_json",
            "ai_turns, answer_to_question_id",
            "ai_user_input_requests, confirmation_consumed_at",
            "puppets, payload_key",
            "ai_operation_assessments, arguments_json",
            "scan_tasks, error_message",
            "scan_tasks, stage_json",
            "scan_endpoint_results, fingerprint_json",
            "scan_endpoint_results, response_size"
    })
    void rejectsMissingColumnsWithoutMigrating(String table, String column) throws Exception {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + tempDir.resolve("missing-column.db"));
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("sql/schema.sql"));
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("ALTER TABLE " + table + " DROP COLUMN " + column);
            }
        }

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> new DatabaseInitializer(dataSource).run());
        assertTrue(error.getMessage().contains(table));
        assertTrue(error.getMessage().contains(column));
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            assertEquals(0, scalar(statement, "SELECT COUNT(*) FROM pragma_table_info('"
                    + table + "') WHERE name='" + column + "'"));
        }
    }

    @Test
    void rejectsIncompleteScanSchemaBeforeRecovery() throws Exception {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + tempDir.resolve("incomplete-scan-schema.db"));
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("sql/schema.sql"));
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("ALTER TABLE scan_fingerprint_results DROP COLUMN rule_hash");
            }
        }

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> new DatabaseInitializer(dataSource).run());
        assertTrue(error.getMessage().contains("scan_fingerprint_results"));
        assertTrue(error.getMessage().contains("rule_hash"));
    }

    @Test
    void rejectsIncompleteAiSchema() throws Exception {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + tempDir.resolve("incomplete-schema.db"));
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    CREATE TABLE ai_messages (
                        message_id VARCHAR(64) PRIMARY KEY,
                        thread_id VARCHAR(64) NOT NULL,
                        role VARCHAR(32) NOT NULL
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE ai_runs (
                        run_id VARCHAR(64) PRIMARY KEY,
                        thread_id VARCHAR(64) NOT NULL,
                        status VARCHAR(32) NOT NULL
                    )
                    """);
        }

        IllegalStateException error = assertThrows(
                IllegalStateException.class, () -> new DatabaseInitializer(dataSource).run());

        assertTrue(error.getMessage().contains("数据库结构不完整"));
    }

    @Test
    void rejectsIncompleteEventJournal() throws Exception {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + tempDir.resolve("incomplete-events.db"));
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(
                    connection, new ClassPathResource("sql/schema.sql"));
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("DROP TABLE ai_events");
                statement.executeUpdate("""
                        CREATE TABLE ai_events (
                            event_id VARCHAR(64) PRIMARY KEY,
                            run_id VARCHAR(64),
                            thread_id VARCHAR(64) NOT NULL,
                            event_seq INTEGER NOT NULL,
                            timestamp INTEGER NOT NULL,
                            name VARCHAR(64) NOT NULL,
                            data_json TEXT
                        )
                        """);
            }
        }

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> new DatabaseInitializer(dataSource).run());

        assertTrue(error.getMessage().contains("数据库结构不完整"));
    }

    private int scalar(Statement statement, String sql) throws SQLException {
        try (ResultSet result = statement.executeQuery(sql)) {
            assertTrue(result.next());
            return result.getInt(1);
        }
    }
}
