---
name: escalate-windows-privilege
description: 在 Windows 目标上系统性地检测权限提升路径，包括未打补丁的内核/组件 CVE、服务配置错误、AlwaysInstallElevated、令牌滥用、UAC 绕过等。当任务涉及 Windows 提权、补丁缺失、服务劫持、令牌操作时使用。
---

# Windows 权限提升路径检测

当目标是 Windows 主机且当前权限为普通用户（非 SYSTEM/Administrator）时，使用这个 skill 系统性地检测可用的提权路径。

**原则：只检测，不利用。** 所有步骤为信息收集，不执行实际提权操作，由用户决定是否利用。

---

## 一、OPSEC 与环境约束

| 原则 | 说明 |
|---|---|
| 只读操作 | 不修改注册表、不安装服务、不执行实际提权 |
| 低噪声 | 不使用 PowerShell 下载脚本、不执行 WinPEAS 等工具 |
| 避免触发 EDR | 不调用 `Invoke-Expression`、不使用 `Net.WebClient`、不创建进程注入 |
| 分阶段收敛 | 先检测令牌和服务（快速），再检测补丁（耗时） |
| 原生命令优先 | 优先使用 `cmd.exe` 内置命令和 `wmic`，减少 PowerShell 使用 |

**WebShell 环境注意：**
- WebShell 通常以 IIS AppPool、NETWORK SERVICE 或 Web 服务账户运行
- `whoami /priv` 可能显示 SeImpersonatePrivilege（IIS/服务账户常见）
- `systeminfo` 输出可能很长，WebShell 可能截断，用 `wmic` 替代
- 部分命令需要特定权限才能执行（如 `wmic qfe`），失败时需回退
- PowerShell 可能被 Constrained Language Mode 限制

---

## 二、目标

检测并报告以下提权向量：

- 令牌权限滥用（SeImpersonatePrivilege 等 Potato 系列）
- 未打补丁的高危 CVE（内核、Print Spooler、Win32k 等）
- 服务配置错误（可写服务路径、未加引号路径、弱权限）
- AlwaysInstallElevated 注册表配置
- UAC 配置和绕过面
- 计划任务可写脚本
- 可写的 PATH 目录
- 凭据存储（Credential Manager、AutoLogon）
- Print Spooler 状态（PrintNightmare）
- 域控专项（Zerologon，如适用）

---

## 三、工作流程

### 执行前：制定计划

在任何工具调用前，先输出：

1. **目标**：检测目标 Windows 主机上可用的权限提升路径
2. **路径**：确认用户和令牌 → 并发检测服务/注册表/补丁 → 汇总报告
3. **终止条件**：所有检测项已执行并汇总，或当前用户已是 SYSTEM/Administrator 时停止

如果已有侦察摘要，先读取 `hostProfile`（OS 版本）和 `privilegeProfile`（当前用户和令牌），跳过已有信息。如果当前用户已是 SYSTEM 或 Administrator，此 skill 无需执行。

### 第一阶段：用户与令牌确认

```cmd
whoami /all
net user %username%
net localgroup administrators
```

**高危令牌权限：**

| 权限名称 | 风险 | 利用方式 |
|---|---|---|
| SeImpersonatePrivilege | 高 | Potato 系列（JuicyPotato/PrintSpoofer/GodPotato） |
| SeAssignPrimaryTokenPrivilege | 高 | 同上 |
| SeBackupPrivilege | 高 | 可读取任意文件（SAM/SYSTEM） |
| SeRestorePrivilege | 高 | 可写入任意文件 |
| SeTakeOwnershipPrivilege | 中 | 可接管任意对象所有权 |
| SeDebugPrivilege | 高 | 可注入高权限进程 |
| SeLoadDriverPrivilege | 高 | 可加载内核驱动 |

> **WebShell 关键提示**：IIS AppPool 和 Windows 服务账户通常拥有 SeImpersonatePrivilege，这是最常见的 WebShell 提权路径。

### 第二阶段：系统版本与补丁检测

```cmd
wmic os get Caption,Version,BuildNumber,ServicePackMajorVersion /format:list
wmic qfe list brief /format:table
```

如果 `wmic qfe` 失败：

```cmd
systeminfo | findstr /i "KB"
```

根据 OS Build 和已安装补丁，对照以下高危 CVE：

| CVE 编号 | 名称 | 受影响范围 | 修复基线 |
|---|---|---|---|
| CVE-2020-1472 | Zerologon | 域控未安装 2020-08 补丁 | 2020-08 累积更新 |
| CVE-2021-34527 | PrintNightmare | Win10/Server 2019 等 | 2021-07 累积更新 |
| CVE-2021-1732 | Win32k EoP | Win10 1803~20H2 | 2021-02 累积更新 |
| CVE-2022-21999 | Print Spooler EoP | Win8.1~Server 2022 | 对应月份累积更新 |
| CVE-2024-21338 | Kernel EoP | Win10 1809+ / Win11 | 对应构建号更新 |
| CVE-2024-30051 | DWM Core Library EoP | Win10 22H2 / Server 2022 | 对应构建号更新 |
| CVE-2024-30088 | Kernel EoP (KEV) | Win10 22H2 / Server 2022 | 对应构建号更新 |
| CVE-2020-17087 | Kernel Crypto Driver | Win7~20H2 | 2020-11 累积更新 |

