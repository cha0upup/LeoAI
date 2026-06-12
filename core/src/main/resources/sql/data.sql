-- =====================================================
-- LeoSpring 初始数据
-- =====================================================

-- 插入默认团队
INSERT OR REPLACE INTO teams (
    team_id, 
    team_name, 
    leader_id, 
    description, 
    status, 
    create_time, 
    update_time, 
    remark
) VALUES (
    'admin-team',
    '管理员团队',
    'admin',
    '系统默认管理员团队',
    1,
    datetime('now'),
    datetime('now'),
    '系统初始化创建'
);

-- 插入默认管理员用户
INSERT OR REPLACE INTO users (
    user_id,
    user_name,
    password,
    privilege,
    email,
    phone,
    status,
    last_login_time,
    login_count,
    create_time,
    update_time,
    team_id,
    remark
) VALUES (
    'admin',
    'admin',
    '052fb3c5d2d357ba01d068ff0303a076', -- MD5加密的密码
    'admin',
    'admin@leospring.com',
    '',
    1,
    datetime('now'),
    0,
    datetime('now'),
    datetime('now'),
    'admin-team',
    '系统默认管理员账户'
);

-- 插入系统配置
INSERT OR REPLACE INTO system_configs (
    config_key,
    config_value,
    config_type,
    description,
    create_time,
    update_time
) VALUES 
('system.name', 'LeoSpring', 'string', '系统名称', datetime('now'), datetime('now')),
('system.version', '2.1', 'string', '系统版本', datetime('now'), datetime('now')),
('system.description', 'LeoSpring - 轻量级远程主机管理平台', 'string', '系统描述', datetime('now'), datetime('now')),
('log.retention.days', '30', 'number', '日志保留天数', datetime('now'), datetime('now')),
('session.timeout.minutes', '30', 'number', '会话超时时间(分钟)', datetime('now'), datetime('now')),
('heartbeat.interval.ms', '30000', 'number', '心跳间隔(毫秒)', datetime('now'), datetime('now')),
('max.file.upload.size.mb', '100', 'number', '最大文件上传大小(MB)', datetime('now'), datetime('now')),
('max.concurrent.sessions', '10', 'number', '最大并发会话数', datetime('now'), datetime('now')),
('security.password.min.length', '6', 'number', '密码最小长度', datetime('now'), datetime('now')),
('security.login.max.attempts', '5', 'number', '最大登录尝试次数', datetime('now'), datetime('now'));