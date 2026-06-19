---
name: escalate-linux-privilege
description: 在 Linux 目标上系统性地检测权限提升路径，包括 SUID/SGID 文件、sudo 配置、可写的 cron 脚本、内核版本漏洞、NFS 错误配置、PATH 劫持等。当任务涉及 Linux 提权、SUID、sudo 滥用、内核漏洞时使用。
enabled: true
tags:
  - privilege-escalation
  - linux
---

# Linux 权限提升路径检测

当目标是 Linux 主机且当前权限为普通用户（非 root）时，使用这个 skill 系统性地检测可用的提权路径。

**原则：只检测，不利用。** 所有步骤为信息收集，不执行实际提权操作，由用户决定是否利用。

---

## 一、OPSEC 与环境约束

| 原则 | 说明 |
|---|---|
| 只读操作 | 不修改系统文件、不安装内核模块、不执行实际提权 |
| 低噪声 | `find /` 级搜索加 `timeout`，避免触发 HIDS 文件遍历告警 |
| 避免触发审计 | 不读取 `/etc/shadow`（除非已确认有权限），不执行 `sudo` 带密码 |
| 分阶段收敛 | 先快速检测高价值向量（SUID/sudo），再扩展到内核和服务 |
| 并发执行 | 独立检测项并发执行，减少总耗时 |

**WebShell 环境注意：**
- WebShell 通常以 Web 服务用户运行（www-data/tomcat/nobody），`sudo -l` 可能需要密码
- `find /` 在容器 overlay fs 上性能极差，必须限定目录或加 `timeout`
- 部分命令（`lsb_release`、`showmount`）可能不存在，需回退
- 非交互式 Shell 下 `sudo` 无法输入密码，只能检测 NOPASSWD 规则

---

## 二、目标

检测并报告以下提权向量：

- SUID/SGID 可利用的二进制文件
- sudo 配置中的可利用规则（NOPASSWD）
- 可写的 cron 任务脚本
- 可写的 PATH 目录（PATH 劫持）
- 敏感文件权限（/etc/passwd、/etc/shadow 可写）
- 内核版本和已知 CVE
- 服务配置错误（运行中的特权服务）
- 环境变量注入（LD_PRELOAD 等）
- NFS 错误配置（no_root_squash）
- Docker/LXC 容器逃逸向量

---

## 三、工作流程

### 执行前：制定计划

在任何工具调用前，先输出：

1. **目标**：检测目标 Linux 主机上可用的权限提升路径
2. **路径**：并发检测 SUID/sudo/cron → 内核 CVE 评估 → 服务和容器检测 → 汇总报告
3. **终止条件**：所有检测项已执行并汇总，或当前用户已是 root 时停止

如果已有侦察摘要，先读取 `hostProfile`（内核版本）、`privilegeProfile`（当前用户）、`containerProfile`（容器状态），跳过已有信息。如果当前用户已是 root，此 skill 无需执行。

### 第一阶段：用户与环境确认（并发）

同时执行：

1. **当前用户**：`getCurrentUserInfo` 或 `id && whoami && groups`
2. **内核版本**：`uname -r && cat /etc/os-release 2>/dev/null`
3. **可登录用户**：`cat /etc/passwd | grep -v "nologin\|false" | grep "sh$"`

### 第二阶段：高价值向量检测（并发）

同时执行以下检测：

**1. SUID/SGID 文件：**

```bash
timeout 15 find / -perm -4000 -type f 2>/dev/null
timeout 15 find / -perm -2000 -type f 2>/dev/null
```

对比 GTFOBins 可利用清单，重点关注：

```
nmap, vim, vi, nano, find, awk, python, python3, perl, ruby, php, bash,
sh, more, less, man, cp, mv, tar, zip, unzip, curl, wget, nc, netcat,
openssl, base64, dd, xxd, tee, env, strace, ltrace, gdb, git, svn, ftp,
sftp, ssh, scp, rsync, screen, tmux, docker, lxc, kubectl, ionice, nice,
taskset, time, timeout, journalctl, systemctl, service, mount, umount,
chsh, chown, chmod
```

**2. sudo 配置：**

```bash
sudo -l 2>/dev/null
cat /etc/sudoers 2>/dev/null
ls -la /etc/sudoers.d/ 2>/dev/null
```

