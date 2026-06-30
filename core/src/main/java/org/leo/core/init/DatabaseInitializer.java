package org.leo.core.init;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

@Component
public class DatabaseInitializer implements CommandLineRunner {

    private static final String PLACEHOLDER_KEY = "placeholder-configure-db-or-env";

    private final DataSource dataSource;

    @Value("${spring.ai.openai.api-key:}")
    private String openaiApiKey;

    public DatabaseInitializer(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void run(String... args) throws Exception {
        // 开启 WAL 模式提升并发读写性能
        enableWalMode();
        // 校验 API key 配置
        validateApiKeys();

        try (Connection conn = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(conn, new ClassPathResource("sql/schema.sql"));
        }
        if (needsSeedData()) {
            System.out.println("检测到无用户数据，写入默认团队与管理员账户...");
            try (Connection conn = dataSource.getConnection()) {
                ScriptUtils.executeSqlScript(conn, new ClassPathResource("sql/data.sql"));
            }
        }
        try (Connection conn = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(conn, new ClassPathResource("sql/ai_model_schema.sql"));
        }
        runMigrations();
    }

    private void runMigrations() {
        addColumnIfMissing("ai_messages", "plan_json", "TEXT");
        addColumnIfMissing("ai_messages", "nodes_json", "TEXT");
        addColumnIfMissing("ai_messages", "thinking_logs_json", "TEXT");
        addColumnIfMissing("ai_messages", "tool_calls_json", "TEXT");
        addColumnIfMissing("ai_messages", "review_json", "TEXT");
        addColumnIfMissing("ai_threads", "parent_thread_id", "VARCHAR(64)");
        addColumnIfMissing("ai_threads", "profile", "VARCHAR(64) NOT NULL DEFAULT 'default'");
        addColumnIfMissing("ai_threads", "mode", "VARCHAR(16) NOT NULL DEFAULT 'auto'");
        addColumnIfMissing("ai_threads", "context_summary", "TEXT");
        addColumnIfMissing("ai_threads", "root_plan_id", "VARCHAR(64)");
        ensureAiProviderTable();
        ensureAiModelConfigColumns();
        ensureAiProviderColumns();
        backfillAiProtocolColumns();
        normalizeAiModelConfigOptionalDefaults();
    }

    private void addColumnIfMissing(String table, String column, String typeDecl) {
        try (Connection conn = dataSource.getConnection()) {
            if (columnExists(conn, table, column)) return;
            try (Statement st = conn.createStatement()) {
                st.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + typeDecl);
            }
        } catch (SQLException e) {
            System.err.println("迁移失败: ALTER TABLE " + table + " ADD COLUMN " + column + " — " + e.getMessage());
        }
    }

    private void normalizeAiModelConfigOptionalDefaults() {
        try (Connection conn = dataSource.getConnection()) {
            if (!tableExists(conn, "ai_model_configs")) return;
            try (Statement st = conn.createStatement()) {
                st.executeUpdate("UPDATE ai_model_configs "
                        + "SET max_output_tokens = NULL "
                        + "WHERE max_output_tokens IS NOT NULL AND max_output_tokens <= 0");
                st.executeUpdate("UPDATE ai_model_configs "
                        + "SET context_window_tokens = NULL "
                        + "WHERE context_window_tokens IS NOT NULL AND context_window_tokens <= 0");
            }
        } catch (SQLException e) {
            System.err.println("迁移失败: normalize ai_model_configs optional defaults — " + e.getMessage());
        }
    }

    private void ensureAiModelConfigColumns() {
        addColumnIfMissing("ai_model_configs", "provider_id", "INTEGER");
        addColumnIfMissing("ai_model_configs", "provider_key", "VARCHAR(64) NOT NULL DEFAULT 'custom'");
        addColumnIfMissing("ai_model_configs", "provider_name", "VARCHAR(100)");
        addColumnIfMissing("ai_model_configs", "enabled", "INTEGER NOT NULL DEFAULT 1");
        addColumnIfMissing("ai_model_configs", "protocol", "VARCHAR(32) NOT NULL DEFAULT 'chat_completions'");
        addColumnIfMissing("ai_model_configs", "reasoning_effort", "VARCHAR(16)");
        addColumnIfMissing("ai_model_configs", "temperature", "REAL");
        addColumnIfMissing("ai_model_configs", "headers_json", "TEXT");
    }

    private void ensureAiProviderColumns() {
        addColumnIfMissing("ai_providers", "protocol", "VARCHAR(32) NOT NULL DEFAULT 'chat_completions'");
    }

    private void backfillAiProtocolColumns() {
        try (Connection conn = dataSource.getConnection()) {
            if (tableExists(conn, "ai_providers")) {
                backfillProtocol(conn, "ai_providers");
            }
            if (tableExists(conn, "ai_model_configs")) {
                backfillProtocol(conn, "ai_model_configs");
            }
        } catch (SQLException e) {
            System.err.println("迁移失败: backfill ai protocol columns — " + e.getMessage());
        }
    }

    private void backfillProtocol(Connection conn, String table) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.executeUpdate("UPDATE " + table + " "
                    + "SET protocol = 'responses' "
                    + "WHERE lower(coalesce(completions_path, '')) LIKE '%/responses%'");
            st.executeUpdate("UPDATE " + table + " "
                    + "SET protocol = 'chat_completions' "
                    + "WHERE protocol IS NULL OR trim(protocol) = ''");
        }
    }

    private void ensureAiProviderTable() {
        try (Connection conn = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(conn, new ClassPathResource("sql/ai_model_schema.sql"));
        } catch (Exception e) {
            System.err.println("迁移失败: ensure ai_providers — " + e.getMessage());
        }
    }

    private boolean tableExists(Connection conn, String table) throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT name FROM sqlite_master WHERE type='table' AND name='" + table + "'")) {
            return rs.next();
        }
    }

    private boolean columnExists(Connection conn, String table, String column) throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rs.next()) {
                if (column.equalsIgnoreCase(rs.getString("name"))) return true;
            }
        }
        return false;
    }

    private boolean needsSeedData() {
        try (Connection conn = dataSource.getConnection();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM users")) {
            return !rs.next() || rs.getLong(1) == 0;
        } catch (SQLException e) {
            return true;
        }
    }

    /**
     * 启动时校验 API key 配置，若仍为占位符则输出警告。
     * 不做硬性阻断，因为系统支持通过数据库配置 AI 渠道。
     */
    private void validateApiKeys() {
        if (isPlaceholderOrBlank(openaiApiKey)) {
            System.out.println("[WARN] OpenAI API key 未配置（环境变量 OPENAI_API_KEY 或数据库 AI 渠道）");
            System.out.println("[WARN] 如已通过数据库 AI 渠道配置，可忽略以上警告");
        }
    }

    private boolean isPlaceholderOrBlank(String key) {
        return key == null || key.isBlank() || key.contains(PLACEHOLDER_KEY);
    }

    /**
     * 开启 SQLite WAL 模式，提升并发读写性能。
     * WAL 模式下读操作不阻塞写操作，适合 Web 应用场景。
     */
    private void enableWalMode() {
        try (Connection conn = dataSource.getConnection();
             Statement st = conn.createStatement()) {
            ResultSet rs = st.executeQuery("PRAGMA journal_mode=WAL");
            if (rs.next()) {
                String mode = rs.getString(1);
                System.out.println("SQLite journal_mode: " + mode);
            }
        } catch (SQLException e) {
            System.err.println("开启 WAL 模式失败: " + e.getMessage());
        }
    }

}
