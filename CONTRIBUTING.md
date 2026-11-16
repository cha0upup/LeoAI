# 贡献指南

感谢你对 LeoAI 的关注！欢迎提交 Issue 和 Pull Request。

## 如何贡献

### 报告 Bug

1. 在 [Issues](../../issues) 中搜索是否已有相同问题
2. 如果没有，使用 Bug 报告模板创建新 Issue
3. 尽可能提供复现步骤、环境信息和错误日志

### 提交功能建议

1. 使用功能请求模板创建 Issue
2. 描述使用场景和期望方案

### 提交代码

1. Fork 本仓库
2. 创建功能分支：`git checkout -b feat/your-feature`
3. 提交改动：`git commit -m "feat: 添加某功能"`
4. 推送分支：`git push origin feat/your-feature`
5. 创建 Pull Request

## Commit 规范

使用 [Conventional Commits](https://www.conventionalcommits.org/) 格式：

- `feat:` 新功能
- `fix:` Bug 修复
- `docs:` 文档更新
- `refactor:` 重构（不影响功能）
- `chore:` 构建/工具变更
- `test:` 测试相关

## 开发环境

- JDK 17+
- Maven 3.8+
- Node.js 24（前端 `.nvmrc` 和发布检查统一使用 24.14.1），使用 `npm ci` 安装 lockfile 依赖
- Java Component 只读检查使用 Java 8 javac；应用构建使用 JDK 17

## 代码规范

- Java 代码遵循项目现有风格
- 前端代码使用 ESLint + Prettier 格式化
- 日常后端检查直接使用 `./mvnw verify`；完整重建使用 `./mvnw clean verify`，无需额外构建包装脚本。
- 保持现有模块分层；公共逻辑优先收拢到已有职责中，不为单个分支新增服务、接口或通用框架。
- `web` 是组合根，`web.application` 承接应用编排，HTTP/SSE/SSH 等入口和实现留在适配包。应用编排使用纯数据结果和业务异常，不引用 Servlet、HTTP DTO 或具体适配器；SSE 订阅与后台执行独立。
- `core` 的通信、文件仓储和字节码实现归入 `infrastructure`，`runtime`、`generator`、`component.runtime`、`rpc`、`net` 等共享契约不反向引用基础设施；Java 运行时使用 `org.leo.javacore.*`。
- `service`、`ai` 不直接引用 Servlet、Spring Web/HTTP 类型；文件导入使用通用输入源，HTTP 上传对象留在 Web 适配层。
- `service`、`ai` 不依赖 `jmg`；生成器 AI 工具及具体制品缓存位于 `web.adapter.ai`，由组合根与节点 AI 桥接工具一起装配。
- 连接池初始化已启用数据库外键；供应商、模型删除复用 `schema.sql` 的级联与置空规则，不在服务或 Mapper 中重复维护同一关系。
- 测试按行为和职责组织在已有测试类中，重复输入使用参数化测试；保留业务边界、权限、持久化与生命周期验证，不重复测试底层配置解析或用反射锁定内部类名和字段。
- `tools/check_architecture.py` 只检查模块依赖、循环依赖和包级适配边界；具体类的实现细节交给行为测试与代码审查。
- 架构检查按生产源码中类型的实际模块归属校验显式、静态、通配导入及全限定引用，包名不作为模块归属的唯一依据；共享包中的契约类型使用显式导入，通配导入会保守检查整个包的模块边界。可独立运行 `python3 tools/check_architecture.py`，该检查不替代 Java 编译器的类型解析。
- 发布前执行 `python3 tools/verify_release.py --frontend ../LeoVueAi`；应用 JDK 使用 17，可通过 `JAVA8_HOME` 指定组件编译器
- 命名区分执行节点与 AI：节点模型使用 `Agent`，执行对象使用 `AgentNode`，节点运行时使用 `AgentRuntime`，AI 接口使用 `NodeAiAgent` / `PlatformAiAgent`，AI 运行实例使用 `AiRuntime`；资产与连接上下文使用 `Host` / `Session`。
- 节点 HTTP API 使用 `/agent-node`，管理 API 使用 `/platform/agent-manage`，节点 Skills scope 使用 `agent-node`；历史数据不兼容，部署时使用全新数据库重建。
- 集合查询使用复数；控制台视图使用 `AgentConsoleView`；父节点使用 `parentAgentId` / `parentAgentConfig`；连接测试使用 `AgentConnectionService` / `ConnectionTestService`。变量保留领域与单位，按职责命名，不机械替换词片段。
- 内部命名调整保留 HTTP 路由、JSON/数据库字段、RPC Component ID 和配置绑定键；生成产物从源码重建，不直接修改压缩 JS 或已编译字节码。

## 注意事项

- 发布入口会安装 lockfile 依赖，执行前端 lint/测试/构建、静态目录逐文件比对、组件 Java 6 API 与运行字节码检查、工具测试以及后端 `clean verify`。本地已准备依赖时可使用 `--skip-install`，CI 使用完整入口。
- 发布入口先校验父/子 POM、前端 package 与 lockfile 的版本一致性；修改版本时请同步两个仓库。
- 静态比对失败时先同步前端构建产物并审阅差异；检查器保持后端静态目录原样。组件比对只忽略源码、行号与局部变量调试表，仍校验指令、访问标志、注解及反射方法参数。
- GitHub Actions 的 `Release checks` 需输入前端仓库与精确提交 SHA。私有前端仓库可配置只读的 `FRONTEND_READ_TOKEN`；流程仅做检查和打包，不自动发布或修改仓库。

- 不要在代码中包含任何真实的密钥、凭据或敏感信息
- 提交前检查 `git status --short` 与 `git diff --check`；数据库、`.leo/` 密钥、用户 VFS、环境覆盖文件、日志和诊断转储均属于本地数据，不随源码分发。
- Docker 构建上下文使用显式白名单，仅包含入口脚本和随仓库分发的 VFS 种子。新增内置插件、伪装或指纹时同步更新 `.dockerignore`；内置 Skill 由 JAR 中的资源在启动时同步，不复制本地 Skill 工作区。
- 代码变更后核对受影响的现存 Markdown 文档；行为、接口、配置、部署或验证命令与描述有出入时，同步修改文档，并保持中英文 README 一致。新功能请附带基本的使用说明。
- 通信协议以当前服务端和节点实现为准；修改协议时同步更新两端及测试，不保留旧版兼容分支
