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
    provider_key VARCHAR(64) NOT NULL DEFAULT 'custom',
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

INSERT OR IGNORE INTO ai_model_capabilities
(model_name, provider_key, source, context_window_tokens, max_output_tokens,
 supports_text_generation, supports_reasoning, supports_streaming, supports_function_calling,
 supports_structured_output, supports_web_search, supports_parallel_tool_calls, remark)
VALUES
('mimo-v2.5-pro', 'mimo', 'system', 1000000, 128000, 1, 1, 1, 1, 1, 1, 1, '系统内置能力库'),
('mimo-v2.5-flash', 'mimo', 'system', 256000, 32000, 1, 1, 1, 1, 1, 0, 1, '系统内置能力库'),
('mimo-embedding-v1', 'mimo', 'system', 8192, 0, 0, 0, 0, 0, 0, 0, 0, '系统内置能力库'),
('deepseek-chat', 'deepseek', 'system', 128000, 8192, 1, 0, 1, 1, 1, 0, 1, '系统内置能力库'),
('deepseek-reasoner', 'deepseek', 'system', 128000, 65536, 1, 1, 1, 1, 1, 0, 1, '系统内置能力库'),
('deepseek-r1', 'deepseek', 'system', 128000, 65536, 1, 1, 1, 1, 1, 0, 1, '系统内置能力库'),
('deepseek-v4-flash', 'deepseek', 'system', 1000000, 384000, 1, 0, 1, 1, 1, 0, 1, '系统内置能力库'),
('deepseek-v4-pro', 'deepseek', 'system', 1000000, 384000, 1, 1, 1, 1, 1, 0, 1, '系统内置能力库'),
('qwen3-235b-a22b', 'qwen', 'system', 128000, 32000, 1, 1, 1, 1, 1, 0, 1, '系统内置能力库'),
('qwq-plus', 'qwen', 'system', 128000, 32000, 1, 1, 1, 1, 1, 0, 1, '系统内置能力库'),
('qwen-max', 'qwen', 'system', 128000, 8192, 1, 0, 1, 1, 1, 0, 1, '系统内置能力库'),
('qwen-plus', 'qwen', 'system', 128000, 8192, 1, 0, 1, 1, 1, 0, 1, '系统内置能力库'),
('qwen-turbo', 'qwen', 'system', 128000, 8192, 1, 0, 1, 1, 1, 0, 1, '系统内置能力库'),
('gemini-2.5-pro', 'gemini', 'system', 1000000, 65536, 1, 1, 1, 1, 1, 1, 1, '系统内置能力库'),
('gemini-2.5-flash', 'gemini', 'system', 1000000, 65536, 1, 0, 1, 1, 1, 1, 1, '系统内置能力库'),
('gemini-2.0-flash', 'gemini', 'system', 1000000, 65536, 1, 0, 1, 1, 1, 1, 1, '系统内置能力库'),
('gpt-4o', 'openai', 'system', 128000, 16384, 1, 0, 1, 1, 1, 0, 1, '系统内置能力库'),
('gpt-4o-mini', 'openai', 'system', 128000, 16384, 1, 0, 1, 1, 1, 0, 1, '系统内置能力库'),
('gpt-4', 'openai', 'system', 128000, 8192, 1, 0, 1, 1, 1, 0, 1, '系统内置能力库'),
('o1', 'openai', 'system', 200000, 100000, 1, 1, 1, 1, 1, 0, 1, '系统内置能力库'),
('o3', 'openai', 'system', 200000, 100000, 1, 1, 1, 1, 1, 0, 1, '系统内置能力库'),
('o4-mini', 'openai', 'system', 200000, 100000, 1, 1, 1, 1, 1, 0, 1, '系统内置能力库'),
('moonshot-v1-128k', 'moonshot', 'system', 128000, 8192, 1, 0, 1, 1, 0, 0, 1, '系统内置能力库'),
('moonshot-v1-32k', 'moonshot', 'system', 32000, 8192, 1, 0, 1, 1, 0, 0, 1, '系统内置能力库'),
('moonshot-v1-8k', 'moonshot', 'system', 8000, 4096, 1, 0, 1, 1, 0, 0, 1, '系统内置能力库');

UPDATE ai_model_capabilities
SET context_window_tokens = 1000000,
    max_output_tokens = 384000,
    update_time = CURRENT_TIMESTAMP
WHERE model_name IN ('deepseek-v4-flash', 'deepseek-v4-pro')
  AND source = 'system';
