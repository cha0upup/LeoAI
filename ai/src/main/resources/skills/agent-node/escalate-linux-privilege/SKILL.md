---
name: escalate-linux-privilege
description: 在 Linux Agent 立足点上以只读方式发现并排序本地提权候选路径，覆盖 sudo、SUID/SGID、cron、PATH、服务、容器、Docker socket、NFS 和版本风险。当红队任务需要扩大本机权限、突破容器边界或判断是否值得投入提权时使用；本 skill 不执行利用。
---

# Linux 提权路径优选

目标不是罗列所有弱点，而是找出最可能、收益最高、噪声最低且能推进任务的提权路径。

## 行动目标

- 确认当前权限是否阻碍演练目标。
- 发现本地或容器边界中的可操作提权候选。
- 对候选路径进行前提验证和红队优先级排序。
- 为后续人工或独立验证提供证据，不执行提权。

## 授权与 ROE

- 风险等级：medium；访问模式：read-only。
- 当前已是 root 时停止，转而评估目标达成或后续行动价值。
- 不执行利用、下载代码、编译载荷、写 cron、修改服务或触发崩溃风险测试。
- `sudo` 仅使用非交互模式，不提交密码。
- 摘要不保存可直接执行的利用代码。

## OPSEC 预算

- 先执行高价值低噪声检查，再决定是否扩大搜索。
- SUID/SGID 优先限定标准二进制目录；全根文件系统搜索必须 `-xdev`、限时并限制输出。
- 版本匹配只作为候选，不运行 PoC 验证。
- 独立只读项并发执行；异步命令获取必要输出后调用 `stopTask` 释放资源。

## 工作流

1. 读取侦察摘要，确认任务目标、当前身份、OS、内核和容器状态。
2. 判断提升权限是否会实际推进目标；若不会，建议停止而不是机械扫描。
3. 创建 4 步以内计划：身份与快速路径、配置与文件权限、容器与版本、排序与交接。
4. 执行第一轮低噪声检查；只在没有足够候选时扩大 SUID/cron/服务范围。
5. 对每个候选补齐证据、必要前提、预计收益、稳定性和检测面。
6. 只保留最多 5 条有行动价值的路径，写入一次摘要。

## 快速检查

```bash
id; whoami; groups; uname -r; cat /etc/os-release 2>/dev/null
sudo -n -l 2>/dev/null
printf '%s\n' "$PATH" | tr ':' '\n' | while IFS= read -r dir; do [ -n "$dir" ] && [ -w "$dir" ] && echo "WRITABLE_PATH=$dir"; done
ls -ld /etc/passwd /etc/shadow /etc/sudoers /etc/sudoers.d 2>/dev/null
find /usr/bin /usr/sbin /usr/local/bin /usr/local/sbin -xdev -type f \( -perm -4000 -o -perm -2000 \) -ls 2>/dev/null
cat /etc/crontab 2>/dev/null; crontab -l 2>/dev/null
find /etc/cron.d /etc/cron.daily /etc/cron.hourly -xdev -maxdepth 2 -type f -ls 2>/dev/null
ps -eo pid,user,comm,args --no-header 2>/dev/null | awk '$2=="root" {print}' | head -50
ls -l /var/run/docker.sock 2>/dev/null
grep -E 'Cap(Inh|Prm|Eff|Bnd|Amb)' /proc/self/status 2>/dev/null
test -f /.dockerenv && echo IN_DOCKER; cat /proc/1/cgroup 2>/dev/null | head -10
grep -v '^[[:space:]]*#' /etc/exports 2>/dev/null
```

仅在第一轮没有足够证据时执行：

```bash
timeout 15 find / -xdev -type f \( -perm -4000 -o -perm -2000 \) -print 2>/dev/null | head -200
find /etc -xdev -type f -writable 2>/dev/null | head -40
sudo --version 2>/dev/null | head -1; pkexec --version 2>/dev/null; ldd --version 2>/dev/null | head -1
```

如果 `timeout` 不存在，不运行无边界 `find /`。

## 路径分析

- sudo：验证规则、目标二进制、参数限制、环境保留和是否确为 `NOPASSWD`。
- SUID/SGID：核对所有者、权限、真实路径、版本、挂载选项和可控输入，不凭文件名下结论。
- cron/服务：只有高权限任务实际引用当前用户可写文件或目录时才标为高置信度。
- PATH：必须证明高权限程序使用相对命令；单独的可写 PATH 目录不是完整路径。
- Docker/容器：分别评估 socket、能力、宿主挂载、设备和 namespace 证据。
- 内核/组件：记录发行版包版本和待核对公告；考虑 backport、稳定性与崩溃风险。
- NFS：确认导出可访问、危险选项和挂载前提后再提升优先级。

## 红队排序

按以下顺序评分：

1. 是否能推进当前任务目标。
2. 当前前提是否已经满足。
3. 权限收益：root、宿主机或特权服务身份。
4. 稳定性和可回滚性。
5. EDR、auditd、sudo、服务和文件完整性日志噪声。

优先配置错误和既有授权路径，其次文件权限与服务路径，最后才考虑内核类候选。

## 成功与停止条件

成功：至少一条候选路径的目标、证据、必要前提、权限收益和噪声均明确，或确认没有值得投入的路径。

立即停止并报告：

- 当前已是 root 或已具备完成目标所需权限。
- 继续验证需要写文件、触发漏洞或改变服务状态。
- 只剩高崩溃风险、低置信度版本候选。
- 容器或系统范围不在用户授权内。

## 摘要与交接

```markdown
## Linux 提权路径更新
- 当前身份与任务阻碍：...
- 候选路径与证据：...
- 已满足/缺失前提：...
- 预期权限收益：...
- 成功率、稳定性和噪声：...
- 推荐顺序与停止理由：...
```

输出最多 5 条行动路径矩阵。发现凭据入口时建议 `hunt-credentials`；用户明确要求并已启用时，获得所需权限后可交接 `persistence-linux`。不要声称本 skill 已完成提权。
