-- =====================================================
-- LeoAI 初始数据
-- =====================================================

-- 管理员账号由首次部署的 Web 初始化页面创建；没有共享默认密码。

-- 插入系统配置
INSERT OR IGNORE INTO system_configs (
    config_key,
    config_value,
    config_type,
    description,
    create_time,
    update_time
) VALUES 
('audit.mode', 'on', 'string', '审计日志模式：on=开启，write=关闭低风险读操作，off=完全关闭', datetime('now'), datetime('now')),
('max.file.upload.size.mb', '100', 'number', '最大文件上传大小(MB)', datetime('now'), datetime('now')),
('security.password.min.length', '8', 'number', '密码最小长度', datetime('now'), datetime('now')),
('security.login.max.attempts', '5', 'number', '最大登录尝试次数', datetime('now'), datetime('now')),
('security.login.lock.seconds', '300', 'number', '登录失败锁定时长(秒)', datetime('now'), datetime('now'));
