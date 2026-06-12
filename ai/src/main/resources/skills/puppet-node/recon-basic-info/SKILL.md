---
name: recon-basic-info
description: 对目标主机执行初始基础信息侦察，第一步完成主机与权限确认，判断当前 WebShell 所在系统、运行用户、应用权限、网络位置、容器状态、出网限制和服务器业务角色，并将发现自动保存为会话侦察摘要。这是进入新目标后的第一步标准操作。
---

# 初始基础信息侦察

当用户进入新目标会话、希望了解目标主机基本情况，或还没有侦察摘要时，使用这个 skill。

---

## 一、WebShell 环境约束

当前 AI 通过 WebShell 与目标交互，存在以下限制：

| 约束 | 影响 | 应对 |
|---|---|---|
| 非交互式 Shell | 无 TTY，无法使用 `sudo -S`、`passwd`、交互式编辑器 | 只用单行命令或管道 |
| 输出可能截断 | 大量输出可能被 WebShell 截断 | 用 `head`/`tail`/`grep` 限制输出量 |
| 字符编码 | 二进制输出或非 UTF-8 可能乱码 | 优先用文本格式输出 |
| 命令超时 | 长时间运行的命令可能被 kill | 避免全盘搜索，用 `timeout` 包裹 |
| 无持久会话 | 每次命令独立执行，无 cd/env 保持 | 用绝对路径，单行完成 |
| 权限受限 | 通常以 Web 服务用户运行（www-data/tomcat/nobody） | 先确认权限边界再决定后续操作 |

---

## 二、OPSEC 指导

| 原则 | 说明 |
|---|---|
| 低噪声优先 | 优先使用读取 `/proc` 和环境变量的方式，减少进程创建 |
| 避免触发 HIDS | 不要执行 `nmap`、`masscan`、`wget`、`nc` 等高告警命令 |
| 不写文件 | 本 skill 仅读取和观察，不创建临时文件 |
| 控制输出量 | 进程列表用 `ps aux --no-header` + `grep`，不要全量输出 |
| 避免重复执行 | 已有侦察摘要时用 `appendReconSummary` 追加，不覆盖 |

**高噪声命令替代方案：**

| 避免 | 替代 |
|---|---|
| `ifconfig`（部分系统已移除） | `ip addr` 或 `cat /proc/net/if_inet6` |
| `netstat`（可能不存在） | `ss -tlnp` 或 `cat /proc/net/tcp` |
| `ps aux`（全量） | `ps -eo pid,user,comm,args --no-header` + `grep java` |
| `find / ...`（全盘） | 限定目录 + `timeout 10` |

---

## 三、目标

第一步完成**主机与权限确认**，收集以下信息并保存为侦察摘要：

- 操作系统类型、版本、内核
- 主机名
- 当前用户和权限（uid/gid/groups）
- WebShell / 应用进程权限边界
- 当前工作目录
- 网络接口与 IP 地址
- 默认网关、路由和 DNS / 代理线索
- 监听端口（本地服务暴露面）
- 是否运行在容器或虚拟化环境中
- 是否存在出网限制或代理限制
- 运行中的 Java 进程（如有）
- 其他关键运行进程
- 服务器业务角色判断

---

## 四、工作流程

### 执行前：制定计划

在任何工具调用前，先输出：

1. **目标**：对目标主机执行初始基础信息侦察，产出主机画像和侦察摘要
2. **路径**：并发调用 BasicInfoTools + CommandTools 收集 OS/用户/网络/进程信息，整理后保存摘要
3. **终止条件**：OS、当前用户、网络接口、监听端口、Java 进程、业务角色判断均已填充，或确认无法获取时停止

### 执行步骤

```
1. 主机与权限确认（第一步，不可跳过）
   ├─ 并发获取：BasicInfoTools + CommandTools
   ├─ 确认 OS 类型 → 决定后续命令集（Linux vs Windows）
   └─ 确认当前用户权限 → 决定可执行操作范围

2. 网络与服务发现
   ├─ 网络接口 + IP
   ├─ 监听端口
   ├─ 路由 / DNS / 代理
   └─ 出网限制判断（被动优先）

3. 进程与环境
   ├─ Java 进程（重点）
   ├─ 其他关键进程
   └─ 容器 / 云环境判断

4. 整理与保存
   ├─ setReconSummary（Markdown）
   ├─ appendReconSummary（JSON）
   └─ 输出给用户
```

---

## 五、工具优先级

