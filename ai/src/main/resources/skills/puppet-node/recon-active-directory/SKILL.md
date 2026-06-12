---
name: recon-active-directory
description: Windows Active Directory 域环境侦察：域控定位、域用户/组枚举、GPP 密码、SPN 发现、域信任关系、ACL 分析。当目标处于 Windows 域环境且需要了解域拓扑和攻击面时使用。
---

# Active Directory 域侦察

当目标主机加入 Windows 域且已获取域用户凭据时，使用本 skill 系统化枚举域环境信息，为 Kerberos 攻击、横向移动和域控攻击提供情报基础。

> 前置条件：目标在域中 + 已获取至少一个域用户凭据（普通域用户即可完成大部分枚举）。

---

## 一、OPSEC 与约束

| 原则 | 说明 |
|---|---|
| 标准查询 | 使用 net/dsquery/PowerShell 等内置工具，不上传第三方工具 |
| 批量限制 | LDAP 查询加分页，避免一次性拉取过大结果集 |
| 不修改 | 不修改 AD 对象、不重置密码、不更改 ACL |
| 不攻击 | 仅侦察不利用（Kerberoast/AS-REP Roast 放在 exploit-kerberos） |
| 日志意识 | 大量 LDAP 查询可能触发 SIEM 告警 |

---

## 二、目标

- 定位域控制器（DC）及其 IP/版本
- 枚举域用户、特权组（Domain Admins、Enterprise Admins）
- 发现 SPN（Kerberoast 目标）
- 查找 GPP 密码、描述字段中的密码
- 识别域信任关系
- 评估当前用户在域中的权限边界

---

## 三、Skill 元数据

- riskLevel: `medium`
- accessMode: `read_only`
- requiredTools: `CommandTools`
- optionalTools: `FileTools`, `RegistryTools`
- produces: `domainProfile.controllers[]`, `domainProfile.users[]`, `domainProfile.groups[]`, `domainProfile.spns[]`, `domainProfile.trusts[]`, `credentials.fromGPP[]`, `openQuestions`
- structuredPatchPaths: `domainProfile.controllers[]`, `domainProfile.groups[]`, `domainProfile.spns[]`, `domainProfile.trusts[]`, `credentials.fromGPP[]`
- recommendedNextSkills: `exploit-kerberos`, `lateral-move-wmi-psexec`, `hunt-credentials`
- forbiddenByDefault: 修改 AD 对象、密码重置、DCSync（需提权后单独确认）

---

## 四、工作流程

### 执行前：制定计划

1. **目标**：系统化枚举 Active Directory 域环境，产出域拓扑、特权账户和攻击路径地图。
2. **路径**：确认域身份 → 定位 DC → 枚举用户和组 → SPN 发现 → GPP/描述密码搜索 → 域信任。
3. **终止条件**：完成 DC 定位、特权组枚举、SPN 列表和 GPP 检查，或确认无域环境时停止。

### 第一阶段：域环境确认

```cmd
:: 确认域成员身份
systeminfo | findstr /B /C:"Domain"
whoami /all
net config workstation

:: 当前域和站点
nltest /dsgetdc:%USERDOMAIN%
nltest /domain_trusts

:: 域控列表
nltest /dclist:%USERDOMAIN%
nslookup -type=SRV _ldap._tcp.dc._msdcs.%USERDNSDOMAIN%
```

### 第二阶段：域用户与特权组枚举

```cmd
:: 域管理员
net group "Domain Admins" /domain
net group "Enterprise Admins" /domain
net group "Schema Admins" /domain
net group "Account Operators" /domain
net group "Backup Operators" /domain

:: 域用户列表（前 50）
net user /domain | more

:: 特定用户详情
net user <username> /domain
```

PowerShell 扩展（如可用）：

```powershell
# 枚举所有启用账户
Get-ADUser -Filter {Enabled -eq $true} -Properties Description,LastLogonDate |
    Select Name,SamAccountName,Description,LastLogonDate |
    Sort LastLogonDate -Descending | Select -First 50

# 密码永不过期的账户（服务账户候选）
Get-ADUser -Filter {PasswordNeverExpires -eq $true -and Enabled -eq $true} -Properties Description

# 描述字段中可能含密码
Get-ADUser -Filter {Description -like "*pass*"} -Properties Description
Get-ADComputer -Filter {Description -like "*pass*"} -Properties Description
```

### 第三阶段：SPN 发现（Kerberoast 目标）

```cmd
:: 使用 setspn 枚举
setspn -T %USERDOMAIN% -Q */*
```

```powershell
# PowerShell 方式
Get-ADUser -Filter {ServicePrincipalName -ne "$null"} -Properties ServicePrincipalName,Description |
    Select Name,SamAccountName,ServicePrincipalName,Description
```

**高价值 SPN 模式：**

