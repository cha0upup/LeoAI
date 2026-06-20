# Changelog

## v0.0.6 (2026-06-20)

### Puppet 组件可用性增强（红队 / 排障场景重点）

针对「独立 Tomcat 部署 + puppet 注入 commonLoader + webapp idle」这类典型场景，把过去依赖活跃请求 / contextClassLoader / 内部静态字段才能工作的几个核心组件全面重构，覆盖 Tomcat 6/7/8/9/10/11、WebLogic 全版本及 Spring 5/6（含 Tomcat 10+ 的 jakarta.servlet）。

- **TomcatCatalinaManageComponent**
  - `unLoadFilter`：改用公开 API `removeFilterMap(FilterMap)` + `removeFilterDef(FilterDef)`，同时清空 `filterConfigs` 缓存并对其调 `release()`，让卸载**即时生效**而不是等下一次请求；公开 API 不可用时自动退到字段直写
  - `getAllListener`：原本只取 `getApplicationEventListeners`（事件监听器），导致大多数 Context 看着「没有 Listener」；新增 `getApplicationLifecycleListeners` 同时收集生命周期监听器（ServletContextListener / HttpSessionListener / Spring `ContextLoaderListener` 都在此），返回结果带 `category=event|lifecycle` 字段；bootstrap CL 加载的 listener `getClassLoader()` 返回 null 时降级显示为 `<bootstrap>`，避免 NPE
  - `unLoadListener`：候选字段表扩展为 6 个，覆盖 event 和 lifecycle 两类，每类各 3 个 Tomcat 版本字段名（`applicationEventListenersList/Objects/applicationEventListeners` + `applicationLifecycleListenersList/Objects/applicationLifecycleListeners`）；遍历策略改为「全部字段都尝试 remove」而非命中即 break，杜绝同时实现 Lifecycle + Event 接口的 listener 卸载残留
- **WeblogicCatalinaManageComponent**
  - 移除类加载期 `static contexts = getContext()` 缓存（首次扫到空就永远空）
  - `getContextsByMbean()` 加载 `WebAppServletContext` 时按 `classLoader → systemClassLoader` 两段降级
  - `getContext()` 新增第 3 条兜底路径 `getContextsByPlatformMbean()`：通过 `com.bea:Type=ApplicationRuntime,*` 反推 `WebAppServletContext`，覆盖 idle / 普通 CL 注入场景
  - `getCatalinaInfo` / `getAllListener`：补齐 Listener 采集，覆盖 `_servletContextListeners` / `_sessionListeners` / `_requestListeners` / `_asyncListeners` 等 8 类字段（带/不带下划线两套命名兜底），结果带 `category` 字段，按 identity 去重
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

### 体验优化

- **「插件调用」模块改名为「脚本与插件」**：原命名只对应模块内一个按钮的语义（仅覆盖 ~25%），不能涵盖脚本编辑、Java Class 临时执行、保存为插件、插件库浏览四类核心动作；同步更新 README 与英文文档对应段落（Plugin Invocation → Scripts & Plugins）
- **类字节码弹窗比例修复**：在 1440px+ 宽屏下原 `width="80vw"` 会出现「左右大空白、上下挤压」并伴随 Monaco minimap 拖出长条
  - Dialog 宽度按视口分档（≥2400=1800px / ≥1920=1500px / ≥1440=1200px / ≥1024=920px / 移动端 92vw），高分屏不再「漂在中间」
  - Dialog 显式 `height: 90vh`、`top: 4vh`、内部 flex 布局，编辑器随高度自然 fill，不再出现「弹窗矮、内容溢出」
  - 抽出 `useResponsiveDialogWidth` composable，断点表可覆盖；JavaPlugin 调用弹窗（原硬编码 `width="1000px"`）改用同一 composable，自带稍窄一档（≥2400=1600px / ≥1920=1300px / ≥1440=1100px / ≥1024=1000px）匹配其左右两栏布局
- **资源浏览器代码预览升级为 Monaco**：原 `<el-input type="textarea" :rows="22">` 在大文件下没有语法高亮、Ctrl+F、折叠
  - 新增 `<CodePreview>` 通用组件，封装 monaco 编辑器实例的复用 / 主题切换 / 内容增量更新（避免每次重建丢滚动）
  - ResourceBrowser 的 Java 反编译预览、文本预览全部改走 CodePreview
  - 文本预览自动按扩展名推断语言（json/xml/yaml/properties/sql/sh/js/ts/html/css/md/py/java），常见配置文件直接吃语法高亮
- **NetworkConnectionService 数据返回 0 条修复**：原 macOS 链路在某些场景上 `lsof -i -n -P` 命令执行了、回显却被 `2>/dev/null` 吞没，`output.trim().length() > 10` 判断仍通过（shell prompt 自身就 >10），结果走进 `parseLsof` 但解析出 0 条；fallback 到 netstat 的逻辑也不会触发
  - 命令前缀加 `PATH=$PATH:/usr/sbin:/sbin:/usr/local/sbin`，覆盖 puppet 非 login shell 默认 PATH 缺 sbin 的情况
  - 重定向改为 `2>&1`，错误信息不再丢，便于诊断
  - 抽 `looksLikeRealOutput(output, expectedHeader)`：见 header 关键字才算"真有输出"，否则识别 `command not found / Permission denied` 等错误模式后直接 fallback
  - 解析后若仍是 0 条，diagnostics 里附带 `preview=…` 输出片段，下次再有问题能直接看到 shell 回显的原始内容
  - 现在 macOS / Linux 两侧都遵循「真解析出连接才记 source、否则继续 fallback」的策略
- **NetworkConnectionService 响应结构平铺修复**：上一轮修好后端能拿到 315 条连接，但前端列表仍空。根因是 `ControllerUtil.handlePuppetCall` 看到 service 返回的 `code=200` 会再调 `ApiResponse.success(result)` 包一层，service 又自己嵌了 `data:{...payload}`，最终 HTTP body 变成 `res.data.data.connections`，而 `NetworkConnectionManager.vue` 读的是 `res.data.connections`
  - `list()` / `summary()` 把 `result.put("data", data)` 改为 `result.putAll(data)`，让 payload 字段（connections / total / byState 等）直接平铺到 service 返回 map 上
  - 与 `BrowserDataService` 等其他 puppet service 的返回风格对齐：service 只返回 `{code, ...payload}`，不再自己嵌一层 data
- **parseLsof / parseSs 跳过 shell 噪声行**：原 parser 假定第 0 行是 header、第 1 行就是数据；但通过 puppet shell 会话执行时，前面会带 prompt + 命令回显
  - 改为先扫描定位 header 行（lsof 的 `COMMAND ... PID ... NAME`、ss 的 `Netid|State ... Local`），从 header 之后开始解析
  - 跳过 `$` / `#` / `%` 起始的 prompt 行
  - parseLsof 增加 NODE 列校验（必须是 TCP/UDP/IPv4/IPv6 才算合法行），并对 `IPv4/IPv6` 协议从 NAME 中再抽取实际的 TCP/UDP 标签

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
