---
name: hunt-credentials
description: 在已授权的 Agent 立足点上定向搜集与红队任务相关的应用、JVM、数据库、SSH、云和服务账号凭据线索，建立“凭据—身份—目标—用途”映射。当需要扩大访问、接近指定业务目标或判断当前主机凭据价值时使用。
---

# 定向凭据猎取

目标不是尽可能多地抓取秘密，而是找到能推进当前任务的最少凭据，并说明它能用于哪个明确目标。

## 行动目标

- 定位当前进程、当前用户和已确认应用目录中的凭据来源。
- 将每条线索关联到身份、服务、目标和预期权限。
- 优先识别能推进演练目标、横向移动或访问高价值服务的凭据。
- 为受控验证提供最小输入，不在本 skill 内使用凭据登录。

## 授权与 ROE

- 风险等级：medium；访问模式：read-only-sensitive。
- 只检查当前 JVM、当前用户目录和已确认部署目录。
- 不批量读取其他用户目录、浏览器或无关个人数据。
- 不读取或保存私钥正文；优先引用目标上的路径和指纹。
- 不下载、上传或修改凭据文件，不使用发现的凭据发起认证。
- 已发现的凭据在回复、计划结果、摘要和报告中保留完整原值及来源。

## OPSEC 预算

- Java 目标最多一次 `harvestAll`。
- 系统枚举最多一次合并 `exec`；递归搜索只允许一个已确认目录。
- 候选文件先列元数据，按任务相关性选择后再读取；不要整目录导出。
- 结果达到任务所需的可用凭据后停止扩张搜索。

## 工作流

1. 读取侦察摘要和用户目标，确定所需凭据类型与目标服务。
2. 创建 4 步以内计划：运行时采集、系统线索、定向读取、价值排序与交接。
3. Java 应用调用一次 `harvestAll`；非 Java 目标跳过。
4. 用一次受限 `exec` 枚举环境、进程参数和当前用户候选文件。
5. 只对高相关候选调用 `readTextFile`、`searchFileContent` 或 `ResourceTools`。
6. 解析占位符和连接关系，构建凭据—目标矩阵，去重并保留原值。
7. 输出可复用线索及来源；系统会从成功工具结果中自动维护侦察摘要。

## 系统枚举

Linux/macOS：

```bash
echo '=== ENV ==='; env | grep -iE 'password|passwd|secret|token|credential|api[_-]?key|access[_-]?key|jdbc|redis|nacos' 2>/dev/null
echo '=== PROCESS ARGS ==='; ps -eo pid,user,comm,args --no-header 2>/dev/null | grep -iE 'password|passwd|secret|token|jdbc:|redis|nacos|vault|aws|azure|gcp' | grep -v grep | head -80
echo '=== USER CANDIDATES ==='; find "$HOME" -maxdepth 3 -type f \( -name '.env' -o -name 'credentials' -o -name 'config.json' -o -name '.pgpass' -o -name '.my.cnf' -o -name 'config' \) -print 2>/dev/null | head -50
echo '=== SSH METADATA ==='; find "$HOME/.ssh" -maxdepth 1 -type f -exec ls -ld {} \; 2>/dev/null
```

Windows：

```cmd
set | findstr /i "password passwd secret token credential api_key access_key jdbc redis nacos"
wmic process get ProcessId,Name,CommandLine | findstr /i "password secret token jdbc redis nacos vault"
dir /a "%USERPROFILE%\.ssh" 2>nul & dir /a "%USERPROFILE%\.aws" 2>nul
```

## 定向策略

- JDBC：解析 URL、用户、认证来源和业务库；判断是否只绑定本机或指向高价值数据节点。
- Redis/Nacos：解析地址、namespace/database、账号和占位符来源。
- SSH：记录私钥路径、属主、权限、算法、指纹、SSH config 和对应目标；不读取正文。
- 云/CI：只在任务涉及该平台时读取选定配置，记录账号或 key id、权限线索和目标环境。
- Shiro/应用密钥：记录来源、用途和是否为默认值；不在本 skill 构造利用载荷。
- `${ENV_NAME}`、Vault/KMS 引用：追溯实际来源；无法解析时保留引用关系，不猜值。

## 凭据价值排序

每条凭据按以下维度评分：

- 任务相关性：是否直接接近用户指定目标。
- 权限潜力：普通应用、运维、云、数据库或高权限服务账号。
- 目标明确度：是否已有对应主机、服务、端口和用户名。
- 可验证性：能否用当前已存在 skill 受控验证。
- 噪声与锁定风险：认证日志、失败计数、第三方系统影响。

禁止因为“可能有用”就展开所有秘密。优先保留目标明确、成功率高、噪声可控的 3 条路径。

## 凭据保真

- 完整展示凭据原值、类型、长度、来源和置信度。
- URL 密码、查询 Token、Authorization 头和 Cookie 保留完整原值。
- 摘要和报告保存路径、变量名、账号、key id、指纹、完整凭据和用途，便于整理与后续复用。

## 成功与停止条件

成功：获得至少一条与任务相关、来源可靠且目标明确的凭据线索，或确认授权范围内没有可用线索。

立即停止并报告：

- 下一步需要读取其他用户、导出秘密文件或访问第三方系统。
- 发现的秘密与演练目标无关。
- 已获得足够推进当前最优路径的输入。
- 输出可能包含大批秘密或超出安全展示范围。

## 摘要与交接

```markdown
## 凭据行动情报更新
- 类型与身份：...
- 凭据原值与状态：原值 / 占位符 / 未读取
- 来源与证据：...
- 对应目标：host/service/account
- 预期权限与任务价值：...
- 验证前提、噪声和锁定风险：...
```

输出凭据—目标矩阵。只有目标、用户名和私钥路径都明确且用户授权连接时，才建议 `lateral-move-ssh`；本机权限不足且 Linux 提权能推进目标时，建议 `escalate-linux-privilege`。否则说明缺少的具体能力，不推荐不存在的 skill。
