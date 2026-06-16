---
name: collect-spring-boot-config
description: 收集 Spring Boot 应用的配置来源，包括外部配置文件、启动参数、环境变量、classpath 资源和 fat jar 内部资源。当任务涉及 application.yml、bootstrap.yml、spring.profiles.active、spring.config.location、fat jar 配置读取或 Spring Boot 配置覆盖关系时使用。
enabled: true
---

# 收集 Spring Boot 配置

当用户希望定位 Spring Boot 应用配置、确定配置来源、分析 profile、生效顺序或读取 fat jar 内部配置时，使用这个 skill。

---

## 一、OPSEC 与环境约束

| 原则 | 说明 |
|---|---|
| 只读操作 | 不修改配置、不重启应用、不写入环境变量 |
| 低噪声 | 不触发业务接口、不全盘搜索 |
| 不猜测 | 只报告有证据支持的配置来源 |

**WebShell 环境注意：**
- fat jar 内部配置需要 `ResourceTools` 读取，不能直接 `cat`
- 外部配置文件路径可能从进程参数中的 `spring.config.location` 获取
- 多 profile 场景需确认 `spring.profiles.active` 的实际值
- 配置覆盖优先级：命令行参数 > 环境变量 > 外部配置 > classpath 内配置

---

## 二、目标

确认以下内容：

- 外部配置文件位置和内容
- 生效的 profile（spring.profiles.active）
- spring.config.location / spring.config.additional-location
- 环境变量注入的配置
- classpath 资源和 fat jar 内资源
- 配置覆盖关系和优先级
- 与数据源、Redis、Nacos 等相关的关键配置项

---

## 三、工作流程

### 执行前：制定计划

在任何工具调用前，先输出：

1. **目标**：收集 Spring Boot 应用的配置来源，产出配置分层清单和关键配置项
2. **路径**：识别 Java 进程和部署方式 → 按"启动参数 → 环境变量 → 外部配置文件 → classpath 资源"顺序收集 → 整理优先级关系
3. **终止条件**：找到生效的配置文件并读取关键配置项，或确认所有来源均无法获取时停止

如果已有侦察摘要，先读取 `serviceProfile.webApps` 和 `keyPaths.configFiles`。多个 Java 进程分别处理。

### 第一阶段：识别部署方式（并发）

同时执行：

1. **Java 进程参数**：`exec`
   - 提取 jar 路径、`-Dspring.config.location`、`-Dspring.profiles.active`
2. **环境变量**：`exec`
   - 过滤 `SPRING_*`、`SERVER_*`

### 第二阶段：外部配置文件

根据第一阶段确定的路径读取配置文件：

| 来源 | 搜索路径 |
|---|---|
| spring.config.location 指定 | 直接读取 |
| jar 同级目录 | `{jar_dir}/application.yml`、`{jar_dir}/config/` |
| 工作目录 | `./application.yml`、`./config/` |
| 系统路径 | `/etc/{app}/`、`/opt/{app}/config/` |

**重点搜索项：**

```
spring.profiles.active
spring.config.location
spring.config.additional-location
spring.datasource.*
spring.redis.*
spring.cloud.nacos.*
server.port
server.servlet.context-path
management.server.port
```

### 第三阶段：classpath 资源（fat jar）

使用 `ResourceTools` 读取：

- `application.yml` / `application.yaml` / `application.properties`
- `application-{profile}.yml`
- `bootstrap.yml` / `bootstrap.yaml` / `bootstrap.properties`
- `bootstrap-{profile}.yml`

### 第四阶段：整理配置分层

按 Spring Boot 配置优先级整理：

1. 命令行参数（`--spring.*`）
2. 系统属性（`-Dspring.*`）
3. 环境变量（`SPRING_*`）
4. 外部配置文件（spring.config.location 指定）
5. jar 同级 config/ 目录
6. jar 同级目录
7. classpath 内 config/
8. classpath 根目录

---

## 四、工具优先级

