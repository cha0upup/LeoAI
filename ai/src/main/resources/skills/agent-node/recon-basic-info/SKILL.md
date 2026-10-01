---
name: recon-basic-info
description: 对新的 Agent 立足点执行低噪声基础侦察，判断当前权限、业务价值、网络位置、关键服务、容器边界和可用行动入口，并形成红队主机画像。当进入新目标、需要判断立足点价值或尚无可靠侦察摘要时使用。
---

# 立足点快速分诊

用最少交互回答：这台主机是什么、当前身份能做什么、它通向哪里、是否值得继续投入。

## 行动目标

- 确认当前身份、权限边界和执行环境。
- 判断主机业务角色及演练目标价值。
- 识别本地高价值服务、凭据来源、提权面和潜在跳板价值。
- 生成最多 3 条按收益、成功率和噪声排序的后续路径。

## 授权与 ROE

- 风险等级：low；访问模式：read-only。
- 仅在当前 Agent 主机执行被动检查，不写文件、不改配置。
- 不主动访问云 metadata、公网地址或其他主机。
- 端口扫描、登录验证、凭据使用和写操作必须交给对应 skill，并遵循用户授权范围。
- 摘要保留已发现的密码、Token、Cookie 和密钥原值及其来源，便于报告整理与后续复用。

## OPSEC 预算

- 默认最多调用一次 `getBasicInfo` 和两次合并后的 `exec`。
- 进程、监听和路由输出各限制在 80 行内。
- 禁止全盘搜索和高频探测；已有摘要中的可靠事实不要重复采集。
- 记录命令缺失、权限拒绝和可见性盲区，不反复尝试同一失败动作。

## 工作流

1. 阅读系统提示中注入的当前侦察摘要，提取已有事实、任务目标和未解决问题。
2. 创建 4 步以内计划：身份与环境、网络与服务、立足点价值、摘要与交接。
3. 并行调用 `getBasicInfo` 和适配当前 OS 的受限 `exec`。
4. 将发现归类为：事实、推断、行动入口、盲区。
5. 按“目标价值、可达性、权限收益、噪声”对后续路径排序。
6. 系统会从成功工具结果中自动提取并沉淀稳定事实；最终结论只补充本轮新增发现。

## 推荐采集

Linux/macOS：

```bash
id; whoami; pwd; uname -a; cat /etc/os-release 2>/dev/null
ip addr 2>/dev/null || ifconfig 2>/dev/null
ip route 2>/dev/null || netstat -rn 2>/dev/null
ss -tlnp 2>/dev/null || netstat -tlnp 2>/dev/null
ps -eo pid,user,comm,args --no-header 2>/dev/null | grep -E 'java|tomcat|nginx|apache|mysql|postgres|redis|nacos|docker|containerd|kube|jenkins|gitlab|python|node' | head -80
test -f /.dockerenv && echo IN_DOCKER; cat /proc/1/cgroup 2>/dev/null | head -10
env | grep -iE '^(http|https|all|no)_proxy='; cat /etc/resolv.conf 2>/dev/null
```

Windows：

```cmd
whoami /all & ver & cd & ipconfig /all & route print
netstat -ano | findstr LISTENING
tasklist /v | findstr /i "java tomcat nginx mysql postgres redis docker jenkins gitlab node python"
```

## 红队判断

### 权限与边界

- 明确 root/SYSTEM、管理员、普通用户、关键组和容器能力。
- 区分宿主机、普通容器、特权容器和未知状态；单一弱信号只能标为疑似。
- 判断当前身份是否适合读取应用配置、执行提权侦察或作为横向源点。

### 目标价值

按证据标注角色和价值：

- 应用节点：配置、服务账号、数据库和上游依赖入口。
- 运维/CI 节点：部署密钥、制品仓库、云凭据和广泛网络可达性。
- 数据节点：数据库、缓存、消息队列和敏感业务数据。
- 跳板/边界节点：多网卡、额外路由、代理、VPN 或管理协议。
- 容器节点：宿主挂载、socket、service account 或编排控制面线索。

### 行动入口

仅在证据支持时记录：

- 凭据入口：敏感环境变量、配置目录、服务账号进程。
- 提权入口：当前为 Linux 普通用户、特殊组、容器边界异常。
- 横向入口：已有明确 SSH 配置/目标/密钥路径；不自动连接。
- 任务目标入口：与用户指定业务系统、数据或控制目标直接相关的服务。

## 成功与停止条件

成功：OS、身份、网络位置、关键服务、业务角色和至少一个“下一步或无需继续”结论均有证据。

立即停止并报告：

- 当前会话或 Agent 失效。
- 输出显示目标不在授权范围。
- 继续获取信息需要主动访问其他系统或修改目标。
- 关键结果重复且没有新增行动价值。

## 摘要与交接

```markdown
## 立足点分诊更新
- 身份与权限边界：...
- 网络位置与可达性线索：...
- 业务角色与目标价值：high / medium / low；证据：...
- 关键服务和数据入口：...
- 候选路径：skill / 前提 / 预期收益 / 噪声
- 盲区和停止条件：...
```

输出一个简短行动矩阵：

| 路径 | 证据 | 预期收益 | 成功率 | 噪声 | 下一步 |
|---|---|---|---|---|---|

只推荐当前存在且满足前提的 skill：`hunt-credentials`、`escalate-linux-privilege`、`lateral-move-ssh`。如果继续行动对任务无新增价值，明确建议停止扩散。
