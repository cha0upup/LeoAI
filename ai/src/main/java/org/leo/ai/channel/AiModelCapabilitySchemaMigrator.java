package org.leo.ai.channel;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 兼容旧版能力库表结构。
 *
 * <p>能力库描述模型自身能力，与供应商无关。旧版表如果存在 {@code provider_key}，
 * 会在启动时折叠为以 {@code model_name} 为主键的开发者维护能力库。
 */
@Component
public class AiModelCapabilitySchemaMigrator {

    private static final Logger log = LoggerFactory.getLogger(AiModelCapabilitySchemaMigrator.class);

    private final JdbcTemplate jdbcTemplate;

    public AiModelCapabilitySchemaMigrator(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostConstruct
    public void migrate() {
        ensureAiRunsRuntimeColumn();
        if (!tableExists("ai_model_capabilities")) return;
        List<Map<String, Object>> columns = jdbcTemplate.queryForList("PRAGMA table_info(ai_model_capabilities)");
        boolean providerKeyInPrimaryKey = isPrimaryKeyColumn(columns, "provider_key");
        boolean modelNameInPrimaryKey = isPrimaryKeyColumn(columns, "model_name");
        boolean modelNameOnlyPrimaryKey = modelNameInPrimaryKey && !providerKeyInPrimaryKey;
        if (modelNameOnlyPrimaryKey && hasCanonicalColumns(columns) && !hasColumn(columns, "provider_key")
                && !needsNormalization()) {
            ensureIndexes();
            return;
        }

        log.info("迁移 AI 模型能力库表为 model_name 单主键");
        rebuildCapabilityTable(columns);
        ensureIndexes();
    }

    private void rebuildCapabilityTable(List<Map<String, Object>> columns) {
        String modelNameSelect = "lower(trim(model_name))";
        String sourceSelect = textSelect(columns, "source", "'system'");
        String updateTimeSelect = textSelect(columns, "update_time", "CURRENT_TIMESTAMP");
        jdbcTemplate.execute("DROP TABLE IF EXISTS ai_model_capabilities_new");
        jdbcTemplate.execute("""
                CREATE TABLE ai_model_capabilities_new (
                    model_name VARCHAR(255) PRIMARY KEY,
                    source VARCHAR(32) NOT NULL DEFAULT 'system',
                    context_window_tokens INTEGER NOT NULL,
                    max_output_tokens INTEGER NOT NULL,
                    supports_text_generation INTEGER NOT NULL DEFAULT 1,
                    supports_reasoning INTEGER NOT NULL DEFAULT 0,
                    supports_streaming INTEGER NOT NULL DEFAULT 1,
                    supports_function_calling INTEGER NOT NULL DEFAULT 0,
                    supports_structured_output INTEGER NOT NULL DEFAULT 0,
                    supports_web_search INTEGER NOT NULL DEFAULT 0,
                    supports_parallel_tool_calls INTEGER NOT NULL DEFAULT 1,
                    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    remark TEXT
                )
                """);
        jdbcTemplate.execute("""
                INSERT OR IGNORE INTO ai_model_capabilities_new
                (model_name, source, context_window_tokens, max_output_tokens,
                 supports_text_generation, supports_reasoning, supports_streaming, supports_function_calling,
                 supports_structured_output, supports_web_search, supports_parallel_tool_calls,
                 create_time, update_time, remark)
                SELECT
                 %s,
                 %s,
                 %s, %s,
                 %s, %s, %s, %s,
                 %s, %s, %s,
                 %s, %s, %s
                FROM ai_model_capabilities
                WHERE model_name IS NOT NULL AND trim(model_name) <> ''
                ORDER BY %s,
                 CASE %s WHEN 'system' THEN 0 WHEN 'official' THEN 1 WHEN 'manual' THEN 2 ELSE 3 END,
                 %s DESC
                """.formatted(
                modelNameSelect,
                sourceSelect,
                positiveIntegerSelect(columns, "context_window_tokens", 32_768),
                nonNegativeIntegerSelect(columns, "max_output_tokens", 4_096),
                flagSelect(columns, "supports_text_generation", 1),
                flagSelect(columns, "supports_reasoning", 0),
                flagSelect(columns, "supports_streaming", 1),
                flagSelect(columns, "supports_function_calling", 0),
                flagSelect(columns, "supports_structured_output", 0),
                flagSelect(columns, "supports_web_search", 0),
                flagSelect(columns, "supports_parallel_tool_calls", 1),
                textSelect(columns, "create_time", "CURRENT_TIMESTAMP"),
                updateTimeSelect,
                hasColumn(columns, "remark") ? "remark" : "NULL",
                modelNameSelect, sourceSelect, updateTimeSelect));
        jdbcTemplate.execute("DROP TABLE ai_model_capabilities");
        jdbcTemplate.execute("ALTER TABLE ai_model_capabilities_new RENAME TO ai_model_capabilities");
    }

    private boolean tableExists(String tableName) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = ?
                """, Integer.class, tableName);
        return count != null && count > 0;
    }

    private static boolean isPrimaryKeyColumn(List<Map<String, Object>> columns, String columnName) {
        for (Map<String, Object> column : columns) {
            Object name = column.get("name");
            Object pk = column.get("pk");
            if (columnName.equalsIgnoreCase(String.valueOf(name)) && toInt(pk) > 0) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasColumn(List<Map<String, Object>> columns, String columnName) {
        for (Map<String, Object> column : columns) {
            if (columnName.equalsIgnoreCase(String.valueOf(column.get("name")))) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasCanonicalColumns(List<Map<String, Object>> columns) {
        return hasColumn(columns, "model_name")
                && hasColumn(columns, "source")
                && hasColumn(columns, "context_window_tokens")
                && hasColumn(columns, "max_output_tokens")
                && hasColumn(columns, "supports_text_generation")
                && hasColumn(columns, "supports_reasoning")
                && hasColumn(columns, "supports_streaming")
                && hasColumn(columns, "supports_function_calling")
                && hasColumn(columns, "supports_structured_output")
                && hasColumn(columns, "supports_web_search")
                && hasColumn(columns, "supports_parallel_tool_calls")
                && hasColumn(columns, "create_time")
                && hasColumn(columns, "update_time")
                && hasColumn(columns, "remark");
    }

    private boolean needsNormalization() {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM ai_model_capabilities
                WHERE model_name IS NULL
                   OR trim(model_name) = ''
                   OR model_name <> lower(trim(model_name))
                """, Integer.class);
        return count != null && count > 0;
    }

    private static String textSelect(List<Map<String, Object>> columns, String columnName, String fallback) {
        if (!hasColumn(columns, columnName)) return fallback;
        return "COALESCE(NULLIF(trim(%s), ''), %s)".formatted(columnName, fallback);
    }

    private static String positiveIntegerSelect(List<Map<String, Object>> columns, String columnName, int fallback) {
        if (!hasColumn(columns, columnName)) return String.valueOf(fallback);
        return "CASE WHEN %s > 0 THEN %s ELSE %d END".formatted(columnName, columnName, fallback);
    }

    private static String nonNegativeIntegerSelect(List<Map<String, Object>> columns, String columnName, int fallback) {
        if (!hasColumn(columns, columnName)) return String.valueOf(fallback);
        return "CASE WHEN %s >= 0 THEN %s ELSE %d END".formatted(columnName, columnName, fallback);
    }

    private static String flagSelect(List<Map<String, Object>> columns, String columnName, int defaultValue) {
        if (!hasColumn(columns, columnName)) return String.valueOf(defaultValue);
        return "CASE WHEN %s > 0 THEN 1 ELSE 0 END".formatted(columnName);
    }

    private static int toInt(Object value) {
        if (value instanceof Number n) return n.intValue();
        if (value == null) return 0;
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private void ensureIndexes() {
        jdbcTemplate.execute("""
                CREATE INDEX IF NOT EXISTS idx_ai_model_capabilities_model_name
                ON ai_model_capabilities(model_name)
                """);
    }

    private void ensureAiRunsRuntimeColumn() {
        if (!tableExists("ai_runs")) return;
        if (hasColumn("ai_runs", "runtime_json")) return;
        log.info("为 ai_runs 添加 runtime_json 运行时快照列");
        jdbcTemplate.execute("ALTER TABLE ai_runs ADD COLUMN runtime_json TEXT");
    }

    private boolean hasColumn(String tableName, String columnName) {
        List<Map<String, Object>> columns = jdbcTemplate.queryForList("PRAGMA table_info(" + tableName + ")");
        for (Map<String, Object> column : columns) {
            if (columnName.equalsIgnoreCase(String.valueOf(column.get("name")))) {
                return true;
            }
        }
        return false;
    }
}
