# LeoAI 项目详细结构与模块依赖

## 一、模块依赖关系

```
web (最顶层)
  ├── depends on → service
  ├── depends on → dao
  ├── depends on → ai
  ├── depends on → core
  └── depends on → jmg
      
ai (AI 层)
  ├── depends on → service
  ├── depends on → dao
  └── depends on → core

service (业务逻辑层)
  ├── depends on → dao
  └── depends on → core

dao (数据访问层)
  └── depends on → core (基础类)

jmg (代码生成器)
  └── depends on → core

core (最底层 - 核心基础)
  └── Spring Boot 基础依赖
```

## 二、详细模块代码结构

### core 模块完整代码树

```
core/
├── src/main/java/org/leo/core/
│   ├── net/
│   │   ├── Communication.java (接口)
│   │   ├── CommunicationFactory.java (工厂)
│   │   ├── impl/
│   │   │   ├── HttpCommunication.java (HTTP 通信)
│   │   │   ├── HttpChunkedCommunication.java (分块传输)
│   │   │   ├── WebSocketCommunication.java (WebSocket)
│   │   │   └── TolerantBodyInterceptor.java (容错拦截)
│   │   └── layer/
│   │       ├── RequestLayer.java (请求处理层)
│   │       ├── ResponseLayer.java (响应处理层)
│   │       ├── TlsFingerprintStrategy.java (TLS 指纹)
│   │       ├── HeaderNoiseGenerator.java (噪声生成)
│   │       ├── HeaderNoiseStrategy.java (噪声策略)
│   │       ├── UrlGenerator.java (URL 生成)
│   │       ├── UrlStrategy.java (URL 策略)
│   │       ├── PaddingStrategy.java (Padding 策略)
│   │       └── PaddingUtil.java (Padding 工具)
│   ├── init/
│   │   ├── DatabaseInitializer.java (数据库初始化)
│   │   ├── FingerprintSeedInitializer.java (指纹种子)
│   │   └── VFSInitializer.java (VFS 初始化)
│   ├── util/
│   │   ├── javassist/
│   │   │   ├── JavassistDisguiseFactory.java (字节码工厂)
│   │   │   └── CloneWithJavassist.java (克隆工具)
│   │   └── md5/
│   │       └── MD5Utils.java (MD5 加密)
│   └── ... (其他核心类)
│
├── src/main/resources/
│   └── sql/
│       ├── schema.sql
│       ├── data.sql
│       └── ai_model_schema.sql
│
└── pom.xml
```

### service 模块完整代码树

```
service/
├── src/main/java/org/leo/service/
│   ├── UserService.java (用户管理)
│   ├── TeamService.java (团队管理)
│   ├── PuppetService.java (节点管理 - 核心)
│   ├── PuppetConnService.java (节点连接管理)
│   ├── PuppetJdbcService.java (JDBC 连接管理)
│   ├── UploadEngineService.java (文件上传)
│   ├── DownloadEngineService.java (文件下载)
│   ├── AuditLogService.java (审计日志)
│   ├── UrlProbeService.java (URL 探测)
│   ├── DisguiseService.java (伪装服务)
│   ├── FingerprintService.java (指纹服务)
│   ├── user/
│   │   └── UserService.java
│   ├── team/
│   │   └── TeamService.java
│   ├── puppetnode/
│   │   ├── plugin/
│   │   │   └── JavaPluginService.java (Java 插件)
│   │   └── ... (节点相关服务)
│   ├── sql/
│   │   ├── PuppetNodeSqlService.java (SQL 执行)
│   │   ├── SqlExportService.java (SQL 导出)
│   │   ├── dialect/
│   │   │   ├── AbstractSqlDialect.java (抽象方言)
│   │   │   ├── MysqlDialect.java
│   │   │   ├── PostgreSqlDialect.java
│   │   │   ├── OracleDialect.java
│   │   │   ├── SqlServerDialect.java
│   │   │   └── SqliteDialect.java
│   │   └── ... (其他 SQL 相关)
│   ├── audit/
│   │   └── AuditLogService.java
│   ├── disguise/
│   │   └── DisguiseService.java
│   ├── fingerprint/
│   │   └── FingerprintManageService.java
│   ├── download/
│   │   ├── DownloadEngineService.java
│   │   ├── DownloadStore.java
│   │   └── DownloadTask.java
│   ├── downloadengine/
│   │   ├── DownloadStore.java
│   │   └── DownloadTask.java
│   ├── shell/
│   │   └── ShellResultStore.java
│   └── ... (其他服务)
│
└── pom.xml
```

### ai 模块完整代码树