| 优先级 | 工具 | 用途 |
|---|---|---|
| 1 | `BasicInfoTools` | OS、用户、主机名、工作目录 |
| 2 | `CommandTools` | 环境变量、Java 进程参数、监听端口 |
| 3 | `NetworkInfoTools`（可选） | `collectAll` 结构化网络拓扑（网卡/ARP/路由/DNS/hosts） |
| 4 | `ProcessTools`（可选） | `listProcesses`/`findProcesses` 进程枚举 |
| 5 | `UserAccountTools`（可选） | `whoami`/`listUsers`/`listGroups` 用户权限信息 |
| 6 | `MountDiskTools`（可选） | `listMountDisks` 磁盘挂载 |
| 7 | `SessionTools` | `setReconSummary`/`appendReconSummary`/`appendReconSummary`/`appendReconSummary` 保存摘要 |

### 推荐命令集

**Linux/macOS（并发执行）：**

```bash
# 身份
id && whoami && groups

# 系统
uname -a && cat /etc/os-release 2>/dev/null

# 网络
ip addr 2>/dev/null || ifconfig
ip route 2>/dev/null || netstat -rn
ss -tlnp 2>/dev/null || netstat -tlnp

# 进程（限制输出）
ps -eo pid,user,comm,args --no-header | grep -E "java|tomcat|nginx|mysql|redis|docker|python|node"

# 容器检测
test -f /.dockerenv && echo "DOCKER"; cat /proc/1/cgroup 2>/dev/null | head -5

# 代理/DNS
echo "HTTP_PROXY=$HTTP_PROXY HTTPS_PROXY=$HTTPS_PROXY"; cat /etc/resolv.conf 2>/dev/null
```

**Windows（并发执行）：**

```cmd
whoami /all
ver
ipconfig /all
route print
netstat -ano | findstr LISTENING
tasklist /v | findstr /i "java tomcat nginx mysql redis"
```

---

## 六、收集项清单

### 操作系统
- OS 名称与版本、内核版本、架构

### 身份与权限
- 当前用户名、UID/GID、所属组
- 是否 root / SYSTEM / Administrator
- 高价值组：sudo、docker、adm、wheel、Remote Management Users
- 当前工作目录、HOME 目录
- 读/写/执行能力（只读检查）

### 主机信息
- 主机名、FQDN
- 云厂商线索（cloud-init、云 Agent、metadata 路由、主机名格式）

### 网络
- 所有网络接口及 IP 地址
- 默认网关
- DNS、代理环境变量、内网网段
- 出网限制判断：可直连 / 需代理 / DNS 受限 / 仅内网 / 未知

### 服务与进程
- 本地监听端口及对应服务
- Java 进程列表（PID、jar 路径、关键 JVM 参数）
- 其他关键进程（Web 服务器、数据库等）

### 容器与业务角色
- 容器状态：是 / 否 / 疑似 / 未确认 + 证据
- 云主机状态：是 / 否 / 疑似 / 未确认 + 证据
- 业务角色：

| 角色 | 特征 |
|---|---|
| 跳板 | SSH/VPN/代理/堡垒机/大量内网路由 |
| 应用服务器 | Tomcat/Spring Boot/Nginx/Apache/Node/PHP |
| 中间件服务器 | MySQL/Redis/Nacos/Kafka/RabbitMQ/ES/ZK |
| 运维节点 | Jenkins/Ansible/kubectl/docker/terraform/CI runner |
| 云主机 | cloud-init/metadata 路由/云监控 agent |

---

## 七、侦察摘要格式

调用 `setReconSummary` 时传入以下格式（字段按需填充，不要空占位）：

```markdown
## 目标基础信息

**OS**：{os_name} {os_version}，内核 {kernel}，{arch}
**主机名**：{hostname}
**当前用户**：{username}（{uid}/{gid}）
**权限级别**：{root/system/admin/普通用户}；关键组/特权：{privileges}
**工作目录**：{cwd}
**应用权限边界**：{read/write/exec summary}

## 容器与云环境

**容器状态**：{container_status}；证据：{container_evidence}
**云主机状态**：{cloud_status}；证据：{cloud_evidence}

## 网络接口

| 接口 | IP 地址 |
|------|---------|
| {iface} | {ip} |

## 监听端口

| 端口 | 协议 | 服务/进程 |
|------|------|---------|
| {port} | TCP | {service} |

## 出网与网络位置

**默认网关 / 路由**：{route_summary}
**DNS / 代理**：{dns_proxy_summary}
**出网限制判断**：{egress_assessment}

## Java 进程

| PID | jar / 主类 | 关键参数摘要 |
|-----|-----------|------------|
| {pid} | {jar} | {args_summary} |

## 其他关键进程

{process_summary}

## 业务角色判断

| 角色 | 判断 | 证据 | 置信度 |
|------|------|------|--------|
| 跳板 | {yes/no/unknown} | {evidence} | {confidence} |
| 应用服务器 | {yes/no/unknown} | {evidence} | {confidence} |
| 中间件服务器 | {yes/no/unknown} | {evidence} | {confidence} |
| 运维节点 | {yes/no/unknown} | {evidence} | {confidence} |
| 云主机 | {yes/no/unknown} | {evidence} | {confidence} |

## 初步判断

{brief_assessment}
```

