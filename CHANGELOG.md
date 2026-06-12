# Changelog

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