重点关注：
- `NOPASSWD` 规则（WebShell 环境下唯一可利用的 sudo 配置）
- 允许执行解释器的规则（python、bash、vim 等）
- 通配符（`*`）滥用
- `env_keep` 中保留 `LD_PRELOAD`

**3. 可写的 cron 任务：**

```bash
cat /etc/crontab 2>/dev/null
ls -la /etc/cron.d/ /etc/cron.daily/ /var/spool/cron/ 2>/dev/null
crontab -l 2>/dev/null
# 查找 root cron 引用的可写脚本
grep -r "^[^#]" /etc/cron* 2>/dev/null | grep -v "^Binary"
```

**4. PATH 劫持：**

```bash
echo $PATH | tr ':' '\n' | while read dir; do test -w "$dir" && echo "WRITABLE: $dir"; done
```

**5. 敏感文件权限：**

```bash
ls -la /etc/passwd /etc/shadow /etc/sudoers 2>/dev/null
find /etc -writable -type f 2>/dev/null | head -20
```

### 第三阶段：内核与组件 CVE 评估

根据内核版本对照以下高危 CVE：

| CVE 编号 | 名称 | 受影响版本 | 修复基线 |
|---|---|---|---|
| CVE-2016-5195 | Dirty COW | 2.6.22 ~ 4.8.3 | 4.8.3 / 4.7.9 / 4.4.26 |
| CVE-2022-0847 | Dirty Pipe | 5.8 ~ 5.16.11 | 5.16.11 / 5.15.25 / 5.10.102 |
| CVE-2024-1086 | nf_tables UAF | 3.15 ~ 6.7.2 | 6.1.76 / 6.6.15 / 6.7.3 |
| CVE-2021-4034 | PwnKit (pkexec) | 所有 polkit 版本 | 发行版修复包 |
| CVE-2021-3156 | Baron Samedit (sudo) | sudo 1.8.2 ~ 1.9.5p1 | sudo 1.9.5p2+ |
| CVE-2023-4911 | Looney Tunables (glibc) | glibc 2.34+ | 发行版修复包 |
| CVE-2023-0386 | OverlayFS EoP | 5.11 ~ 6.1.8 | 5.15.91 / 6.1.9 |
| CVE-2021-3493 | Ubuntu OverlayFS | Ubuntu 特定内核 | Ubuntu 修复包 |
| CVE-2022-0185 | fs_context | 5.1 ~ 5.16 | 发行版修复内核 |
| CVE-2019-5736 | runc 容器逃逸 | runc ≤ 1.0-rc6 | Docker 18.09.2+ |

补充检测组件版本：

```bash
# pkexec 版本（PwnKit）
pkexec --version 2>/dev/null
# sudo 版本（Baron Samedit）
sudo --version 2>/dev/null | head -1
# glibc 版本（Looney Tunables）
ldd --version 2>/dev/null | head -1
```

> **注意**：发行版常通过 backport 修复，排查时应同时核对上游版本和发行版安全公告中的包版本。

### 第四阶段：服务与容器检测（并发）

**1. 特权服务：**

```bash
ps -eo pid,user,comm --no-header | grep "^.*root" | grep -v "\[" | head -30
```

**2. 环境变量注入：**

```bash
sudo -l 2>/dev/null | grep -iE "env_keep|ld_preload|ld_library"
```

**3. Docker / 容器逃逸：**

```bash
# Docker socket 可写
ls -la /var/run/docker.sock 2>/dev/null
# 特权容器检测
cat /proc/self/status 2>/dev/null | grep CapEff
# CapEff = 0000003fffffffff 表示特权容器
test -f /.dockerenv && echo "IN_DOCKER"
cat /proc/1/cgroup 2>/dev/null | grep -i docker | head -3
```

**4. NFS 错误配置：**

```bash
cat /etc/exports 2>/dev/null | grep -v "^#"
showmount -e localhost 2>/dev/null
```

关注 `no_root_squash` 选项。

---

## 四、工具优先级

| 优先级 | 工具 | 用途 |
|---|---|---|
| 1 | `CommandTools` | 所有检测命令执行 |
| 2 | `FileTools` | 读取配置文件（sudoers、crontab、exports） |

命令执行模式选择：

| 命令类型 | 模式 |
|---|---|
| 快速命令（id、uname） | `exec` |
| 可能耗时（find /） | `exec` 或 `exec` + `queryTask` |
| 多个独立命令 | 并发 `exec` |

---

