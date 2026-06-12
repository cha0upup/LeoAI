---
name: analyze-logs-intelligence
description: 从目标主机日志中提取运维情报：access.log 中的内网系统、auth.log 中的来源 IP、应用日志中的连接串和报错信息。当需要从日志中发现新目标或运维模式时使用。
---

# 日志情报分析

从目标主机的系统日志和应用日志中提取运维情报，发现新的攻击目标、运维跳板、内网服务地址和凭据泄露。

> 适用场景：已获得文件读取权限，需要从日志中挖掘情报以扩展攻击面。

---

## 一、OPSEC 与约束

| 原则 | 说明 |
|---|---|
| 只读 | 仅读取日志文件，不修改、不删除、不截断 |
| 按需读取 | 优先读取最近日志（tail），不全量拉取 GB 级文件 |
| 不暴露 | 不向日志写入测试标记 |
| 性能意识 | 避免对生产日志做全文 grep 导致 IO 尖刺 |

---

## 二、目标

- 从 Web 访问日志发现内网系统 URL 和 IP
- 从认证日志发现运维来源 IP（跳板机、堡垒机）
- 从应用日志发现连接串、凭据、API 地址
- 从错误日志发现配置路径、中间件信息
- 建立运维行为画像（登录时间、来源、频率）

---

## 三、Skill 元数据

- riskLevel: `low`
- accessMode: `read_only`
- requiredTools: `CommandTools`, `FileTools`
- optionalTools: `ResourceTools`
- produces: `intelligence.internalSystems[]`, `intelligence.jumpHosts[]`, `intelligence.leakedCredentials[]`, `intelligence.operationalPatterns`, `openQuestions`
- structuredPatchPaths: `intelligence.internalSystems[]`, `intelligence.jumpHosts[]`, `intelligence.leakedCredentials[]`
- recommendedNextSkills: `recon-internal-network`, `lateral-move-ssh`, `collect-jdbc-connection-info`, `exploit-database-post`
- forbiddenByDefault: 修改/删除日志、向日志注入内容

---

## 四、工作流程

### 执行前：制定计划

1. **目标**：从目标主机日志中提取内网情报，发现新目标和凭据线索。
2. **路径**：定位日志文件 → 分类读取 → 提取关键信息 → 交叉关联 → 生成情报摘要。
3. **终止条件**：主要日志源均已分析，或未发现有价值情报时停止。

### 第一阶段：日志文件定位

```bash
# 系统日志
ls -la /var/log/auth.log /var/log/secure /var/log/syslog /var/log/messages 2>/dev/null
ls -la /var/log/lastlog /var/log/wtmp /var/log/btmp 2>/dev/null

# Web 服务器日志
find /var/log/nginx /var/log/apache2 /var/log/httpd -name "*.log" -mtime -7 2>/dev/null
find /opt /srv /data -name "access*.log" -mtime -7 2>/dev/null

# 应用日志
find /opt /srv /data /app -name "*.log" -mtime -3 -size +0 2>/dev/null | head -20
find / -path "*/logs/*.log" -mtime -3 2>/dev/null | head -20

# Tomcat / Spring Boot
ls -la /opt/*/logs/ /var/log/tomcat*/ 2>/dev/null
find / -name "catalina.out" -mtime -3 2>/dev/null

# 数据库日志
ls -la /var/log/mysql/ /var/log/postgresql/ /var/lib/mysql/*.log 2>/dev/null
```

### 第二阶段：Web 访问日志分析

**目标：发现内网系统 IP、API 地址、管理后台**

```bash
# 提取访问来源 IP（去重排序）
tail -5000 /path/to/access.log | awk '{print $1}' | sort -u

# 提取被访问的内网 URL（Referer 和 请求路径）
grep -oP 'https?://10\.\d+\.\d+\.\d+[^\s"]*' /path/to/access.log | sort -u
grep -oP 'https?://172\.(1[6-9]|2\d|3[01])\.\d+\.\d+[^\s"]*' /path/to/access.log | sort -u
grep -oP 'https?://192\.168\.\d+\.\d+[^\s"]*' /path/to/access.log | sort -u

# 发现管理路径
grep -i "admin\|manager\|console\|actuator\|swagger\|api-docs" /path/to/access.log | awk '{print $7}' | sort -u | head -30

# 发现异常大量 POST（可能是接口调用）
grep "POST" /path/to/access.log | awk '{print $7}' | sort | uniq -c | sort -rn | head -20
```

### 第三阶段：认证日志分析

**目标：发现运维来源 IP、登录模式、失败爆破**

```bash
# SSH 登录成功记录
grep "Accepted" /var/log/auth.log 2>/dev/null | tail -50
grep "Accepted" /var/log/secure 2>/dev/null | tail -50

# 提取登录来源 IP
grep "Accepted" /var/log/auth.log | grep -oP 'from \K[\d.]+' | sort | uniq -c | sort -rn

# 登录失败（可能的爆破来源或密码尝试）
grep "Failed password" /var/log/auth.log | grep -oP 'from \K[\d.]+' | sort | uniq -c | sort -rn | head -10

# sudo 使用记录
grep "sudo" /var/log/auth.log | tail -20

# last 命令（历史登录）
last -20 2>/dev/null

# Windows 等价
# wevtutil qe Security /f:text /q:"*[System[(EventID=4624)]]" /c:50
```

