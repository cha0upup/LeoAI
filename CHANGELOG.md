# Changelog

## v0.0.6 (2026-06-20)

### Puppet 组件可用性增强（红队 / 排障场景重点）

针对「独立 Tomcat 部署 + puppet 注入 commonLoader + webapp idle」这类典型场景，把过去依赖活跃请求 / contextClassLoader / 内部静态字段才能工作的几个核心组件全面重构，覆盖 Tomcat 6/7/8/9/10/11、WebLogic 全版本及 Spring 5/6（含 Tomcat 10+ 的 jakarta.servlet）。

- **TomcatCatalinaManageComponent**
  - `unLoadFilter`：改用公开 API `removeFilterMap(FilterMap)` + `removeFilterDef(FilterDef)`，同时清空 `filterConfigs` 缓存并对其调 `release()`，让卸载**即时生效**而不是等下一次请求；公开 API 不可用时自动退到字段直写
  - `unLoadListener`：按 Tomcat 版本轮询 `applicationEventListenersList` / `applicationEventListenersObjects` / `applicationEventListeners` 三个字段名，兼容 6/7/8.5+
- **WeblogicCatalinaManageComponent**
  - 移除类加载期 `static contexts = getContext()` 缓存（首次扫到空就永远空）
  - `getContextsByMbean()` 加载 `WebAppServletContext` 时按 `classLoader → systemClassLoader` 两段降级
  - `getContext()` 新增第 3 条兜底路径 `getContextsByPlatformMbean()`：通过 `com.bea:Type=ApplicationRuntime,*` 反推 `WebAppServletContext`，覆盖 idle / 普通 CL 注入场景
- **SpringFrameworkManageComponent**
  - 移除 `static Object context` 缓存，改为 instance 字段每次现取
  - 新增**路径 3**：从 Tomcat StandardContext 反推 ServletContext → `WebApplicationContextUtils.getWebApplicationContext()`，解决 idle Spring Boot 部署 + 全局 CL 注入下 `RequestContextHolder` / `LiveBeansView` 全部失效的问题
- **CredentialHarvestComponent**
  - `getSpringContext()` 同步加 Tomcat MBean 兜底路径，并兼容 `javax.servlet` / `jakarta.servlet` 两套签名（Spring 5+Tomcat 9 与 Spring 6+Tomcat 10+）
- **DatabaseComponent**
  - JDBC driver 加载从单一 contextCL 改为 `contextCL → systemCL → 所有 Tomcat WebappClassLoader` 三层降级
  - 找到 driver 后直接 `Driver.connect(url, props)`，绕开 `DriverManager` 的 SecurityManager 同 CL 校验，使 webapp `WEB-INF/lib` 内的 driver 也能跨 CL 使用
- **ResourceComponent**（重写源码，原仅留二进制 .payload）
  - `run()` 改用 `catch (Throwable)` 兜底 + 无条件回写 results，根除 puppet 偶发吞响应导致前端报「响应解码结果为空」
  - 资源加载同样三层 CL 降级，可直接读 webapp 内的 `.class` / `application.yml` / `META-INF/MANIFEST.MF` 等
  - 响应同时塞 `bytecode` 与 `data` 两个字段，兼容历史调用方

### 新功能

- **类与资源浏览器**：PuppetConsole 新增独立 Tab「类与资源」，支持
  - 双输入模式：类名（自动转 `com/example/Foo.class`）/ 任意 classpath 路径
  - 自动识别响应内容：`0xCAFEBABE` 走反编译展 Java 源、纯文本走 UTF-8 文本展示（256K 截断）、其他走十六进制 dump（前 4KB + ASCII 列）
  - 配套操作：复制（按预览类型自动选）、下载、清空、最近 6 条历史
  - 后端新接口 `POST /puppet-node/resource/get`，含审计日志、magic-byte 类型识别、反编译失败自动降级到十六进制

### 安全加固（继承自 Unreleased）

- **YAML 反序列化**：`SkillRegistryService` 和 `SkillController` 改用 `SafeConstructor`，禁止 frontmatter 中通过 `!!java.*` 标签实例化任意类
- **Disguise 接口鉴权**：`del-disguise` / `update-disguises` 显式校验登录态；`test-disguises` / `preview` 因会动态编译并执行 Java 代码，在入口处增加未登录拦截
- **Zip Bomb 防护**：新增 `org.leo.core.util.SafeZipReader`，对 Disguise / Plugin / Fingerprint 三处 zip 导入路径强制限制条目数（1000）、单条目大小（5 MB）、解压后总大小（50 MB），超限抛 `ZipLimitExceededException`

### 重构（继承自 Unreleased）