> **注意**：以 `systeminfo` 的 Build 号和 `wmic qfe` 中的 KB 编号为准，不要只看主版本号。

### 第三阶段：服务配置检测（并发）

**1. 服务路径和权限：**

```cmd
wmic service get Name,PathName,StartMode,StartName,State /format:table
```

**2. 未加引号的服务路径（空格路径劫持）：**

```cmd
wmic service get Name,PathName | findstr /i /v "C:\Windows" | findstr /i /v """"
```

**3. 服务可执行文件权限检查：**

```cmd
:: 对可疑服务路径检查 ACL
icacls "C:\path\to\service.exe"
```

重点关注：
- 服务以 SYSTEM/LocalSystem 运行
- 可执行文件或目录对当前用户可写（F/W/M 权限）
- 未加引号且路径含空格

### 第四阶段：注册表与配置检测（并发）

**1. AlwaysInstallElevated：**

```cmd
reg query HKCU\SOFTWARE\Policies\Microsoft\Windows\Installer /v AlwaysInstallElevated 2>nul
reg query HKLM\SOFTWARE\Policies\Microsoft\Windows\Installer /v AlwaysInstallElevated 2>nul
```

两个键值均为 `0x1` 时，可通过 MSI 提权到 SYSTEM。

**2. UAC 配置：**

```cmd
reg query HKLM\SOFTWARE\Microsoft\Windows\CurrentVersion\Policies\System /v EnableLUA 2>nul
reg query HKLM\SOFTWARE\Microsoft\Windows\CurrentVersion\Policies\System /v ConsentPromptBehaviorAdmin 2>nul
reg query HKLM\SOFTWARE\Microsoft\Windows\CurrentVersion\Policies\System /v LocalAccountTokenFilterPolicy 2>nul
```

| 值 | 含义 |
|---|---|
| EnableLUA=0 | UAC 已禁用 |
| ConsentPromptBehaviorAdmin=0 | 无提示自动提升 |
| LocalAccountTokenFilterPolicy=1 | 远程 UAC 禁用 |

**3. Print Spooler 状态：**

```cmd
sc query Spooler
reg query "HKLM\SOFTWARE\Policies\Microsoft\Windows NT\Printers\PointAndPrint" 2>nul
```

Spooler 运行中且 `NoWarningNoElevationOnInstall=1` 时，PrintNightmare 风险更高。

### 第五阶段：计划任务与 PATH

**1. 计划任务：**

```cmd
schtasks /query /fo LIST /v | findstr /i "Task To Run\|Run As User\|Status"
```

关注以 SYSTEM 运行且脚本路径对当前用户可写的任务。

**2. 可写 PATH 目录：**

```cmd
for %d in (%PATH:;= %) do @icacls "%d" 2>nul | findstr /i "Everyone\|BUILTIN\\Users\|Authenticated Users" | findstr /i "F\|W\|M"
```

### 第六阶段：凭据存储

```cmd
:: Credential Manager
cmdkey /list

:: AutoLogon
reg query "HKLM\SOFTWARE\Microsoft\Windows NT\CurrentVersion\Winlogon" /v DefaultUserName 2>nul
reg query "HKLM\SOFTWARE\Microsoft\Windows NT\CurrentVersion\Winlogon" /v DefaultPassword 2>nul

:: 无人值守安装文件
dir /s /b C:\unattend.xml C:\sysprep.inf C:\sysprep\sysprep.xml 2>nul
```

### 第七阶段：域控专项（如适用）

仅当目标为域控时执行：

```cmd
nltest /dclist:%userdomain% 2>nul
sc query Netlogon
```

Zerologon（CVE-2020-1472）要求目标为域控且未安装 2020-08 补丁。

---

## 四、工具优先级

| 优先级 | 工具 | 用途 |
|---|---|---|
| 1 | `CommandTools` | 所有检测命令执行 |
| 2 | `FileTools` | 读取无人值守安装文件等 |

命令执行模式选择：

| 命令类型 | 模式 |
|---|---|
| 快速命令（whoami、reg query） | `exec` |
| 可能耗时（wmic service、schtasks） | `exec` |
| 多个独立命令 | 并发 `exec` |

---

## 五、失败回退

