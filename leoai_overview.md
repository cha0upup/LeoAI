# LeoAI 项目概览

## 📊 项目基本信息

- **项目名称**: LeoAI
- **描述**: AI 驱动的后渗透综合管理平台
- **版本**: 0.0.1-SNAPSHOT
- **开源协议**: GNU General Public License v3.0
- **代码行数**: 78,029 行（456 个 Java 文件）
- **主仓库**: https://github.com/chaodovvn/LeoAI
- **前端仓库**: https://github.com/chaodovvn/LeoVueAI

---

## 🛠️ 技术栈

### 后端框架与依赖
| 技术 | 版本 | 用途 |
|------|------|------|
| **Java** | 17+ | 运行环境 |
| **Spring Boot** | 3.5.13 | Web 框架 |
| **LangChain4j** | 1.15.0 | AI Agent 框架 |
| **MyBatis** | 3.0.5 | ORM 框架 |
| **SQLite** | 3.41.2.2 | 数据库（内置，无需部署） |
| **OkHttp** | 4.11.0 | HTTP 客户端 |
| **Javassist** | 3.30.2 | 字节码操作 |
| **FastJSON** | 1.2.83 | JSON 处理 |
| **ASM** | 9.5 | 字节码操作 |
| **Java-WebSocket** | - | WebSocket 支持 |

### AI 支持的模型
- **OpenAI**: GPT-4, GPT-4o, GPT-3.5 等
- **Anthropic**: Claude 系列
- **通义千问**: 阿里云模型
- **DeepSeek**: 深度求索模型
- **Ollama**: 本地部署模型
- 任何遵循 OpenAI API 格式的服务

### 构建工具
- **Maven**: 多模块构建
- **Git**: 版本控制

---

## 📦 项目模块结构

### 1. **core** 模块 (4.5 MB - 核心通信与插件引擎)

**职责**: 网络通信、协议层、组件注册、插件系统

**关键包**:
- `org.leo.core.net.*` - 通信协议实现
  - `HttpCommunication.java` - HTTP 协议实现
  - `HttpChunkedCommunication.java` - HTTP Chunked（大文件）
  - `WebSocketCommunication.java` - WebSocket 实时通信
  - `Communication.java` - 通信接口定义

- `org.leo.core.net.layer.*` - 流量伪装与隐蔽层
  - `RequestLayer.java` - 请求处理
  - `ResponseLayer.java` - 响应处理
  - `TlsFingerprintStrategy.java` - TLS 指纹伪装
  - `HeaderNoiseGenerator.java` - Header 噪声注入
  - `UrlGenerator.java` - URL 随机化
  - `PaddingStrategy.java` - 请求体 Padding

- `org.leo.core.init.*` - 初始化器
  - `DatabaseInitializer.java` - 数据库初始化
  - `FingerprintSeedInitializer.java` - 指纹库初始化
  - `VFSInitializer.java` - 虚拟文件系统初始化

- `org.leo.core.util.*` - 工具类
  - `javassist/JavassistDisguiseFactory.java` - 字节码操作工厂
  - `md5/MD5Utils.java` - 加密工具

**主要功能**:
- 支持多种通信协议（HTTP、WebSocket、HTTP Chunked）
- 流量伪装与隐蔽（TLS 指纹、Header 噪声、URL 随机化）
- SOCKS5 代理支持
- 字节码动态修改

---

### 2. **dao** 模块 (116 KB - 数据访问层)

**职责**: 数据库映射、SQL 操作

**Mapper 类** (8 个):
- `UserMapper.java` - 用户管理
- `PuppetMapper.java` - 节点管理
- `TeamMapper.java` - 团队管理
- `AiModelConfigMapper.java` - AI 模型配置
- `AuditLogMapper.java` - 审计日志
- `PuppetJdbcMapper.java` - 数据库连接配置
- `UserAiPolicyMapper.java` - 用户 AI 策略
- `AiConversationMapper.java` - AI 对话记录

