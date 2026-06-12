-- AI 模型配置（ccswitch 风格：name + base_url + api_key + model 一体）
CREATE TABLE IF NOT EXISTS ai_model_configs (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    name VARCHAR(100) NOT NULL UNIQUE,
    api_key TEXT NOT NULL,
    base_url TEXT NOT NULL,
    model VARCHAR(255) NOT NULL,
    completions_path VARCHAR(255) NOT NULL DEFAULT '/v1/chat/completions',
    is_active INTEGER NOT NULL DEFAULT 0,
    max_output_tokens INTEGER,
    -- reasoning 三态：1=显式启用，0=显式禁用，NULL=按模型自动推断
    thinking_enabled INTEGER,
    context_window_tokens INTEGER,
    create_time DATETIME NOT NULL,
    update_time DATETIME NOT NULL,
    remark TEXT
);