| 优先级 | 工具 | 用途 |
|---|---|---|
| 1 | `CommandTools` | `exec`、`exec` |
| 2 | `FileTools` | 读取外部配置文件 |
| 3 | `ResourceTools` | fat jar classpath 资源 |
| 4 | `BasicInfoTools` | OS 和部署环境 |

---

## 五、失败回退

| 场景 | 回退策略 |
|---|---|
| 外部配置文件不存在 | 尝试 classpath 资源 |
| ResourceTools 读取失败 | 尝试解压 jar 后用 FileTools 读取 |
| profile 不确定 | 从进程参数和环境变量确认，全部记录 |
| 配置文件权限不足 | 标注后从进程参数推断关键配置 |
| exec 返回 mode=async | 继续轮询，不重复发起 |
| 多个 Java 进程 | 每个独立处理 |

---

## 六、输出格式

```markdown
## Spring Boot 配置摘要

**应用数量**：{n}
**主要 Profile**：{profile}
**部署方式**：{fat jar / war / exploded}

---

## 配置来源（按优先级排序）

| 优先级 | 来源类型 | 路径/内容 | 证据 |
|--------|---------|---------|------|

## 关键配置项

| 配置键 | 值 | 来源 |
|--------|-----|------|

## 配置分层关系

{覆盖关系说明}

## 缺失项

{未找到或权限不足的来源}

## 下一步建议

2~3 条具体建议
```

### 建议示例

| 发现 | 建议 |
|---|---|
| spring.datasource 配置 | 使用 collect-jdbc-connection-info skill 提取完整数据库连接信息 |
| Redis 配置 | 使用 collect-redis-credentials skill 提取 Redis 凭据 |
| Nacos 配置 | 使用 collect-nacos-config skill 拉取配置中心内容 |
| 多个 profile | 检查 application-prod.yml 获取生产环境配置 |
| 加密占位符 | 使用 hunt-credentials skill 搜索明文凭据 |

---

## 七、结构化摘要写入

完成后必须：

1. `manage_recon_summary(action="append")` — 配置来源、profile、关键配置和证据
2. `manage_recon_summary(action="append")` — 机器可读字段

结构化 patch 示例：

```json
{
  "applicationProfile": {
    "springBoot": [
      {
        "appName": "unknown",
        "jarPath": "/opt/app/app.jar",
        "activeProfiles": ["prod"],
        "serverPort": 8080,
        "contextPath": "/",
        "configSources": [
          {
            "type": "external_file",
            "path": "/opt/app/config/application-prod.yml",
            "priority": "high",
            "evidence": "spring.config.location"
          }
        ],
        "datasourceHints": true,
        "redisHints": true,
        "nacosHints": false,
        "confidence": "high"
      }
    ]
  },
  "keyPaths": {
    "configFiles": ["/opt/app/config/application-prod.yml"]
  },
  "openQuestions": ["collect-jdbc-connection-info", "collect-redis-credentials"]
}
```

---

## 八、决策规则

| 场景 | 行为 |
|---|---|
| 不假设配置生效 | 除非有明确来源证据 |
| 多来源冲突 | 说明覆盖关系，不只保留一个 |
| exec 返回 mode=async | 继续轮询，不重复发起 |
| 发现 spring.datasource | 建议触发 JDBC 收集 |
| 只找到文件名无内容 | 标记为中低置信度 |
| 发现配置后 | 立即写入侦察摘要 |

---

## Skill 元数据

- riskLevel: `low`
- accessMode: `read_only`
- requiredTools: `BasicInfoTools`, `CommandTools`, `FileTools`
- optionalTools: `ResourceTools`, `CredentialHarvestTools`
- produces: `applicationProfile.springBoot`, `keyPaths.configFiles`, `serviceProfile.javaProcesses`, `openQuestions`
- recommendedNextSkills: `collect-jdbc-connection-info`, `collect-redis-credentials`, `collect-nacos-config`, `hunt-credentials`
- forbiddenByDefault: 修改配置、重启应用、写入环境变量、触发业务接口