**数据库表**:
- `users` - 用户账户
- `teams` - 团队
- `puppets` - 受控节点
- `puppet_jdbc` - 数据库连接
- `sessions` - 会话管理
- `audit_logs` - 审计日志
- `ai_model_configs` - AI 模型配置
- `system_configs` - 系统配置

---

### 3. **service** 模块 (608 KB - 业务逻辑层)

**职责**: 核心业务逻辑、事务管理、服务编排

**关键服务类** (20+ 个):
- **用户与权限**: `UserService.java`, `TeamService.java`
- **节点管理**: `PuppetService.java`, `PuppetConnService.java`
- **文件操作**: `UploadEngineService.java`, `DownloadEngineService.java`
- **数据库**: `PuppetJdbcService.java`, `PuppetNodeSqlService.java`
  - SQL 方言支持: MySQL, PostgreSQL, Oracle, SQLite, SQL Server
  - `AbstractSqlDialect.java` - 方言基类
- **网络与扫描**: `UrlProbeService.java`
- **伪装与指纹**: `DisguiseService.java`, `FingerprintManageService.java`
- **审计**: `AuditLogService.java`
- **插件**: `JavaPluginService.java` - Java 插件热加载
- **会话管理**: Shell 结果存储

**核心特性**:
- 多数据库支持
- 插件热加载机制
- 审计日志记录
- 下载任务队列管理

---

### 4. **ai** 模块 (2.5 MB - AI 与智能化)

**职责**: LangChain4j Agent、AI Tools、Skills 实现

**子模块**:

#### **a) agent** - AI Agent 框架
- 多轮工具调用自动规划
- 上下文累积机制
- 长对话支持（响应超时禁用）

#### **b) channel** - LLM 通道管理
- 模型配置管理
- API Key 管理
- 多通道切换
- OpenAI 兼容 API

#### **c) tools** - AI 工具集合

**Platform Tools** (平台级工具):
- `UserTools.java` - 用户管理操作
- `PuppetTools.java` - 节点管理操作
- `TeamTools.java` - 团队管理操作
- `ShellGeneratorTools.java` - Shell 生成
- `DisguiseTools.java` - 流量伪装
- `FingerprintTools.java` - 指纹管理
- `PluginTools.java` - 插件管理

**PuppetNode Tools** (节点级工具 - 20+):
- **侦察**: `BasicInfoTools.java`, `NetworkInfoTools.java`
- **命令执行**: `CommandTools.java`, `ScriptTools.java`
- **文件操作**: 文件管理工具
- **数据库**: `SqlTools.java` - 数据库操作
- **系统**: `ProcessTools.java`, `EventLogTools.java`
- **浏览器**: `BrowserDataTools.java` - 凭据提取
- **Java**: `JavaPluginTools.java` - Java 插件执行
- **网络**: `NetworkInfoTools.java`, `ResourceTools.java`
- **其他**: `SessionTools.java`, `PlanTools.java`, `SubAgentDispatchTools.java`

#### **d) service** - AI 服务实现
- 对话管理
- 上下文积累
- 侦察摘要生成

#### **e) audit** - AI 审计
- 对话审计日志
- AI 操作记录

#### **f) config** - 配置管理
- LLM 通道配置
- 模型参数设置

#### **g) util** - 工具方法
- JSON 处理
- 文本处理

---

### 5. **jmg** 模块 (2.0 MB - Java 内存马生成器)

**职责**: 内存马与 WebShell 代码生成

**核心类**:
- `ShellGenerator.java` - Shell 生成引擎
- `ShellGeneratorConfig.java` - 生成配置
- `ServerInjectorMapper.java` - 服务器注入映射

**子包**:
- `org.leo.jmg.mem.*` - 内存马生成
  - 支持类型: Filter、Servlet、Listener、Valve、Interceptor、WebSocket
  - 支持目标: Tomcat、WebLogic、Jetty、Spring 等
  
- `org.leo.jmg.jsp.*` - WebShell 生成
  - JSP、JSPX 格式
  - 表达式注入: OGNL、SpEL、EL、Groovy、Freemarker、MVEL

- `org.leo.jmg.util.*` - 工具类

---