## 五、失败回退

| 场景 | 回退策略 |
|---|---|
| `find /` 超时 | 限定 `/usr/bin`、`/usr/sbin`、`/usr/local/bin` 搜索 SUID |
| `sudo -l` 需要密码 | 跳过，标注"需密码，WebShell 环境不可用" |
| `lsb_release` 不存在 | 用 `cat /etc/os-release` |
| `showmount` 不存在 | 只检查 `/etc/exports` 文件 |
| 容器内 `/proc` 受限 | 从环境变量和挂载点推断 |
| `pkexec --version` 不存在 | 用 `dpkg -l policykit-1` 或 `rpm -q polkit` |
| 权限不足读取 sudoers | 标注后继续其他检测 |

---

## 六、输出格式

```markdown
## Linux 提权路径检测摘要

**当前用户**：{username}（uid={uid}）
**内核版本**：{kernel}
**发现向量数**：{n}（high: x，medium: y，low: z）

---

## 提权向量（按优先级排序）

| # | 类型 | 目标 | 可利用性 | 前提条件 |
|---|------|------|---------|---------|

## 向量详情

### [HIGH] {vector_name}

- **类型**：{suid_binary / sudo / cron / kernel_cve / ...}
- **目标**：{具体文件/配置/CVE}
- **可利用性**：{high / medium / low}
- **利用前提**：{条件说明}
- **参考**：{GTFOBins / CVE 编号}

## 内核 CVE 评估

| CVE | 名称 | 匹配 | 备注 |
|-----|------|------|------|

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
| SUID 可利用 | 用户可通过 GTFOBins 技术提权 |
| 内核 CVE 匹配 | 评估 exploit 可用性和稳定性 |
| Docker socket 可写 | 通过 Docker 挂载宿主机文件系统逃逸 |
| 提权成功后 | 使用 persistence-linux skill 建立持久化 |
| 发现凭据线索 | 使用 hunt-credentials skill 深入收集 |

---

## 七、结构化摘要写入

完成后必须：

1. `manage_recon_summary(action="append")` — 提权向量检测结果摘要
2. `manage_recon_summary(action="append")` — 机器可读字段

结构化 patch 示例：

```json
{
  "privilegeEscalation": {
    "linux": {
      "currentUser": "www-data",
      "currentLevel": "user",
      "vectors": [
        {
          "type": "suid_binary",
          "target": "/usr/bin/find",
          "exploitability": "high",
          "technique": "find . -exec /bin/sh -p \\;",
          "reference": "GTFOBins"
        },
        {
          "type": "kernel_cve",
          "target": "CVE-2021-4034",
          "exploitability": "high",
          "kernelVersion": "5.4.0-42-generic",
          "note": "pkexec SUID + polkit 版本 < 0.120"
        }
      ],
      "checkedCategories": ["suid", "sudo", "cron", "kernel", "path_hijack", "nfs", "container", "services", "env_inject"]
    }
  },
  "openQuestions": ["persistence-linux"]
}
```

---

## 八、决策规则

| 场景 | 行为 |
|---|---|
| 当前用户已是 root | 告知用户已有最高权限，此 skill 不适用 |
| 所有检测命令 | 只读操作，不写入任何文件 |
| `find /` 耗时 | 使用异步模式，等待时先执行其他检测 |
| 发现 docker.sock 可写 | 单独标注"高危：Docker Socket 逃逸" |
| 内核版本匹配 CVE | 列出 CVE 编号和概要，不提供 exploit 代码 |
| 发现 SUID 可利用 | 引用 GTFOBins 技术，不直接执行 |
| WebShell 环境 sudo 需密码 | 跳过，只检测 NOPASSWD 规则 |
| 容器环境 | 额外检测特权容器和 socket 挂载 |
| 发现可利用向量 | 立即写入侦察摘要 |

---

## Skill 元数据

- riskLevel: `medium`
- accessMode: `read_only`
- requiredTools: `CommandTools`, `SuidCapabilityTools`
- optionalTools: `FileTools`, `UserAccountTools`, `ProcessTools`, `ServiceManagerTools`
- produces: `privilegeEscalation.linux`, `openQuestions`
- recommendedNextSkills: `persistence-linux`, `hunt-credentials`, `recon-internal-network`, `detect-container-escape`
- forbiddenByDefault: 执行实际提权操作、修改系统文件、安装内核模块、写入 cron 任务