- 后端 Plugin 模块导入冲突策略从 `boolean overwrite` 改为内部 `ConflictPolicy` 枚举（SKIP / OVERWRITE），与 Disguise / Fingerprint 风格对齐
- 前端新增 4 个 composable：`useDialogVisible`（dialog v-model 收口）、`useDirtyTracker`（表单脏检查）、`useSaveShortcut`（Ctrl/Cmd+S 保存）、`useConfirmClose`（关闭前确认）
- 前端 `AddPluginDialog` / `EditPluginDialog` 提取共享子组件 `PluginFormFields`，删除约 260 行重复模板
- 前端新增 `utils/downloadBlob.js`，统一替换 4 处分散实现的 blob 下载逻辑
- 前端 11 个 dialog 组件迁移到 `useDialogVisible`，移除老式的双 `watch` 同步模式

### 兼容性

| 场景 | 0.0.5 | 0.0.6 |
|---|---|---|
| Idle Tomcat 容器面板列表（无活跃请求） | ✗ | ✓ |
| Idle Spring Boot 凭据采集 / 框架信息 | ✗ | ✓ |
| WebLogic 普通 CL 注入下的容器列表 | ✗ | ✓ |
| Tomcat 6 卸载 Listener | ✗ | ✓ |
| Filter 卸载即时生效 | ✗ | ✓ |
| 跨 CL 使用 webapp 内 JDBC driver | ✗ | ✓ |
| 查看 webapp 内的 class 字节码 | ✗ | ✓ |

### 升级提示

- 容器与 Spring 相关 `.payload` 二进制已重新生成，puppet 端会按需自动重载，**无需手动重启目标进程**
- 旧版本 `LeoAi-0.0.5-SNAPSHOT.jar` 与新版 `.payload` 不兼容（component 接口和反射字段名都有调整），请整体升级到 0.0.6

---

## v1.0.0 (2026-06-12)

首个公开发布版本。

### AI 能力

- 基于 LangChain4j 的多 Agent 架构：主 Agent + 侦察/持久化/利用三个子 Agent，支持并发工具调用
- 175 个原子 AI Tools，覆盖命令执行、文件、进程、网络、凭据、扫描、HTTP 发包、数据库、容器、用户账户、磁盘、SUID/Capability 等全场景
- 21 个内置 puppet-node Skills，涵盖侦察、凭据收集、提权、横向移动、持久化、漏洞利用、容器/云、AD/域渗透等完整攻击链
- reconSummary 自动积累：工具执行后异步提取侦察情报，AI 上下文随操作深入持续增强
- 支持 Thinking 模式（DeepSeek-R1、Claude 等推理模型），延迟换深度
- 运行时热切换 LLM 通道，无需重启
- 平台级 AI Agent，支持流量伪装设计、指纹规则编写、攻击策略规划

### 通信与隐蔽

- 三种通信协议：HTTP、HTTP Chunked（大文件/长日志）、WebSocket（低延迟交互）
- 流量伪装：TLS 指纹随机化、Header 噪声注入、URL 路径随机化、请求/响应自定义编解码
- 四种代理/隧道模式：SOCKS5、HTTP CONNECT、本地端口转发（ssh -L 风格）、反向隧道（ssh -R 风格）
- 反向隧道：puppet 端监听，内网客户端主动连入，C2 拨号转发，无需目标侧出站权限

### 操作控制台

- 交互式 Web 终端：实时流输出、历史记录
- 文件管理器：树形目录、上传/下载、在线编辑、压缩/解压、大文件分片传输、文本/图片/PDF 预览
- 数据库控制台：MySQL、PostgreSQL、Oracle、SQLite、SQL Server，含 SQL 编辑器和表结构浏览
- HTTP 发包器：Repeater（单次）+ Fuzzer（批量模糊测试）
- 端口扫描：TCP 扫描、Ping Sweep、多目标并发
- 服务指纹识别：38 条内置规则（Nginx、Tomcat、Jenkins、Nacos、Redis 等），支持自定义规则
- Docker 管理：容器列表、详情、exec 执行、镜像管理
- 进程管理、计划任务、服务管理（Windows）
- 用户账户枚举、磁盘挂载查看、SUID/Capability 检测
- 注册表管理、事件日志查看（Windows）
- 截屏、剪贴板读取、凭据提取（系统/浏览器/WiFi）
- 类字节码提取与反编译

### Shell 生成器

- 内存马：17 种中间件（Tomcat、Jetty、JBoss、Wildfly、WebLogic、WebSphere、Spring 等），支持 Filter/Servlet/Listener/Valve/Interceptor/WebSocket 类型
- 表达式注入 Packer：23 种（OGNL、SpEL、EL、Groovy、Freemarker、BCEL、Translet、H2 等）
- WebShell：JSP、JSPX

### 平台管理

- 多用户、角色权限控制
- 团队协作：节点共享、成员权限分级
- 审计日志：命令执行、文件操作、AI 对话全量记录
- 内嵌 SQLite，零依赖部署，首次启动自动初始化
- 插件系统：Java 插件热加载，内置脚本执行、堆转储分析、WebLogic 密码获取等插件