### 6. **web** 模块 (2.0 MB - Web 框架与 API)

**职责**: Spring Boot 应用入口、REST API 控制器、前端资源

**API 控制器** (25+ 个):

**平台级 API** (`web/src/main/java/org/leo/web/controller/platform/`):
- **认证**: `LoginController.java`
- **用户管理**: `UserController.java`
- **团队管理**: `TeamController.java`
- **节点管理**: `PuppetManageController.java`
- **AI 配置**: `AiModelConfigController.java`
- **AI 服务**: `PlatformAiController.java`
- **审计**: `AuditLogController.java`, `AiAuditLogController.java`
- **Shell 生成**: `ShellGeneratorController.java`
- **流量伪装**: `DisguiseManagerController.java`
- **指纹管理**: `FingerprintManageController.java`
- **插件管理**: `PluginManageController.java`
- **用户文件**: `UserFileController.java`

**会话管理 API** (`session/`):
- `AsyncShellController.java` - 异步命令执行
- `SessionManageController.java` - 会话管理
- `SessionReportController.java` - 操作报告
- `HostIdController.java` - HostId 切换
- `ReconSummaryController.java` - 侦察摘要

**节点工具 API** (`puppetnode/`):
- `CatalinaManageController.java` - Catalina 应用管理
- 文件管理、数据库、进程、网络等控制器

**前端资源**:
```
web/src/main/resources/static/
├── index.html
├── assets/
│   ├── js/          # JavaScript 打包文件
│   ├── css/         # 样式文件
│   └── ...
```

**配置文件**:
- `application.properties` - Spring Boot 配置
  - 服务器端口: 8082
  - 数据库: SQLite (data.db)
  - AI 配置: OpenAI API Key/Base URL
  - 压缩设置: 响应压缩（省 80%+ 带宽）

---

## 📂 完整目录结构

```
LeoAI/
├── core/                           # 核心模块 (4.5 MB)
│   ├── src/main/java/org/leo/core/
│   │   ├── net/                    # 通信协议层
│   │   │   ├── impl/               # 具体实现（HTTP、WebSocket 等）
│   │   │   └── layer/              # 流量伪装层
│   │   ├── init/                   # 初始化器
│   │   ├── util/                   # 工具类
│   │   └── ...
│   └── pom.xml
│
├── dao/                            # 数据访问层 (116 KB)
│   ├── src/main/java/org/leo/dao/mapper/
│   │   └── *.java                  # MyBatis Mapper 接口
│   └── pom.xml
│
├── service/                        # 业务逻辑层 (608 KB)
│   ├── src/main/java/org/leo/service/
│   │   ├── user/                   # 用户服务
│   │   ├── puppetnode/             # 节点服务
│   │   ├── sql/                    # 数据库服务
│   │   ├── audit/                  # 审计服务
│   │   ├── download*/              # 下载服务
│   │   └── ...
│   └── pom.xml
│
├── ai/                             # AI 模块 (2.5 MB)
│   ├── src/main/java/org/leo/ai/
│   │   ├── agent/                  # LangChain4j Agent
│   │   ├── channel/                # LLM 通道管理
│   │   ├── tools/
│   │   │   ├── platform/           # 平台级工具
│   │   │   └── puppetnode/         # 节点级工具
│   │   ├── service/                # AI 服务
│   │   ├── audit/                  # AI 审计
│   │   └── ...
│   └── pom.xml
│
├── jmg/                            # Java 内存马生成器 (2.0 MB)
│   ├── src/main/java/org/leo/jmg/
│   │   ├── mem/                    # 内存马生成
│   │   ├── jsp/                    # WebShell 生成
│   │   ├── util/                   # 工具类
│   │   └── ...
│   └── pom.xml
│
├── web/                            # Web 模块 (2.0 MB)
│   ├── src/main/java/org/leo/web/
│   │   ├── controller/             # REST API 控制器
│   │   │   ├── platform/
│   │   │   └── puppetnode/
│   │   ├── config/                 # Spring 配置
│   │   └── ...
│   ├── src/main/resources/
│   │   ├── application.properties   # Spring Boot 配置
│   │   ├── sql/                     # 数据库初始化脚本
│   │   │   ├── schema.sql           # 数据库表定义
│   │   │   ├── data.sql             # 初始数据
│   │   │   └── ai_model_schema.sql  # AI 配置表
│   │   └── static/                  # 前端资源（已打包）
│   └── pom.xml
│
├── root/                           # 运行时数据（生成）
│   ├── skills/                     # AI Skills 定义
│   ├── plugin/                     # 插件存储
│   ├── disguise/                   # 伪装配置
│   ├── fingerprint/                # 指纹规则
│   └── users/                      # 用户文件空间
│
├── .github/                        # GitHub 配置
│   ├── workflows/                  # CI/CD 工作流
│   └── ISSUE_TEMPLATE/
│
├── docs/                           # 文档（待完善）
├── pom.xml                         # Maven 父 POM
├── README.md                       # 项目说明
├── SECURITY.md                     # 安全政策
└── CONTRIBUTING.md                # 贡献指南
```

