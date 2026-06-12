---
name: discover-web-apps
description: 发现目标主机上部署的 Web 应用，识别 Tomcat、Spring Boot、Nginx、PHP 等常见 Web 容器和框架的部署路径、监听端口和应用列表。当任务涉及找到 webroot、war 包、部署目录、Web 应用入口、Tomcat webapps 或识别目标是什么 Web 框架时使用。
---

# Web 应用部署发现

当用户希望找到目标主机上运行的 Web 应用、定位部署路径和访问入口时，使用这个 skill。

---

## 一、OPSEC 与环境约束

| 原则 | 说明 |
|---|---|
| 只读操作 | 不写文件、不上传、不修改配置、不触发业务接口 |
| 低噪声 | 不使用 `find /` 全盘搜索，限定已知路径 + `timeout` |
| 避免触发 WAF | 不主动发起 HTTP 请求到业务端口（除非用户明确要求） |
| 进程优先 | 优先从进程参数和监听端口推断，减少文件系统遍历 |

---

## 二、目标

发现以下信息：

- Web 容器类型（Tomcat、Jetty、Undertow、Nginx、Apache、IIS 等）
- 部署路径（webapps 目录、webroot、war 文件位置）
- 监听端口和访问地址
- 已部署应用列表（war 名、context path、主类）
- Web 框架类型（Spring Boot、Spring MVC、JSP、PHP、Node.js 等）
- 静态资源目录和配置文件位置

---

## 三、工作流程

### 执行前：制定计划

在任何工具调用前，先输出：

1. **目标**：发现目标主机上部署的 Web 应用，产出容器类型、部署路径、监听端口和应用列表
2. **路径**：第一阶段并发执行端口扫描 + Java 进程检查 + 常见容器路径探测；第二阶段根据结果深入检查对应容器配置
3. **终止条件**：找到至少一个 Web 应用的部署路径和访问入口，或确认目标无 Web 服务时停止

### 第一阶段：快速定位（并发）

同时执行：

1. **监听端口**：
   - Linux：`ss -tlnp` 或 `cat /proc/net/tcp`
   - Windows：`netstat -ano | findstr LISTENING`
   - 重点关注：80、443、8080、8443、8009、9090、7001、4848

2. **Java 进程检查**：
   - `exec` — 从进程参数识别 Tomcat、Spring Boot jar、war 启动模式
   - 关键词：`-jar`、`-cp`、`catalina.home`、`server.port`、`BOOT-INF`

3. **常见路径探测**（限定目录，不全盘搜索）：

| 容器 | Linux 路径 | Windows 路径 |
|---|---|---|
| Tomcat | `/opt/tomcat*`、`/usr/share/tomcat*`、`/usr/local/tomcat*` | `C:\tomcat*`、`C:\Program Files\Apache*\Tomcat*` |
| Nginx | `/etc/nginx`、`/usr/local/nginx` | `C:\nginx*` |
| Apache | `/etc/apache2`、`/etc/httpd`、`/var/www/html` | `C:\Apache*` |
| PHP | `/var/www`、`/srv/www` | `C:\inetpub\wwwroot` |

### 第二阶段：深入检查

根据第一阶段发现的容器类型选择对应检查项：

**Tomcat：**
- `${catalina.home}/webapps/` 目录下的 war 和目录
- `${catalina.home}/conf/server.xml` — Host 和 Context 配置
- 如有 `CatalinaTools`，直接用 `getCatalinaInfo`

**Spring Boot (fat jar)：**
- 从进程参数提取 jar 路径和 `server.port`
- 用 `ResourceTools` 读取 classpath 内 `application.yml`/`application.properties`
- 关注：`server.port`、`server.servlet.context-path`、`management.server.port`

**Nginx：**
- `/etc/nginx/nginx.conf` 和 `/etc/nginx/sites-enabled/`
- 提取 `server_name`、`listen`、`root`、`proxy_pass`
- 确认反向代理目标

**Apache httpd：**
- `/etc/apache2/sites-enabled/` 或 `/etc/httpd/conf.d/`
- 提取 `VirtualHost`、`DocumentRoot`、`ProxyPass`

---

## 四、工具优先级

