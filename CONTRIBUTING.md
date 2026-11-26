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

## 本地构建与发布

- 使用已同步的前端资源构建应用：`./mvnw -pl web -am -DskipTests clean package`。JAR 位于 `web/target/`。
- 前端有改动时，先在前端仓库执行 `npm ci` 和 `npm run build`，再将 `dist/` 同步到后端的 `web/src/main/resources/static/`，移除已被替换的哈希资源。
- 发布前运行 `python3 tools/verify_release.py --frontend ../LeoVueAi`。前后端应配套发布。
- 前端开发的个人覆盖配置放在 `.env.development.local`；`VITE_*` 变量会进入浏览器产物，不存放密钥。

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
- 测试以可观察的行为和真实回归风险为准，优先在最能暴露问题的一层验证。保留权限、敏感数据、持久化和并发等关键边界；已有入口或集成测试能保护的契约，无需再按实现细节逐层重复。仅在输入确实代表不同风险时增加用例，不把覆盖率作为增测目标。
- `tools/check_architecture.py` 只检查模块依赖、循环依赖和包级适配边界；具体类的实现细节交给行为测试与代码审查。
- 架构检查按生产源码中类型的实际模块归属校验显式、静态、通配导入及全限定引用，包名不作为模块归属的唯一依据；共享包中的契约类型使用显式导入，通配导入会保守检查整个包的模块边界。可独立运行 `python3 tools/check_architecture.py`，该检查不替代 Java 编译器的类型解析。
- 发布前执行 `python3 tools/verify_release.py --frontend ../LeoVueAi`；应用 JDK 使用 17，可通过 `JAVA8_HOME` 指定组件编译器
- 命名区分执行节点与 AI：节点模型使用 `Agent`，执行对象使用 `AgentNode`，节点运行时使用 `AgentRuntime`，AI 接口使用 `NodeAiAgent` / `PlatformAiAgent`，AI 运行实例使用 `AiRuntime`；资产与连接上下文使用 `Host` / `Session`。
- 节点 HTTP API 使用 `/agent-node`，管理 API 使用 `/platform/agent-manage`，节点 Skills scope 使用 `agent-node`；历史数据不兼容，部署时使用全新数据库重建。
- 集合查询使用复数；控制台视图使用 `AgentConsoleView`；父节点使用 `parentAgentId` / `parentAgentConfig`；连接测试使用 `AgentConnectionService` / `ConnectionTestService`。变量保留领域与单位，按职责命名，不机械替换词片段。
- 内部命名调整保留 HTTP 路由、JSON/数据库字段、RPC Component ID 和配置绑定键；生成产物从源码重建，不直接修改压缩 JS 或已编译字节码。

## 管理后台审计查询契约

- 管理员使用 `GET /platform/admin/audit-logs?view=summary` 查询摘要页。筛选和分页参数沿用原列表；`data` 返回 `logs`、`total`、`successCount`、`failedCount`、`limit`、`offset`。
- 数量对应**同一筛选条件命中的全部记录**，与当前页条数无关。失败合计包括 `FAILED` 和 `ERROR`；其他状态只计入总数。列表不会返回请求参数、响应消息等详情字段，但关键字搜索仍覆盖原有字段。
- 首页最多多读取一条摘要，以识别完整结果。结果不超过一页时直接统计返回的记录；其余页面仍在同一读取事务中执行聚合，避免并发写入使分页和计数不一致。
- 摘要分页先按筛选及排序确定当前页的行，再读取摘要列。指定精确状态时，成功或失败数由该状态的匹配总数得出，无须逐条判定状态；未指定状态时仍按全筛选范围聚合。
- 详情继续用 `GET /platform/admin/audit-logs/detail?logId=...`。不传 `view` 的列表、独立计数及写入接口保持原契约。部署新版管理后台时，先更新后端或随同一制品发布。
- 参数校验失败保留业务提示；审计请求遇到意外错误时返回 HTTP 500、操作提示及 `traceId`，内部异常详情只进入服务端日志。

团队管理的队长候选使用 `GET /platform/admin/users/no-team?view=options`，仅返回 `userId` 和 `userName`。旧视图、管理员限制和未入队判定保持原样。