---

## 🏗️ 架构设计

### 分层架构

```
┌─────────────────────────────────────────┐
│       Frontend (Vue.js - 独立仓库)        │
└─────────────────────────────────────────┘
                    ↓ HTTP/WebSocket
┌─────────────────────────────────────────┐
│         API 层 (web/controller)          │
├─────────────────────────────────────────┤
│  服务层 (service)                        │
│  ├─ 用户/团队/节点管理                     │
│  ├─ 数据库操作                            │
│  ├─ 文件上传/下载                         │
│  └─ 审计日志                             │
├─────────────────────────────────────────┤
│  AI 层 (ai)                              │
│  ├─ LangChain4j Agent 框架               │
│  ├─ Platform Tools (平台级操作)           │
│  ├─ PuppetNode Tools (节点级操作)         │
│  └─ LLM Channel 管理 (OpenAI 兼容)       │
├─────────────────────────────────────────┤
│  数据层 (dao/MyBatis)                    │
│  └─ SQLite 数据库                        │
├─────────────────────────────────────────┤
│  核心层 (core)                           │
│  ├─ 通信协议 (HTTP/WebSocket/Chunked)    │
│  ├─ 流量伪装 (TLS/Header/URL/Padding)    │
│  └─ 字节码操作 (内存马生成)               │
└─────────────────────────────────────────┘
                    ↓ 网络通信
┌─────────────────────────────────────────┐
│     节点 (Puppet) - 目标主机上运行        │
│  ├─ HTTP/WebSocket 通信                  │
│  ├─ 命令执行                             │
│  ├─ 文件管理                             │
│  └─ 数据库交互                           │
└─────────────────────────────────────────┘
```

### 关键设计模式

1. **Agent 模式**: LangChain4j 实现多轮工具调用
2. **策略模式**: SQL 方言、通信协议、流量伪装
3. **工厂模式**: 字节码操作、对象创建
4. **观察者模式**: 事件监听、审计日志
5. **单例模式**: 服务层共享实例
6. **模板方法**: SQL 方言基类

---

## 🔑 核心功能模块

### 1. 节点管理系统
- 多协议支持 (HTTP、WebSocket、HTTP Chunked)
- 节点树形管理
- 权限控制（read/write/admin）
- 批量导入/导出
- 分组与标签管理

### 2. AI 自动化系统
- **Agent 框架**: 多轮工具调用，自动规划操作
- **30+ AI Skills**: 侦察、凭据提取、权限提升等
- **多模型支持**: OpenAI、Anthropic、通义千问等
- **上下文积累**: 侦察摘要自动更新
- **报告生成**: 操作总结与风险分析

### 3. 操作控制台工具集
- **终端**: Web 交互式 Shell
- **文件管理**: 上传/下载、编辑、压缩/解压、预览
- **数据库**: MySQL、PostgreSQL、Oracle、SQLite、SQL Server
- **扫描**: 端口扫描、主机探测、服务指纹
- **HTTP 工具**: Repeater（单次）、Fuzzer（批量）
- **系统**: 进程、计划任务、服务、Docker 容器
- **凭据**: 浏览器数据、Windows 凭据、WiFi 配置
- **其他**: 截屏、注册表、防火墙、Java 类反编译

