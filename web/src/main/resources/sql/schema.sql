-- =====================================================
-- LeoSpring 数据库设计 - 重新设计版本
-- =====================================================

-- 1. 用户表
CREATE TABLE IF NOT EXISTS users (
    user_id VARCHAR(50) PRIMARY KEY,
    user_name VARCHAR(100) NOT NULL,
    password VARCHAR(255) NOT NULL,
    privilege VARCHAR(20) NOT NULL DEFAULT 'normal',
    email VARCHAR(100),
    phone VARCHAR(20),
    status INTEGER DEFAULT 1, -- 1:启用 0:禁用
    last_login_time DATETIME,
    login_count INTEGER DEFAULT 0,
    create_time DATETIME NOT NULL,
    update_time DATETIME NOT NULL,
    team_id VARCHAR(50),
    remark TEXT
);

-- 2. 团队表
CREATE TABLE IF NOT EXISTS teams (
    team_id VARCHAR(50) PRIMARY KEY,
    team_name VARCHAR(100) NOT NULL,
    leader_id VARCHAR(50) NOT NULL,
    description TEXT,
    status INTEGER DEFAULT 1, -- 1:启用 0:禁用
    create_time DATETIME NOT NULL,
    update_time DATETIME NOT NULL,
    remark TEXT
);

-- 3. 受控主机表 (Puppet)
CREATE TABLE IF NOT EXISTS puppets (
    puppet_id VARCHAR(50) PRIMARY KEY,
    puppet_name VARCHAR(100) NOT NULL,
    parent_puppet_id VARCHAR(50) NOT NULL,
    create_by_user_id VARCHAR(50) NOT NULL,
    team_id VARCHAR(50),
    conn_link TEXT NOT NULL,
    protocol VARCHAR(20) DEFAULT 'http', -- http, httpChunked, websocket
    headers TEXT,
    req_disguise_id VARCHAR(100) NOT NULL,
    resp_disguise_id VARCHAR(100) NOT NULL,
    proxy_enabled INTEGER DEFAULT 0, -- 0:禁用 1:启用
    proxy_type VARCHAR(20), -- http, socks
    proxy_host VARCHAR(255),
    proxy_port INTEGER,
    balance_enabled INTEGER DEFAULT 0, -- 0:禁用 1:启用（负载均衡稳定功能）
    max_req_count INTEGER DEFAULT 0,
    permission VARCHAR(20) DEFAULT 'read', -- read, write, admin
    last_heartbeat DATETIME,
    heartbeat_interval INTEGER DEFAULT 30000, -- 心跳间隔(毫秒)
    create_time DATETIME NOT NULL,
    update_time DATETIME NOT NULL,
    remark TEXT,
    url_strategy TEXT, -- URL 随机化策略（JSON 格式）
    padding_strategy TEXT, -- 请求体 Padding 策略（JSON 格式）
    header_noise_strategy TEXT, -- Header 噪声注入策略（JSON 格式）
    tls_fingerprint_strategy TEXT, -- TLS 指纹伪装策略（JSON 格式）
    type VARCHAR(20) DEFAULT 'java' -- 节点类型：java, php
);



-- 8. 系统配置表
CREATE TABLE IF NOT EXISTS system_configs (
    config_key VARCHAR(100) PRIMARY KEY,
    config_value TEXT,
    config_type VARCHAR(20) DEFAULT 'string', -- string, number, boolean, json
    description TEXT,
    create_time DATETIME NOT NULL,
    update_time DATETIME NOT NULL
);

-- 9. 会话管理表
CREATE TABLE IF NOT EXISTS sessions (
    session_id VARCHAR(100) PRIMARY KEY,
    user_id VARCHAR(50) NOT NULL,
    puppet_id VARCHAR(50) NOT NULL,
    session_data TEXT, -- JSON格式存储会话数据
    status INTEGER DEFAULT 1, -- 1:活跃 0:过期
    create_time DATETIME NOT NULL,
    last_access_time DATETIME NOT NULL,
    expire_time DATETIME
);


-- =====================================================
-- 数据库连接信息表
-- =====================================================

-- 11. 数据库连接信息表
CREATE TABLE IF NOT EXISTS puppet_jdbc (
    conn_id VARCHAR(50) PRIMARY KEY,
    conn_name VARCHAR(100) NOT NULL,
    puppet_id VARCHAR(50) NOT NULL, -- 所属的puppet ID
    db_type VARCHAR(20) NOT NULL, -- mysql, postgresql, sqlserver, oracle, sqlite
    host VARCHAR(255) NOT NULL,
    port INTEGER NOT NULL,
    database_name VARCHAR(100), -- 数据库名、服务名或文件路径
    username VARCHAR(100),
    password VARCHAR(255), -- 加密存储
    url_template TEXT, -- JDBC URL模板
    jdbc_url TEXT, -- 完整的JDBC连接字符串
    driver_class VARCHAR(255), -- JDBC驱动类名
    connection_params TEXT, -- JSON格式存储额外连接参数
    status INTEGER DEFAULT 1, -- 1:启用 0:禁用
    test_status INTEGER DEFAULT 0, -- 0:未测试 1:连接成功 2:连接失败
    last_test_time DATETIME,
    last_test_message TEXT, -- 最后一次测试的结果信息
    max_connections INTEGER DEFAULT 10, -- 最大连接数
    timeout_seconds INTEGER DEFAULT 30, -- 连接超时时间(秒)
    create_user_id VARCHAR(50) NOT NULL,
    team_id VARCHAR(50), -- 所属团队，NULL表示个人连接
    is_public INTEGER DEFAULT 0, -- 0:私有 1:公开(团队内共享)
    create_time DATETIME NOT NULL,
    update_time DATETIME NOT NULL,
    description TEXT,
    remark TEXT
);

