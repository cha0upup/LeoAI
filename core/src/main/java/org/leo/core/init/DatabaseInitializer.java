package org.leo.core.init;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;

/** Ensures the current database schema, indexes, journal mode and seed data. */
@Component
@Order(0)
public class DatabaseInitializer implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DatabaseInitializer.class);
    private final DataSource dataSource;

    public DatabaseInitializer(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void run(String... args) throws Exception {
        enableWalMode();
        reconcileAndValidateSchema();
        if (needsSeedData()) {
            log.info("检测到全新数据库，写入默认团队与基础配置；管理员账户由安全引导流程创建...");
            executeScript("sql/data.sql");
        }
    }

    private void executeScript(String resource) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource(resource));
        }
    }

    private boolean needsSeedData() {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM users")) {
            return !result.next() || result.getLong(1) == 0;
        } catch (SQLException error) {
            throw new IllegalStateException("检查数据库初始化状态失败", error);
        }
    }

    private void enableWalMode() {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("PRAGMA journal_mode=WAL")) {
            if (result.next()) {
                log.info("SQLite journal_mode: {}", result.getString(1));
            }
        } catch (SQLException error) {
            log.warn("开启 WAL 模式失败: {}", error.getMessage());
        }
    }

    /** 补齐允许在线添加的结构，并校验其余数据库约束。 */
    private void reconcileAndValidateSchema() {
        try (Connection connection = dataSource.getConnection()) {
            ensureColumn(connection, "ai_threads", "context_checkpoint_json", "TEXT");
            ensureColumn(connection, "ai_turns", "answer_to_question_id", "VARCHAR(64)");
            ensureUserInputRequestTable(connection);
            ensureColumn(connection, "ai_user_input_requests",
                    "confirmation_consumed_at", "INTEGER");
            ensureColumn(connection, "puppets", "payload_key", "TEXT");
            // 旧版本已存在评估表但没有保存规范化参数；默认空对象仅用于完成结构迁移，
            // 其旧哈希不会匹配新的实际调用，待处理评估会要求模型重新评估。
            ensureColumn(connection, "ai_operation_assessments", "arguments_json",
                    "TEXT NOT NULL DEFAULT '{}' ");
            requireColumns(connection, "puppets",
                    Set.of("puppet_id", "create_by_user_id", "team_id", "permission"));
            normalizePuppetTeamOwnership(connection);
            requireColumns(connection, "ai_threads",
                    Set.of("thread_id", "context_summary", "context_checkpoint_json"));
            requireColumns(connection, "ai_turns",
                    Set.of("turn_id", "thread_id", "status", "created_at", "completed_at",
                            "protocol_status", "dispatch_status", "command_scope",
                            "command_json", "client_user_message_id",
                            "user_item_id", "assistant_item_id", "started_at",
                            "interrupt_requested", "error_message",
                            "answer_to_question_id"));
            requireColumns(connection, "ai_runs",
                    Set.of("run_id", "thread_id", "turn_id", "status",
                            "error_category", "raw_error_message",
                            "trace_id", "trace_json", "lease_token"));
            requireColumns(connection, "ai_messages",
                    Set.of("message_id", "thread_id", "turn_id", "run_id",
                            "message_seq", "status", "role"));
            requireNullableColumn(connection, "ai_messages", "run_id");
            requireColumns(connection, "ai_events",
                    Set.of("event_id", "thread_id", "run_id", "turn_id", "item_id",
                            "subagent_invocation_id", "event_seq", "timestamp",
                            "name", "data_json"));
            requireColumns(connection, "ai_thread_leases",
                    Set.of("thread_id", "owner_id", "lease_token", "acquired_at",
                            "heartbeat_at", "expires_at"));
            requireColumns(connection, "ai_user_input_requests",
                    Set.of("request_id", "thread_id", "turn_id", "item_id",
                            "request_type", "prompt", "options_json",
                            "allow_free_text", "action_summary", "tool_name",
                            "arguments_hash", "risk", "status", "answer",
                            "created_at", "answered_at", "confirmation_consumed_at", "expires_at"));
            requireColumns(connection, "ai_operation_assessments",
                    Set.of("assessment_id", "user_id", "thread_id", "tool_name",
                            "arguments_json", "arguments_hash", "risk_level",
                            "requires_confirmation", "reason", "impact", "rollback",
                            "status", "created_at", "expires_at", "consumed_at"));
        } catch (SQLException error) {
            throw new IllegalStateException("校验 AI 对话数据库结构失败", error);
        }
    }

    /** 让团队可见 Puppet 的团队归属与创建者保持一致。 */
    private void normalizePuppetTeamOwnership(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            int updated = statement.executeUpdate("""
                    UPDATE puppets
                    SET team_id = (
                        SELECT users.team_id
                        FROM users
                        WHERE users.user_id = puppets.create_by_user_id
                    )
                    WHERE permission = 'team'
                      AND (team_id IS NULL OR TRIM(team_id) = '')
                      AND EXISTS (
                          SELECT 1
                          FROM users
                          WHERE users.user_id = puppets.create_by_user_id
                            AND users.team_id IS NOT NULL
                            AND TRIM(users.team_id) <> ''
                      )
                    """);
            if (updated > 0) {
                log.info("已补齐 {} 个 Puppet 的团队归属", updated);
            }
        }
    }

    private void ensureUserInputRequestTable(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS ai_user_input_requests (
                        request_id VARCHAR(64) PRIMARY KEY,
                        thread_id VARCHAR(64) NOT NULL,
                        turn_id VARCHAR(64),
                        item_id VARCHAR(64),
                        request_type VARCHAR(32) NOT NULL,
                        prompt TEXT NOT NULL,
                        options_json TEXT,
                        allow_free_text INTEGER NOT NULL DEFAULT 1,
                        action_summary TEXT,
                        tool_name VARCHAR(128),
                        arguments_hash VARCHAR(128),
                        risk VARCHAR(32),
                        status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
                        answer TEXT,
                        created_at INTEGER NOT NULL,
                        answered_at INTEGER,
                        confirmation_consumed_at INTEGER,
                        expires_at INTEGER,
                        FOREIGN KEY (thread_id) REFERENCES ai_threads(thread_id) ON DELETE CASCADE,
                        FOREIGN KEY (turn_id) REFERENCES ai_turns(turn_id) ON DELETE SET NULL,
                        FOREIGN KEY (item_id) REFERENCES ai_messages(message_id) ON DELETE SET NULL
                    )
                    """);
            statement.executeUpdate("""
                    CREATE UNIQUE INDEX IF NOT EXISTS uk_ai_user_input_pending_thread
                    ON ai_user_input_requests(thread_id) WHERE status = 'PENDING'
                    """);
            statement.executeUpdate("""
                    CREATE INDEX IF NOT EXISTS idx_ai_user_input_thread_time
                    ON ai_user_input_requests(thread_id, created_at)
                    """);
        }
    }

    /** 幂等补齐允许在线添加的 nullable 字段。 */
    private void ensureColumn(Connection connection,
                              String table,
                              String column,
                              String definition) throws SQLException {
        Set<String> existingColumns = tableColumns(connection, table);
        if (existingColumns.isEmpty() || existingColumns.contains(column)) return;
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("ALTER TABLE " + table
                    + " ADD COLUMN " + column + " " + definition);
        }
        log.info("数据库字段已补齐: {}.{}", table, column);
    }

    private void requireColumns(Connection connection, String table,
                                Set<String> requiredColumns) throws SQLException {
        Set<String> actual = tableColumns(connection, table);
        if (!actual.containsAll(requiredColumns)) {
            Set<String> missing = new HashSet<>(requiredColumns);
            missing.removeAll(actual);
            throw new IllegalStateException(
                    "AI 数据库结构不完整，缺少 " + table + "." + missing);
        }
    }

    private void requireNullableColumn(Connection connection,
                                       String table,
                                       String column) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "PRAGMA table_info('" + table + "')")) {
            while (result.next()) {
                if (!column.equals(result.getString("name"))) continue;
                if (result.getInt("notnull") == 0) return;
                throw new IllegalStateException(
                        "AI 数据库结构不符合约束，" + table + "." + column + " 必须允许 NULL");
            }
        }
    }

    private Set<String> tableColumns(Connection connection, String table)
            throws SQLException {
        Set<String> actual = new HashSet<>();
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("PRAGMA table_info('" + table + "')")) {
            while (result.next()) {
                actual.add(result.getString("name"));
            }
        }
        return actual;
    }

}
