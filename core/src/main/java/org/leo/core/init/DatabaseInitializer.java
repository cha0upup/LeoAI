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
        validateSchema();
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

    /** 校验当前数据库基线，不迁移历史结构或数据。 */
    private void validateSchema() {
        try (Connection connection = dataSource.getConnection()) {
            requireColumns(connection, "puppets",
                    Set.of("puppet_id", "create_by_user_id", "team_id", "permission", "payload_key"));
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
            requireColumns(connection, "scan_tasks",
                    Set.of("task_id", "session_id", "status", "outcome",
                            "error_message", "stage_json"));
            requireColumns(connection, "scan_endpoint_results",
                    Set.of("task_id", "endpoint_id", "fingerprint_json", "response_size"));
            requireColumns(connection, "scan_fingerprint_results",
                    Set.of("task_id", "match_key", "endpoint_id", "target_id", "rule_id",
                            "rule_hash", "rule_name", "status", "result_json", "updated_at"));
        } catch (SQLException error) {
            throw new IllegalStateException("校验数据库结构失败", error);
        }
    }

    private void requireColumns(Connection connection, String table,
                                Set<String> requiredColumns) throws SQLException {
        Set<String> actual = tableColumns(connection, table);
        if (!actual.containsAll(requiredColumns)) {
            Set<String> missing = new HashSet<>(requiredColumns);
            missing.removeAll(actual);
            throw new IllegalStateException(
                    "数据库结构不完整，缺少 " + table + "." + missing);
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