### 4. 内存马与 WebShell 生成
- **内存马**: Filter、Servlet、Listener、Valve、Interceptor、WebSocket
- **WebShell**: JSP、JSPX
- **表达式注入**: OGNL、SpEL、EL、Groovy、Freemarker、MVEL
- **自动指纹识别**: 智能推荐最佳方案

### 5. 流量伪装与隐蔽
- **TLS 指纹伪装**: 模拟真实浏览器
- **Header 噪声**: 注入虚假 Header
- **URL 随机化**: 动态生成 URL
- **请求/响应编码**: 自定义编码规则
- **模板管理**: 保存和重用伪装配置

### 6. 团队协作与权限
- 多用户支持
- 团队隔离
- 节点分享
- 权限细粒度控制
- 完整审计日志

### 7. 插件系统
- **Java 插件热加载**: 动态加载和执行
- **内置插件**: 脚本执行、命令执行、WebLogic 密码提取、堆转储分析
- **自定义开发**: 开放插件接口

---

## 📊 数据库设计

### 核心表
| 表名 | 用途 | 关键字段 |
|------|------|--------|
| `users` | 用户账户 | user_id, user_name, privilege, team_id |
| `teams` | 团队 | team_id, team_name, leader_id |
| `puppets` | 节点（受控主机） | puppet_id, conn_link, protocol, disguise_strategy |
| `puppet_jdbc` | 数据库连接 | conn_id, db_type, host, port, jdbc_url |
| `sessions` | 会话管理 | session_id, user_id, puppet_id, expire_time |
| `audit_logs` | 审计日志 | log_id, user_id, operation_type, operation_path |
| `ai_model_configs` | AI 模型 | id, name, api_key, base_url, model, is_active |
| `system_configs` | 系统配置 | config_key, config_value, config_type |
| `ai_conversations` | AI 对话 | conversation_id, user_id, messages, context |

---

## 🚀 启动与部署

### 前置条件
- Java 17+
- 现代浏览器

### 启动命令
```bash
java -jar --add-opens java.base/java.lang=ALL-UNNAMED LeoAi-0.0.1-SNAPSHOT.jar
```

### 配置参数
```bash
# 修改端口
--server.port=9090

# 修改数据库位置
--spring.datasource.url=jdbc:sqlite:/path/to/data.db

# AI 配置
--leo.ai.openai.api-key=your-key
--leo.ai.openai.base-url=https://api.openai.com/v1
--leo.ai.openai.model=gpt-4o
```

### 首次启动
- 自动初始化 SQLite 数据库
- 生成默认管理员账户
- 创建基础配置
- 访问: http://localhost:8082

---

## 📈 项目统计

| 指标 | 数值 |
|------|------|
| **总代码行数** | 78,029 |
| **Java 文件数** | 456 |
| **模块数** | 6 |
| **数据库表** | 13+ |
| **API 端点** | 50+ |
| **AI Tools** | 30+ |
| **支持的数据库** | 5+ |
| **支持的通信协议** | 3 |

---

## 🔐 安全特性

- 多用户隔离
- 角色权限控制
- 操作审计日志（完整的操作记录）
- API Key 加密存储
- SQLite 本地数据库（无网络开放）
- 流量伪装与隐蔽
- 会话管理与超时控制

---

## 📚 相关文档

- **README.md**: 详细功能说明、快速开始、使用指南
- **SECURITY.md**: 安全漏洞报告政策
- **CONTRIBUTING.md**: 开发指南、代码规范、Commit 规范
- **LICENSE**: GNU General Public License v3.0

---

## 🎯 使用场景

✅ **已授权渗透测试**  
✅ **红队攻防演练**  
✅ **内网安全评估**  
✅ **事件响应与取证**

---

**最后更新**: 2026 年 6 月  
**版本**: 0.0.1-SNAPSHOT