`初步判断` 必须简要说明：目标系统类型、当前权限是否足以进行后续操作、是否容器/云主机、出网是否受限、业务角色判断、建议的下一步侦察方向。

---

## 八、结构化侦察摘要

调用 `appendReconSummary` 时传入 JSON 对象字符串。未知项使用 `"unknown"`，不要编造：

```json
{
  "schemaVersion": "1.0",
  "hostProfile": {
    "os": "Linux/Windows/macOS/unknown",
    "osVersion": "",
    "kernel": "",
    "arch": "",
    "hostname": "",
    "fqdn": "",
    "cwd": "",
    "home": ""
  },
  "privilegeProfile": {
    "username": "",
    "uid": "",
    "groups": [],
    "level": "root/system/admin/user/unknown",
    "notablePrivileges": [],
    "appPermissionBoundary": "",
    "evidence": []
  },
  "networkProfile": {
    "interfaces": [],
    "routes": [],
    "dns": [],
    "proxy": {},
    "internalCidrs": [],
    "egress": {
      "status": "allowed/proxy_only/internal_only/restricted/unknown",
      "evidence": []
    }
  },
  "containerProfile": {
    "status": "yes/no/suspected/unknown",
    "runtime": "",
    "evidence": []
  },
  "cloudProfile": {
    "status": "yes/no/suspected/unknown",
    "provider": "",
    "evidence": []
  },
  "businessRole": {
    "primary": "jump_host/app_server/middleware_server/ops_node/cloud_host/mixed/unknown",
    "roles": [
      {
        "name": "app_server",
        "matched": true,
        "confidence": "high/medium/low",
        "evidence": []
      }
    ]
  },
  "serviceProfile": {
    "listeningPorts": [],
    "javaProcesses": [],
    "keyProcesses": []
  },
  "openQuestions": []
}
```

---

## 九、决策规则

| 场景 | 行为 |
|---|---|
| 目标是 Windows | 命令集切换为 Windows 命令 |
| 某项信息获取失败 | 标注"未获取"，不留空不跳过 |
| 不确定容器/云/出网 | 标注"未确认"，说明缺哪类证据 |
| 判断业务角色 | 必须引用证据，不要只给结论 |
| 出网探测 | 被动判断优先；主动探测需用户授权且低频 |
| 已有侦察摘要 | 用 `appendReconSummary` 追加，不覆盖 |
| 并发获取 | 不要串行等待，独立工具调用并发执行 |
| 本 skill 完成后 | 侦察摘要保存完成后才输出最终结果 |
| 只读原则 | 不执行任何写操作 |

---

## 十、输出格式

输出简洁 Markdown，包含：

1. `主机与权限确认`
2. `网络位置与出网`
3. `容器与云环境`
4. `监听端口`
5. `Java 进程`（如有）
6. `其他关键进程`
7. `业务角色判断`
8. `初步判断`
9. `下一步建议`：2~3 条具体建议，例如：
   - 发现 Spring Boot 进程 → "建议使用 collect-spring-boot-config skill 收集配置信息"
   - 发现 Tomcat → "建议使用 discover-web-apps skill 发现已部署 Web 应用"
   - 发现数据库监听端口 → "建议使用 collect-jdbc-connection-info skill 提取数据库连接信息"
   - 发现容器环境 → "建议使用 detect-container-escape skill 评估逃逸面"
   - 发现云主机特征 → "建议使用 collect-cloud-metadata skill 提取实例元数据"

末尾注明：侦察摘要已保存，后续会话将自动使用。

---

## Skill 元数据

- `riskLevel`: low
- `accessMode`: read_only
- `requiredTools`: `BasicInfoTools`, `CommandTools`, `SessionTools`
- `optionalTools`: `FileTools`, `NetworkInfoTools`, `ProcessTools`, `UserAccountTools`, `MountDiskTools`
- `produces`: `reconSummary`
- `forbiddenByDefault`: 文件写入、配置修改、主动公网探测、高频端口扫描、提权、持久化
- `recommendedNextSkills`: `discover-web-apps`, `collect-spring-boot-config`, `collect-jdbc-connection-info`, `collect-cloud-metadata`, `detect-container-escape`, `recon-internal-network`, `recon-active-directory`, `collect-kubernetes-secrets`, `analyze-logs-intelligence`