| 场景 | 回退策略 |
|---|---|
| `wmic` 不可用（Win11 移除） | 用 PowerShell `Get-WmiObject` 或 `Get-CimInstance` |
| `wmic qfe` 权限不足 | 用 `systeminfo \| findstr KB` |
| `systeminfo` 输出被截断 | 分段获取：先 `wmic os`，再 `wmic qfe` |
| PowerShell Constrained Mode | 仅使用 cmd.exe 内置命令 |
| `icacls` 输出过多 | 限定检查前 10 个可疑服务路径 |
| `schtasks` 输出过长 | 用 `findstr` 过滤关键字段 |
| 注册表查询被拒绝 | 标注"权限不足"，继续其他检测 |

---

## 六、输出格式

```markdown
## Windows 提权路径检测摘要

**当前用户**：{username}
**权限级别**：{service_account / user / admin}
**OS 版本**：{os} Build {build}
**发现向量数**：{n}（high: x，medium: y，low: z）

---

## 提权向量（按优先级排序）

| # | 类型 | 目标 | 可利用性 | 前提条件 |
|---|------|------|---------|---------|

## 向量详情

### [HIGH] {vector_name}

- **类型**：{token_abuse / service_misconfig / missing_patch / ...}
- **目标**：{具体权限/服务/CVE}
- **可利用性**：{high / medium / low}
- **利用前提**：{条件说明}

## 补丁缺失风险

| CVE | 名称 | 当前 Build | 修复 Build | 匹配 |
|-----|------|-----------|-----------|------|

## 已检测项

{列出所有已检测的类别和结果}

## 未检查项

{权限不足或工具缺失导致未检测的项}

## 下一步建议

2~3 条具体建议
```

### 建议示例

| 发现 | 建议 |
|---|---|
| SeImpersonatePrivilege | Potato 系列工具提权到 SYSTEM |
| PrintNightmare 风险 | 评估 PrintNightmare exploit 可用性 |
| 服务路径可写 | 替换服务二进制文件提权 |
| 提权成功后 | 使用 persistence-windows skill 建立持久化 |
| 发现 AutoLogon 凭据 | 使用 hunt-credentials skill 深入收集 |

---

## 七、结构化摘要写入

完成后必须：

1. `appendReconSummary` — 提权向量检测结果摘要
2. `appendReconSummary` — 机器可读字段

结构化 patch 示例：

```json
{
  "privilegeEscalation": {
    "windows": {
      "currentUser": "IIS APPPOOL\\DefaultAppPool",
      "currentLevel": "service_account",
      "vectors": [
        {
          "type": "token_abuse",
          "target": "SeImpersonatePrivilege",
          "exploitability": "high",
          "technique": "Potato series (JuicyPotato/PrintSpoofer/GodPotato)",
          "note": "IIS AppPool 默认拥有此权限"
        },
        {
          "type": "unquoted_service_path",
          "target": "VulnService",
          "exploitability": "high",
          "path": "C:\\Program Files\\Vuln App\\service.exe",
          "technique": "Place binary at C:\\Program.exe"
        },
        {
          "type": "missing_patch",
          "target": "CVE-2021-1732",
          "exploitability": "medium",
          "osVersion": "Windows Server 2019 Build 17763",
          "note": "Win32k elevation of privilege"
        }
      ],
      "checkedCategories": ["tokens", "patches", "services", "alwaysInstallElevated", "uac", "scheduledTasks", "path", "credentials", "printSpooler"]
    }
  },
  "openQuestions": ["persistence-windows"]
}
```

---

## 八、决策规则

| 场景 | 行为 |
|---|---|
| 当前用户已是 SYSTEM/Administrator | 告知用户已有最高权限，此 skill 不适用 |
| 所有检测命令 | 只读操作，不写入任何文件或注册表 |
| 发现 SeImpersonatePrivilege | 单独标注"高危：Potato 系列提权面" |
| 发现 Print Spooler + 补丁缺失 | 单独标注"高危：PrintNightmare 风险" |
| 发现 AlwaysInstallElevated 双键为 1 | 单独标注"高危：MSI 提权面" |
| Build 号低于 CVE 修复基线 | 列出 CVE 编号和概要，不提供 exploit 代码 |
| 域控场景 | 额外检查 Zerologon 补丁状态 |
| WebShell 环境 | 优先检测令牌权限（最常见提权路径） |
| 发现可利用向量 | 立即写入侦察摘要 |

---

## Skill 元数据

- riskLevel: `medium`
- accessMode: `read_only`
- requiredTools: `CommandTools`
- optionalTools: `FileTools`, `RegistryTools`, `ServiceManagerTools`, `ScheduledTaskTools`, `UserAccountTools`, `InstalledSoftwareTools`
- produces: `privilegeEscalation.windows`, `openQuestions`
- recommendedNextSkills: `lateral-move-wmi-psexec`, `persistence-windows`, `hunt-credentials`, `recon-internal-network`
- forbiddenByDefault: 执行实际提权操作、修改注册表、安装服务、加载驱动