```
ai/
├── src/main/java/org/leo/ai/
│   ├── agent/
│   │   ├── PlatformAgent.java (平台级 Agent)
│   │   ├── PuppetNodeAgent.java (节点级 Agent)
│   │   ├── BaseAgent.java (Agent 基类)
│   │   └── ... (其他 Agent 类)
│   │
│   ├── channel/
│   │   ├── AiChannelManager.java (通道管理)
│   │   ├── AiChannel.java (通道接口)
│   │   ├── OpenAiChannel.java (OpenAI 通道)
│   │   └── ... (其他通道实现)
│   │
│   ├── tools/
│   │   ├── platform/
│   │   │   ├── UserTools.java (用户操作)
│   │   │   ├── TeamTools.java (团队操作)
│   │   │   ├── PuppetTools.java (节点操作)
│   │   │   ├── ShellGeneratorTools.java (Shell 生成)
│   │   │   ├── DisguiseTools.java (伪装工具)
│   │   │   ├── FingerprintTools.java (指纹工具)
│   │   │   └── PluginTools.java (插件工具)
│   │   │
│   │   └── puppetnode/
│   │       ├── BasicInfoTools.java (基础信息)
│   │       ├── CommandTools.java (命令执行)
│   │       ├── SqlTools.java (数据库操作)
│   │       ├── ProcessTools.java (进程管理)
│   │       ├── EventLogTools.java (事件日志)
│   │       ├── BrowserDataTools.java (浏览器数据)
│   │       ├── JavaPluginTools.java (Java 插件)
│   │       ├── NetworkInfoTools.java (网络信息)
│   │       ├── ResourceTools.java (资源工具)
│   │       ├── SessionTools.java (会话工具)
│   │       ├── ScriptTools.java (脚本工具)
│   │       ├── PlanTools.java (规划工具)
│   │       ├── SubAgentDispatchTools.java (子 Agent 分发)
│   │       └── ... (其他节点工具)
│   │
│   ├── service/
│   │   ├── AiConversationService.java (对话管理)
│   │   ├── AiContextService.java (上下文管理)
│   │   ├── ReconSummaryService.java (侦察摘要)
│   │   └── ... (其他 AI 服务)
│   │
│   ├── audit/
│   │   ├── AiAuditLogService.java (AI 审计)
│   │   └── ... (其他审计类)
│   │
│   ├── config/
│   │   ├── AiConfiguration.java (AI 配置)
│   │   ├── LangChain4jConfig.java (LangChain4j 配置)
│   │   └── ... (其他配置)
│   │
│   ├── thread/
│   │   ├── AgentThreadPool.java (Agent 线程池)
│   │   └── ... (线程管理)
│   │
│   ├── util/
│   │   ├── JsonUtil.java (JSON 工具)
│   │   ├── PromptBuilder.java (提示词构建)
│   │   └── ... (其他工具)
│   │
│   ├── puppetnode/
│   │   ├── PuppetNodeAiService.java (节点 AI 服务)
│   │   └── ... (节点级 AI)
│   │
│   ├── platform/
│   │   ├── PlatformAiService.java (平台 AI 服务)
│   │   └── ... (平台级 AI)
│   │
│   └── ... (其他 AI 类)
│
└── pom.xml
```

### dao 模块完整代码树

```
dao/
├── src/main/java/org/leo/dao/
│   ├── mapper/
│   │   ├── UserMapper.java
│   │   ├── TeamMapper.java
│   │   ├── PuppetMapper.java
│   │   ├── AuditLogMapper.java
│   │   ├── AiModelConfigMapper.java
│   │   ├── PuppetJdbcMapper.java
│   │   ├── UserAiPolicyMapper.java
│   │   ├── AiConversationMapper.java
│   │   └── ... (其他 Mapper)
│   │
│   └── ... (其他数据访问类)
│
├── src/main/resources/
│   └── mapper/
│       ├── UserMapper.xml
│       ├── TeamMapper.xml
│       ├── PuppetMapper.xml
│       └── ... (其他 SQL 映射文件)
│
└── pom.xml
```

### web 模块完整代码树