| 优先级 | 工具 | 用途 |
|---|---|---|
| 1 | `CommandTools` | 端口、进程、目录探测、搜索 |
| 2 | `FileTools` | 读取 server.xml、nginx.conf 等配置 |
| 3 | `ResourceTools` | fat jar 内部 classpath 配置 |
| 4 | `CatalinaTools`（如可用） | `getCatalinaInfo` Servlet/Filter/Controller 列表 |

---

## 五、失败回退

| 场景 | 回退策略 |
|---|---|
| `ss` 不存在 | 用 `cat /proc/net/tcp` 解析十六进制端口 |
| 进程参数被截断 | 用 `cat /proc/<pid>/cmdline` 补充 |
| 配置文件权限不足 | 标注"权限不足"，从进程参数推断 |
| 无 Java 进程 | 检查 PHP-FPM、Node、Python 进程 |
| 第一阶段无发现 | 检查 systemd 服务：`systemctl list-units --type=service --state=running` |

---

## 六、输出格式

```markdown
## Web 应用发现摘要

**发现应用数量**：{n}
**主要 Web 容器**：{container_type}

---

## 监听端口

| 端口 | 协议 | 服务 | 进程 |
|------|------|------|------|

## 已部署应用

| 应用名 / Context Path | 部署路径 | 框架 | 访问地址 |
|----------------------|---------|------|---------|

## 配置文件

| 文件路径 | 关键配置摘要 |
|---------|------------|

## 反向代理关系

{前端 → 后端映射}

## 未检查项

{权限不足或未找到的路径}

## 下一步建议

2~3 条具体建议
```

### 建议示例

| 发现 | 建议 |
|---|---|
| Spring Boot fat jar | 使用 collect-spring-boot-config skill 收集配置 |
| Tomcat webapps | 使用 FileTools 检查应用文件结构 |
| Nginx 反向代理 | 追溯后端真实服务地址 |
| 多个应用 | 优先对核心业务应用使用 collect-jdbc-connection-info |
| Actuator 端点暴露 | 使用 exploit-spring-actuator skill 枚举端点 |

---

## 七、结构化摘要写入

完成后必须同时写入两类摘要：

1. `appendReconSummary` — 面向用户的 Markdown 关键发现
2. `appendReconSummary` — 机器可读字段

结构化 patch 示例：

```json
{
  "serviceProfile": {
    "webApps": [
      {
        "name": "app",
        "container": "Tomcat/Spring Boot/Nginx/unknown",
        "framework": "Spring Boot/unknown",
        "contextPath": "/",
        "deployPath": "/opt/app/app.jar",
        "listen": [{"host": "0.0.0.0", "port": 8080, "protocol": "http"}],
        "configFiles": ["/opt/app/application.yml"],
        "evidence": ["process args", "server.xml"]
      }
    ],
    "listeningPorts": [
      {"port": 8080, "protocol": "tcp", "process": "java", "evidence": "ss -tlnp"}
    ]
  },
  "keyPaths": {
    "webRoots": ["/var/www/html"],
    "configFiles": ["/etc/nginx/nginx.conf", "/opt/app/application.yml"]
  },
  "openQuestions": ["collect-spring-boot-config", "collect-jdbc-connection-info"]
}
```

---

## 八、决策规则

| 场景 | 行为 |
|---|---|
| 第一阶段无 Web 进程 | 检查 daemon/systemd 服务 |
| Nginx/Apache 做反向代理 | 追溯后端真实服务 |
| 多个应用 | 每个单独列出 |
| 有 `CatalinaTools` 且目标是 Tomcat | 优先用 `getCatalinaInfo` |
| 找到 webroot | 建议检查应用文件结构 |
| 发现 Actuator 特征 | 建议使用 exploit-spring-actuator skill |

---

## Skill 元数据

- riskLevel: `low`
- accessMode: `read_only`
- requiredTools: `BasicInfoTools`, `CommandTools`, `FileTools`
- optionalTools: `ResourceTools`, `CatalinaTools`, `HttpRequestTools`, `ProcessTools`
- produces: `serviceProfile.webApps`, `serviceProfile.listeningPorts`, `keyPaths.webRoots`, `keyPaths.configFiles`
- recommendedNextSkills: `collect-spring-boot-config`, `collect-jdbc-connection-info`, `hunt-credentials`, `exploit-spring-actuator`
- forbiddenByDefault: 写文件、上传文件、修改 Web 容器配置、主动触发业务接口