**高价值来源 IP 类型：**

| IP 特征 | 推断 |
|---|---|
| 固定单一内网 IP 频繁登录 | 跳板机/堡垒机 |
| 多个目标从同一 IP 登录 | 运维管理网段 |
| 非工作时间登录 | 可能是自动化或异常 |
| 登录后立即 sudo | 运维操作模式 |

### 第四阶段：应用日志分析

**目标：发现连接串、凭据、错误中的敏感信息**

```bash
# JDBC 连接串
grep -i "jdbc:" /path/to/app.log | tail -10

# 连接失败（暴露地址和凭据）
grep -i "connection refused\|access denied\|authentication failed" /path/to/app.log | tail -20

# Redis 连接
grep -i "redis\|jedis\|lettuce" /path/to/app.log | grep -i "connect\|auth\|password" | tail -10

# API 调用（发现其他内网服务）
grep -oP 'https?://[\d.]+:\d+/[^\s"]*' /path/to/app.log | sort -u

# 异常堆栈中的路径信息
grep -A2 "at.*Exception" /path/to/app.log | grep -oP '/[\w/.-]+\.(yml|properties|xml|conf)' | sort -u

# Token / Key 泄露
grep -i "token=\|apikey=\|secret=\|password=" /path/to/app.log | tail -10
```

### 第五阶段：交叉关联

将各日志源的发现交叉验证：

- auth.log 中的来源 IP + access.log 中同 IP 的请求 → 运维人员行为模式
- 应用日志中的连接地址 + 已知存活主机列表 → 确认新目标
- 错误日志中的路径 + 文件系统实际检查 → 配置文件定位

---

## 五、工具优先级

| 优先级 | 工具 | 用途 |
|---|---|---|
| 1 | `CommandTools.exec` | grep/awk/tail 日志分析 |
| 2 | `CommandTools.exec` | 大文件 grep（可能耗时） |
| 3 | `FileTools.readTextFile` | 读取小日志文件全文 |
| 4 | `CommandTools.exec` | 递归搜索特定模式 |

**注意：** 大日志文件（>100MB）使用 tail + grep，不要 cat 全文。

---

## 六、输出格式

```markdown
## 日志情报分析摘要

**分析日志源数**：{n}
**发现内网系统数**：{n}
**发现跳板/来源 IP 数**：{n}
**发现凭据线索数**：{n}

---

## 内网系统发现

| IP:Port | 来源日志 | 服务推断 | 优先级 |
|---------|---------|---------|--------|

## 运维来源 IP（跳板机候选）

| IP | 登录次数 | 最近登录 | 登录用户 | 推断角色 |
|----|---------|---------|---------|---------|

## 凭据/连接串泄露

| 来源日志 | 类型 | 内容 | 置信度 |
|---------|------|------|--------|

## 运维行为画像

| 模式 | 描述 | 攻击意义 |
|------|------|---------|
| 登录时段 | 工作日 9-18 | 非工作时间操作不易被发现 |
| 登录来源 | 10.0.1.100 | 跳板机，横向高价值目标 |

## 下一步建议

2~3 条建议
```

---

## 七、结构化摘要写入

```json
{
  "intelligence": {
    "internalSystems": [
      {"address": "10.0.0.20:3306", "source": "app.log (JDBC error)", "service": "mysql", "confidence": "high"},
      {"address": "10.0.0.30:8080", "source": "access.log (Referer)", "service": "http", "confidence": "medium"}
    ],
    "jumpHosts": [
      {"ip": "10.0.1.100", "loginCount": 45, "lastSeen": "2024-01-15", "users": ["ops", "deploy"], "role": "jumpbox"}
    ],
    "leakedCredentials": [
      {"source": "catalina.out", "type": "jdbc", "content": "jdbc:mysql://10.0.0.20:3306/app?user=root&password=xxx", "confidence": "high"}
    ],
    "operationalPatterns": {
      "activeHours": "09:00-18:00 weekdays",
      "primaryJumpbox": "10.0.1.100",
      "deploymentUser": "deploy"
    }
  },
  "openQuestions": ["lateral-move-ssh (to jumpbox 10.0.1.100)", "collect-jdbc-connection-info (10.0.0.20:3306)"]
}
```

---

## 八、决策规则

| 场景 | 行为 |
|---|---|
| 日志文件不存在 | 跳过，标注无此日志源 |
| 日志文件过大（>500MB） | 只读最近 5000 行 |
| 日志已轮转（.gz） | 标注存在但不解压（避免 IO） |
| 发现大量内网 IP | 与已知存活主机交叉验证 |
| 发现明文凭据 | 立即记录到侦察摘要 |
| 无有价值信息 | 报告已分析的范围和结论 |