项目交接使用 `GET /platform/admin/users/enabled-options` 读取已启用账号，同样只返回 `userId` 和 `userName`。该接口仅向管理员开放；交接提交时仍在后端重新校验接任账号状态。

`GET /platform/admin/teams` 在原有团队条目中按用户可见范围补充只读 `leaderName`。团队页优先显示姓名，缺失时显示队长 ID；打开新增团队弹窗时才单独查询候选。

`POST /platform/admin/teams` 在所选队长已加入团队时返回 409，账号已不存在时返回 404；管理端保留团队名称、重新加载候选并等待用户重新提交。

清理旧审计日志继续按指定天数执行，截止时间与日志写入都使用应用本地时间。等于截止时间的记录保留。

## 任务附件下载

- `GET /platform/user/file/download` 仅下载当前登录用户的任务输出。任务文件引用通过 `userWorkspacePath` 提供路径，并通过 `downloadEndpoint` 提供下载地址。

## 账号与资料写入

- `POST /platform/admin/users` 接收用户名、密码、角色、团队、状态及可选联系资料和备注。用户 ID、登录记录和首次改密标志由服务端维护，客户端提交这些内部字段不会影响新账号。
- `POST /platform/admin/users/delete` 只删除服务端刚校验过的账号版本。若账号在校验后发生变化，或仍负责项目、担任队长，接口返回 409。管理端在收到 404 或 409 后刷新列表，不自动重试删除；若仍有项目或团队归属，先完成交接。
- `POST /platform/user/profile` 只更新邮箱、手机号和备注，仍使用请求内已校验用户的账号版本处理并发。账号在校验后发生变化时返回 409；成功响应不包含密码，也不替换会话中的凭据。

## 项目状态与资料写入

- `POST /platform/projects/archive` 和 `POST /platform/projects/restore` 接收 `projectId`，只更新状态和更新时间。普通保存不发送项目状态，未传 `status` 时保留数据库中的当前值。
- `POST /platform/projects/transfer` 接收 `projectId` 和 `newOwnerUserId`，只变更负责人，保留共享范围和归档状态。接任账号须已启用。

## 注意事项

- 发布入口会安装 lockfile 依赖，执行前端 lint/测试/构建、静态目录逐文件比对、组件 Java 6 API 与运行字节码检查、工具测试以及后端 `clean verify`。本地已准备依赖时可使用 `--skip-install`，CI 使用完整入口。
- 打包后会直接启动 JAR，在临时 SQLite 与 VFS 中验证首次设置管理员、登录和同库重启，并确认修改过的配置会被保留。已有 JAR 可单独运行 `python3 tools/check_packaged_startup.py web/target/LeoAi-3.0.1.jar`（应用 JDK 17）。检查仅监听本机回环地址，不使用工作区的数据库。
- 发布入口先校验父/子 POM、前端 package 与 lockfile 的版本一致性；修改版本时请同步两个仓库。
- 静态比对失败时先同步前端构建产物并审阅差异；检查器保持后端静态目录原样。组件比对只忽略源码、行号与局部变量调试表，仍校验指令、访问标志、注解及反射方法参数。
- GitHub Actions 的 `Release checks` 需输入前端仓库与精确提交 SHA。私有前端仓库可配置只读的 `FRONTEND_READ_TOKEN`；流程仅做检查和打包，不自动发布或修改仓库。

- 不要在代码中包含任何真实的密钥、凭据或敏感信息
- 提交前检查 `git status --short` 与 `git diff --check`；数据库、`.leo/` 密钥、用户 VFS、环境覆盖文件、日志和诊断转储均属于本地数据，不随源码分发。
- Docker 构建上下文使用显式白名单，仅包含入口脚本和随仓库分发的 VFS 种子。新增内置插件、伪装或指纹时同步更新 `.dockerignore`；内置 Skill 由 JAR 中的资源在启动时同步，不复制本地 Skill 工作区。
- README 只保留项目简介、主要功能和快速使用方法，并保持中英文说明一致。接口、实现、测试和发布细节放到贡献指南或专题文档；新功能只在影响基本用法时更新 README。
- 通信协议以当前服务端和节点实现为准；修改协议时同步更新两端及测试，不保留旧版兼容分支