```
web/
├── src/main/java/org/leo/web/
│   ├── controller/
│   │   ├── config/
│   │   │   └── SpaForwardController.java (SPA 路由)
│   │   │
│   │   ├── platform/
│   │   │   ├── LoginController.java (登录认证)
│   │   │   ├── admin/
│   │   │   │   ├── UserController.java
│   │   │   │   ├── TeamController.java
│   │   │   │   ├── AiModelConfigController.java
│   │   │   │   └── AuditLogController.java
│   │   │   ├── puppet/
│   │   │   │   └── PuppetManageController.java
│   │   │   ├── shell/
│   │   │   │   └── ShellGeneratorController.java
│   │   │   ├── disguise/
│   │   │   │   └── DisguiseManagerController.java
│   │   │   ├── fingerprint/
│   │   │   │   └── FingerprintManageController.java
│   │   │   ├── plugin/
│   │   │   │   └── PluginManageController.java
│   │   │   ├── user/
│   │   │   │   └── UserFileController.java
│   │   │   ├── ai/
│   │   │   │   ├── PlatformAiController.java
│   │   │   │   └── AiAuditLogController.java
│   │   │   │
│   │   │   └── session/
│   │   │       ├── AsyncShellController.java
│   │   │       ├── SessionManageController.java
│   │   │       ├── SessionReportController.java
│   │   │       ├── HostIdController.java
│   │   │       └── ReconSummaryController.java
│   │   │
│   │   └── puppetnode/
│   │       ├── catalina/
│   │       │   └── CatalinaManageController.java
│   │       ├── database/
│   │       │   └── SqlController.java
│   │       ├── file/
│   │       │   └── FileManagerController.java
│   │       ├── process/
│   │       │   └── ProcessController.java
│   │       ├── network/
│   │       │   └── NetworkController.java
│   │       ├── system/
│   │       │   └── SystemController.java
│   │       └── ... (其他节点工具控制器)
│   │
│   ├── config/
│   │   ├── SecurityConfig.java (安全配置)
│   │   ├── WebConfig.java (Web 配置)
│   │   └── ... (其他 Spring 配置)
│   │
│   ├── filter/
│   │   ├── JwtFilter.java (JWT 认证过滤)
│   │   └── ... (其他过滤器)
│   │
│   ├── interceptor/
│   │   └── ... (拦截器)
│   │
│   └── ... (其他 Web 类)
│
├── src/main/resources/
│   ├── application.properties (配置文件)
│   ├── application-dev.properties
│   ├── application-prod.properties
│   ├── sql/
│   │   ├── schema.sql
│   │   ├── data.sql
│   │   └── ai_model_schema.sql
│   └── static/
│       ├── index.html
│       ├── favicon.ico
│       └── assets/
│           ├── js/
│           │   ├── app.js
│           │   ├── chunk-xxx.js
│           │   └── ... (其他打包的 JS)
│           ├── css/
│           │   ├── style.css
│           │   └── ... (其他样式)
│           └── ... (其他资源)
│
├── src/test/java/
│   └── ... (测试类)
│
└── pom.xml
```

### jmg 模块完整代码树

```
jmg/
├── src/main/java/org/leo/jmg/
│   ├── ShellGenerator.java (主生成器)
│   ├── ShellGeneratorConfig.java (生成配置)
│   ├── ServerInjectorMapper.java (服务器映射)
│   │
│   ├── mem/
│   │   ├── MemoryShellGenerator.java (内存马生成)
│   │   ├── filter/
│   │   │   └── FilterMemoryShell.java
│   │   ├── servlet/
│   │   │   └── ServletMemoryShell.java
│   │   ├── listener/
│   │   │   └── ListenerMemoryShell.java
│   │   ├── valve/
│   │   │   └── ValveMemoryShell.java
│   │   ├── interceptor/
│   │   │   └── InterceptorMemoryShell.java
│   │   └── websocket/
│   │       └── WebSocketMemoryShell.java
│   │
│   ├── jsp/
│   │   ├── WebShellGenerator.java (WebShell 生成)
│   │   ├── JspShell.java
│   │   ├── JspxShell.java
│   │   └── ... (其他格式)
│   │
│   ├── core/
│   │   ├── BaseGenerator.java (生成基类)
│   │   └── ... (核心生成类)
│   │
│   └── util/
│       ├── ClassByteCodeUtil.java (字节码工具)
│       ├── ExpressionInjector.java (表达式注入)
│       └── ... (其他工具)
│
└── pom.xml
```

## 三、API 端点汇总

### 认证与用户 (Authentication & User)
- POST `/api/auth/login` - 登录
- POST `/api/auth/logout` - 登出
- POST `/api/users` - 创建用户
- GET `/api/users/{userId}` - 获取用户信息
- PUT `/api/users/{userId}` - 更新用户
- DELETE `/api/users/{userId}` - 删除用户

### 团队管理 (Team Management)
- POST `/api/teams` - 创建团队
- GET `/api/teams` - 获取团队列表
- PUT `/api/teams/{teamId}` - 更新团队
- DELETE `/api/teams/{teamId}` - 删除团队

### 节点管理 (Puppet/Node Management)
- POST `/api/puppets` - 添加节点
- GET `/api/puppets` - 获取节点列表
- PUT `/api/puppets/{puppetId}` - 更新节点
- DELETE `/api/puppets/{puppetId}` - 删除节点
- POST `/api/puppets/{puppetId}/test` - 测试连接

### 会话与命令 (Session & Command)
- POST `/api/sessions/{puppetId}` - 创建会话
- GET `/api/sessions` - 获取会话列表
- POST `/api/sessions/{sessionId}/command` - 执行命令
- GET `/api/sessions/{sessionId}/output` - 获取输出

