CREATE TABLE IF NOT EXISTS ai_providers (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    name VARCHAR(100) NOT NULL UNIQUE,
    provider_key VARCHAR(64) NOT NULL DEFAULT 'custom',
    api_key TEXT NOT NULL,
    base_url TEXT NOT NULL,
    protocol VARCHAR(32) NOT NULL DEFAULT 'chat_completions',
    completions_path VARCHAR(255) NOT NULL DEFAULT '/v1/chat/completions',
    headers_json TEXT,
    enabled INTEGER NOT NULL DEFAULT 1,
    create_time DATETIME NOT NULL,
    update_time DATETIME NOT NULL,
    remark TEXT
);

-- AI 模型配置（ccswitch 风格：name + base_url + api_key + model 一体）
CREATE TABLE IF NOT EXISTS ai_model_configs (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    provider_id INTEGER,
    name VARCHAR(100) NOT NULL UNIQUE,
    provider_key VARCHAR(64) NOT NULL DEFAULT 'custom',
    provider_name VARCHAR(100),
    api_key TEXT NOT NULL,
    base_url TEXT NOT NULL,
    model VARCHAR(255) NOT NULL,
    protocol VARCHAR(32) NOT NULL DEFAULT 'chat_completions',
    completions_path VARCHAR(255) NOT NULL DEFAULT '/v1/chat/completions',
    is_active INTEGER NOT NULL DEFAULT 0,
    enabled INTEGER NOT NULL DEFAULT 1,
    max_output_tokens INTEGER,
    thinking_enabled INTEGER,
    reasoning_effort VARCHAR(16),
    context_window_tokens INTEGER,
    temperature REAL,
    headers_json TEXT,
    create_time DATETIME NOT NULL,
    update_time DATETIME NOT NULL,
    remark TEXT
);

CREATE TABLE IF NOT EXISTS ai_model_capabilities (
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
);

CREATE INDEX IF NOT EXISTS idx_ai_model_capabilities_model_name
ON ai_model_capabilities(model_name);

DELETE FROM ai_model_capabilities
WHERE source = 'system'
  AND model_name NOT IN (
    'deepseek-v4-flash', 'deepseek-v4-pro',
    'gpt-5.5', 'gpt5.5', 'gpt-5.4', 'gpt5.4',
    'glm-5.2', 'glm5.2', 'glm-5.1', 'glm5.1',
    'mimo-v2.5-pro', 'mimo2.5pro', 'mimo-v2.5-flash',
    'qwen3-max', 'qwen3-coder',
    'gemini-2.5-pro', 'gemini-2.5-flash'
  );

INSERT OR IGNORE INTO ai_model_capabilities
(model_name, source, context_window_tokens, max_output_tokens,
 supports_text_generation, supports_reasoning, supports_streaming, supports_function_calling,
 supports_structured_output, supports_web_search, supports_parallel_tool_calls, remark)
VALUES
('deepseek-v4-flash', 'system', 1000000, 384000, 1, 1, 1, 1, 1, 0, 1, '系统内置能力库'),
('deepseek-v4-pro', 'system', 1000000, 384000, 1, 1, 1, 1, 1, 0, 1, '系统内置能力库'),
('gpt-5.5', 'system', 400000, 128000, 1, 1, 1, 1, 1, 1, 1, '系统内置能力库'),
('gpt5.5', 'system', 400000, 128000, 1, 1, 1, 1, 1, 1, 1, '系统内置能力库'),
('gpt-5.4', 'system', 400000, 128000, 1, 1, 1, 1, 1, 1, 1, '系统内置能力库'),
('gpt5.4', 'system', 400000, 128000, 1, 1, 1, 1, 1, 1, 1, '系统内置能力库'),
('glm-5.2', 'system', 256000, 64000, 1, 1, 1, 1, 1, 1, 1, '系统内置能力库'),
('glm5.2', 'system', 256000, 64000, 1, 1, 1, 1, 1, 1, 1, '系统内置能力库'),
('glm-5.1', 'system', 256000, 64000, 1, 1, 1, 1, 1, 1, 1, '系统内置能力库'),
('glm5.1', 'system', 256000, 64000, 1, 1, 1, 1, 1, 1, 1, '系统内置能力库'),
('mimo-v2.5-pro', 'system', 1000000, 128000, 1, 1, 1, 1, 1, 1, 1, '系统内置能力库'),
('mimo2.5pro', 'system', 1000000, 128000, 1, 1, 1, 1, 1, 1, 1, '系统内置能力库'),
('mimo-v2.5-flash', 'system', 256000, 32000, 1, 1, 1, 1, 1, 0, 1, '系统内置能力库'),
('qwen3-max', 'system', 262000, 32000, 1, 1, 1, 1, 1, 0, 1, '系统内置能力库'),
('qwen3-coder', 'system', 262000, 32000, 1, 1, 1, 1, 1, 0, 1, '系统内置能力库'),
('gemini-2.5-pro', 'system', 1000000, 65536, 1, 1, 1, 1, 1, 1, 1, '系统内置能力库'),
('gemini-2.5-flash', 'system', 1000000, 65536, 1, 0, 1, 1, 1, 1, 1, '系统内置能力库');

UPDATE ai_model_capabilities
SET context_window_tokens = 1000000,
    max_output_tokens = 384000,
    supports_reasoning = 1,
    update_time = CURRENT_TIMESTAMP
WHERE model_name IN ('deepseek-v4-flash', 'deepseek-v4-pro')
  AND source = 'system';