-- 12. 审计日志表
CREATE TABLE IF NOT EXISTS audit_logs (
    log_id VARCHAR(50) PRIMARY KEY,
    user_id VARCHAR(50), -- 操作用户ID
    user_name VARCHAR(100), -- 操作用户名
    puppet_id VARCHAR(50), -- 目标主机ID
    puppet_name VARCHAR(100), -- 目标主机名
    session_id VARCHAR(100), -- 会话ID
    operation_type VARCHAR(50) NOT NULL, -- 操作类型：FILE_LIST, FILE_DELETE, FILE_EDIT, COMMAND_EXEC, SQL_EXEC, SCREENSHOT等
    operation_name VARCHAR(100) NOT NULL, -- 操作名称
    operation_path VARCHAR(500), -- 操作路径（文件路径、命令等）
    request_params TEXT, -- 请求参数（JSON格式，敏感信息需脱敏）
    response_code INTEGER, -- 响应码
    response_message VARCHAR(500), -- 响应消息
    status VARCHAR(20) DEFAULT 'SUCCESS', -- 操作状态：SUCCESS, FAILED, ERROR
    error_message TEXT, -- 错误信息
    client_ip VARCHAR(50), -- 客户端IP
    create_time DATETIME NOT NULL, -- 操作时间
    remark TEXT -- 备注
);

-- 13. AI 供应商与模型配置
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

-- 15. AI 对话线程（统一存储，不再依赖 JSONL/index 文件）
CREATE TABLE IF NOT EXISTS ai_threads (
    thread_id VARCHAR(64) PRIMARY KEY,
    scope VARCHAR(32) NOT NULL,
    user_id VARCHAR(50),
    puppet_id VARCHAR(50),
    session_id VARCHAR(100),
    title VARCHAR(200) NOT NULL,
    config_id INTEGER,
    config_name VARCHAR(100),
    config_protocol VARCHAR(32),
    config_model VARCHAR(255),
    config_base_url TEXT,
    config_completions_path VARCHAR(255),
    config_max_output_tokens INTEGER,
    created_at INTEGER NOT NULL,
    last_active_at INTEGER NOT NULL,
    message_count INTEGER NOT NULL DEFAULT 0,
    run_status VARCHAR(32) NOT NULL DEFAULT 'idle',
    parent_thread_id VARCHAR(64),
    profile VARCHAR(64) NOT NULL DEFAULT 'default',
    mode VARCHAR(16) NOT NULL DEFAULT 'auto',
    context_summary TEXT,
    root_plan_id VARCHAR(64)
);

CREATE INDEX IF NOT EXISTS idx_ai_threads_scope
    ON ai_threads(scope, user_id, puppet_id, last_active_at);

CREATE INDEX IF NOT EXISTS idx_ai_threads_parent
    ON ai_threads(parent_thread_id);

-- 16. AI 对话消息
CREATE TABLE IF NOT EXISTS ai_messages (
    message_id VARCHAR(64) PRIMARY KEY,
    thread_id VARCHAR(64) NOT NULL,
    role VARCHAR(32) NOT NULL,
    content TEXT,
    timestamp INTEGER NOT NULL,
    thinking_logs_json TEXT,
    tool_calls_json TEXT,
    review_json TEXT,
    plan_json TEXT
);

CREATE INDEX IF NOT EXISTS idx_ai_messages_thread_time
    ON ai_messages(thread_id, timestamp);

-- 17. AI 单次运行记录
CREATE TABLE IF NOT EXISTS ai_runs (
    run_id VARCHAR(64) PRIMARY KEY,
    thread_id VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    started_at INTEGER NOT NULL,
    finished_at INTEGER,
    duration_ms INTEGER,
    config_id INTEGER,
    input TEXT,
    output TEXT,
    error_message TEXT,
    tool_call_count INTEGER,
    runtime_json TEXT
);

CREATE INDEX IF NOT EXISTS idx_ai_runs_thread_time
    ON ai_runs(thread_id, started_at);

-- 18. AI 运行事件（SSE 事件持久化）
CREATE TABLE IF NOT EXISTS ai_events (
    event_id VARCHAR(64) PRIMARY KEY,
    run_id VARCHAR(64),
    thread_id VARCHAR(64) NOT NULL,
    event_seq INTEGER NOT NULL,
    timestamp INTEGER NOT NULL,
    name VARCHAR(64) NOT NULL,
    data_json TEXT
);

CREATE INDEX IF NOT EXISTS idx_ai_events_thread_seq
    ON ai_events(thread_id, event_seq);

CREATE INDEX IF NOT EXISTS idx_ai_events_run_seq
    ON ai_events(run_id, event_seq);

-- 19. AI 子 Agent 调用记录（Phase 1 脚手架，父会话→子会话的派发关系）
CREATE TABLE IF NOT EXISTS ai_subagent_invocations (
    invocation_id VARCHAR(64) PRIMARY KEY,
    parent_thread_id VARCHAR(64) NOT NULL,
    parent_message_id VARCHAR(64),
    child_thread_id VARCHAR(64),
    profile VARCHAR(64) NOT NULL,
    task TEXT NOT NULL,
    input_json TEXT,
    summary TEXT,
    status VARCHAR(32) NOT NULL DEFAULT 'pending',
    created_at INTEGER NOT NULL,
    completed_at INTEGER
);

CREATE INDEX IF NOT EXISTS idx_ai_subagent_parent
    ON ai_subagent_invocations(parent_thread_id, created_at);