### 文件管理 (File Management)
- GET `/api/files/{puppetId}/list` - 列文件
- POST `/api/files/{puppetId}/upload` - 上传文件
- GET `/api/files/{puppetId}/download` - 下载文件
- POST `/api/files/{puppetId}/delete` - 删除文件
- POST `/api/files/{puppetId}/edit` - 编辑文件

### 数据库操作 (Database)
- POST `/api/databases/{puppetId}/connect` - 创建连接
- POST `/api/databases/{puppetId}/query` - 执行查询
- GET `/api/databases/{puppetId}/tables` - 获取表列表
- GET `/api/databases/{puppetId}/table/{tableName}` - 获取表结构

### AI 相关 (AI)
- GET `/api/ai/models` - 获取模型列表
- POST `/api/ai/models` - 配置新模型
- POST `/api/ai/chat` - 对话
- GET `/api/ai/audit` - 获取对话审计

### Shell 生成 (Shell Generator)
- POST `/api/shell/generate` - 生成 Shell
- POST `/api/shell/memory` - 生成内存马
- POST `/api/shell/webshell` - 生成 WebShell

### 伪装与指纹 (Disguise & Fingerprint)
- GET `/api/disguise/list` - 获取伪装模板
- POST `/api/disguise/create` - 创建伪装模板
- GET `/api/fingerprint/list` - 获取指纹规则

### 审计日志 (Audit Log)
- GET `/api/audit/logs` - 获取审计日志
- GET `/api/audit/logs/{logId}` - 获取日志详情

---

## 四、关键类与接口

### 通信接口 (Communication Interface)
```java
public interface Communication {
    String communicate(String request) throws Exception;
    void connect() throws Exception;
    void disconnect() throws Exception;
    boolean isConnected();
}
```

### Agent 接口
```java
public interface Agent {
    String chat(String message, String context);
    List<Tool> getAvailableTools();
    void registerTool(Tool tool);
}
```

### Tool 接口
```java
public interface Tool {
    String execute(String... args);
    String getName();
    String getDescription();
}
```

### 数据库方言接口
```java
public abstract class AbstractSqlDialect {
    public abstract String buildConnection(PuppetJdbc jdbc);
    public abstract List<String> getTableList(Connection conn);
    public abstract List<Map<String, Object>> executeQuery(Connection conn, String sql);
}
```

---

## 五、项目初始化流程

```
1. 应用启动 (Application Start)
   ↓
2. Spring Boot 初始化 (Spring Boot Initialization)
   ├── 加载 application.properties
   ├── 初始化 DataSource (SQLite)
   └── 初始化 MyBatis
   ↓
3. 核心模块初始化 (Core Module Initialization)
   ├── DatabaseInitializer → 创建数据库表
   ├── FingerprintSeedInitializer → 加载指纹规则
   └── VFSInitializer → 初始化虚拟文件系统
   ↓
4. 服务层初始化 (Service Layer Initialization)
   ├── UserService → 用户初始化
   ├── PuppetService → 节点初始化
   └── AuditLogService → 审计初始化
   ↓
5. AI 层初始化 (AI Layer Initialization)
   ├── AiChannelManager → 加载 LLM 通道
   ├── PlatformAgent → 初始化平台 Agent
   └── PuppetNodeAgent → 初始化节点 Agent
   ↓
6. Web 层初始化 (Web Layer Initialization)
   ├── 加载 Spring MVC 控制器
   ├── 配置安全过滤器
   └── 挂载前端静态资源
   ↓
7. 应用就绪 (Application Ready)
   └── 监听 8082 端口
```

---

## 六、核心数据流

### 用户与节点交互流程

```
User (Frontend)
    ↓ HTTP/WebSocket
Web Controller
    ↓
Service Layer
    ├── PuppetService (节点管理)
    ├── AuditLogService (审计)
    └── DownloadEngineService (文件传输)
    ↓
DAO Layer (MyBatis)
    ↓
SQLite Database
    ↑
core/net/* (Communication Protocol)
    ↓ Network Traffic
Puppet Node (Target Host)
    ├── Execute Command
    ├── File I/O
    ├── Database Query
    └── ...
    ↓ Response
Web Controller
    ↓ HTTP Response
User (Frontend)
```

### AI Agent 执行流程

```
User Input
    ↓
PlatformAiController / PuppetNodeAiController
    ↓
AiConversationService
    ↓
LangChain4j Agent
    ├── Parse Message
    ├── Select Tool
    ├── Execute Tool
    │   ├── PlatformTools (User/Puppet/Team Management)
    │   ├── PuppetNodeTools (Command/File/DB/System)
    │   └── ...
    ├── Accumulate Context
    ├── Loop until Goal Reached
    └── Generate Response
    ↓
ReconSummaryService (Update Reconnaissance Summary)
    ↓
AiAuditLogService (Record Conversation)
    ↓
Response to User
```

---

