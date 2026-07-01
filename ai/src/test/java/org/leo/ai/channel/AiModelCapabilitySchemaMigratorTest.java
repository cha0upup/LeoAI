package org.leo.ai.channel;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiModelCapabilitySchemaMigratorTest {

    @Test
    void collapsesCompositeCapabilityKeysToModelOnlyLibrary() throws Exception {
        JdbcTemplate jdbc = jdbc();
        createCanonicalCapabilityTable(jdbc);
        jdbc.update("""
                INSERT INTO ai_model_capabilities
                (provider_key, model_name, source, context_window_tokens, max_output_tokens,
                 supports_text_generation, supports_reasoning, supports_streaming, supports_function_calling,
                 supports_structured_output, supports_web_search, supports_parallel_tool_calls, remark)
                VALUES
                ('DeepSeek', 'DeepSeek-V4-FLASH', 'system', 1000000, 384000, 1, 1, 1, 1, 1, 0, 0, 'system row')
                """);
        jdbc.update("""
                INSERT INTO ai_model_capabilities
                (provider_key, model_name, source, context_window_tokens, max_output_tokens,
                 supports_text_generation, supports_reasoning, supports_streaming, supports_function_calling,
                 supports_structured_output, supports_web_search, supports_parallel_tool_calls, remark)
                VALUES
                ('deepseek', 'deepseek-v4-flash', 'manual', 8192, 0, 0, 0, 0, 0, 0, 0, 0, 'manual row')
                """);

        new AiModelCapabilitySchemaMigrator(jdbc).migrate();

        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM ai_model_capabilities");
        assertEquals(1, rows.size());
        Map<String, Object> row = rows.get(0);
        assertEquals("deepseek-v4-flash", row.get("model_name"));
        assertEquals("system", row.get("source"));
        assertEquals(1000000, ((Number) row.get("context_window_tokens")).intValue());
        assertEquals(384000, ((Number) row.get("max_output_tokens")).intValue());
        assertEquals(1, ((Number) row.get("supports_text_generation")).intValue());
        List<Map<String, Object>> columns = jdbc.queryForList("PRAGMA table_info(ai_model_capabilities)");
        assertTrue(columns.stream().noneMatch(column -> "provider_key".equals(column.get("name"))));
    }

    @Test
    void migratesLegacySingleModelPrimaryKeyTable() throws Exception {
        JdbcTemplate jdbc = jdbc();
        jdbc.execute("""
                CREATE TABLE ai_model_capabilities (
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
        jdbc.update("""
                INSERT INTO ai_model_capabilities
                (model_name, source, context_window_tokens, max_output_tokens,
                 supports_text_generation, supports_reasoning, supports_streaming, supports_function_calling,
                 supports_structured_output, supports_web_search, supports_parallel_tool_calls, remark)
                VALUES ('GPT-4O', 'system', 128000, 16384, 1, 0, 1, 1, 1, 0, 1, 'legacy')
                """);

        new AiModelCapabilitySchemaMigrator(jdbc).migrate();

        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM ai_model_capabilities");
        assertEquals("gpt-4o", row.get("model_name"));
        List<Map<String, Object>> pk = jdbc.queryForList("PRAGMA table_info(ai_model_capabilities)");
        assertTrue(pk.stream().noneMatch(column -> "provider_key".equals(column.get("name"))));
        assertTrue(pk.stream().anyMatch(column ->
                "model_name".equals(column.get("name")) && ((Number) column.get("pk")).intValue() > 0));
    }

    @Test
    void addsRuntimeSnapshotColumnWhenAiRunsAlreadyExists() throws Exception {
        JdbcTemplate jdbc = jdbc();
        jdbc.execute("CREATE TABLE ai_runs (id INTEGER PRIMARY KEY)");

        new AiModelCapabilitySchemaMigrator(jdbc).migrate();

        List<Map<String, Object>> columns = jdbc.queryForList("PRAGMA table_info(ai_runs)");
        assertTrue(columns.stream().anyMatch(column -> "runtime_json".equals(column.get("name"))));
    }

    private static JdbcTemplate jdbc() throws Exception {
        Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        return new JdbcTemplate(new SingleConnectionDataSource(connection, true));
    }

    private static void createCanonicalCapabilityTable(JdbcTemplate jdbc) {
        jdbc.execute("""
                CREATE TABLE ai_model_capabilities (
                    provider_key VARCHAR(64) NOT NULL DEFAULT 'custom',
                    model_name VARCHAR(255) NOT NULL,
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
                    remark TEXT,
                    PRIMARY KEY (provider_key, model_name)
                )
                """);
    }
}