| SPN 前缀 | 含义 |
|---|---|
| MSSQLSvc/ | SQL Server 服务账户 |
| HTTP/ | Web 服务（IIS/Exchange） |
| exchangeMDB/ | Exchange 服务 |
| TERMSRV/ | 远程桌面 |
| ldap/ | LDAP 服务 |

### 第四阶段：GPP 密码搜索

```cmd
:: 搜索 SYSVOL 中的 GPP XML 文件
dir \\%USERDNSDOMAIN%\SYSVOL\%USERDNSDOMAIN%\Policies\ /s /b | findstr /i "groups.xml services.xml scheduledtasks.xml datasources.xml drives.xml printers.xml"

:: 读取找到的 XML（cpassword 字段包含可解密的密码）
type \\%USERDNSDOMAIN%\SYSVOL\...\groups.xml
```

GPP 密码解密：`cpassword` 字段使用公开 AES 密钥加密，可直接解密。

### 第五阶段：域信任关系

```cmd
nltest /domain_trusts /all_trusts /v
```

```powershell
Get-ADTrust -Filter * | Select Name,Direction,TrustType,IntraForest
```

**信任方向含义：**

| 方向 | 攻击路径 |
|---|---|
| 双向信任 | 可直接访问对方域资源 |
| 入站信任 | 对方信任我们，可被利用 |
| 林内信任 | 同森林通常完全信任 |
| 外部信任 | 跨森林，可能有 SID 过滤 |

### 第六阶段：补充枚举

```cmd
:: 计算机账户
net group "Domain Computers" /domain

:: 组织单元
dsquery ou -limit 50

:: 密码策略
net accounts /domain

:: AS-REP Roastable 用户（不要求预认证）
:: PowerShell
Get-ADUser -Filter {DoesNotRequirePreAuth -eq $true} -Properties DoesNotRequirePreAuth
```

---

## 五、工具优先级

| 优先级 | 工具 | 用途 |
|---|---|---|
| 1 | `CommandTools.exec` | net/nltest/setspn/dsquery 命令 |
| 2 | `CommandTools.exec` | PowerShell AD 枚举（可能较慢） |
| 3 | `FileTools.readTextFile` | 读取 SYSVOL 中的 GPP XML 文件 |
| 4 | `RegistryTools` | 读取本地缓存的域信息 |

---

## 六、输出格式

```markdown
## Active Directory 域侦察摘要

**域名**：{domain}
**域功能级别**：{level}
**域控数量**：{n}
**域用户数（估算）**：{n}
**发现 SPN 数**：{n}
**GPP 密码**：{found / none}

---

## 域控制器

| 主机名 | IP | 角色 | OS 版本 |
|--------|-----|------|---------|

## 特权组成员

### Domain Admins
| 用户名 | 描述 | 最后登录 |
|--------|------|---------|

## SPN 列表（Kerberoast 目标）

| 用户 | SPN | 描述 | 优先级 |
|------|-----|------|--------|

## GPP / 描述字段密码

| 来源 | 用户名 | 密码 | 备注 |
|------|--------|------|------|

## 域信任关系

| 受信域 | 方向 | 类型 | 可利用性 |
|--------|------|------|---------|

## 攻击路径评估

{基于枚举结果的攻击路径分析}

## 下一步建议

2~3 条建议
```

---

## 七、结构化摘要写入

```json
{
  "domainProfile": {
    "domainName": "corp.local",
    "functionalLevel": "2016",
    "controllers": [
      {"hostname": "DC01", "ip": "10.0.1.1", "os": "Windows Server 2019"}
    ],
    "groups": {
      "domainAdmins": ["admin", "svc_backup"],
      "enterpriseAdmins": ["admin"]
    },
    "spns": [
      {"user": "svc_sql", "spn": "MSSQLSvc/db01:1433", "description": "SQL Service"}
    ],
    "trusts": [
      {"domain": "partner.local", "direction": "bidirectional", "type": "external"}
    ],
    "passwordPolicy": {"minLength": 8, "lockoutThreshold": 5, "maxAge": "90 days"}
  },
  "credentials": {
    "fromGPP": [
      {"source": "SYSVOL/groups.xml", "username": "local_admin", "password": "P@ss123!", "confidence": "high"}
    ]
  },
  "openQuestions": ["exploit-kerberos", "lateral-move-wmi-psexec"]
}
```

---

## 八、决策规则

| 场景 | 行为 |
|---|---|
| 不在域中 | 终止，报告非域环境 |
| 普通域用户 | 仍可完成大部分枚举（SPN、用户、组、信任） |
| PowerShell 不可用 | 回退到 net/nltest/dsquery |
| AD 模块未安装 | 使用 .NET LDAP 查询或 net 命令 |
| 发现域管凭据 | 最高优先级标记 |
| 发现 SPN | 建议 exploit-kerberos (Kerberoast) |
| LDAP 查询被限速 | 加分页参数，降低查询频率 |
