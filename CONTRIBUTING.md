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
- Node.js 24（发布检查使用 24.14.1）
- Java Component 只读检查使用 Java 8 javac；应用构建使用 JDK 17

## 代码规范

- Java 代码遵循项目现有风格
- 前端代码使用 ESLint + Prettier 格式化
- 发布前执行 `python3 tools/verify_release.py --frontend ../LeoVueAi`；应用 JDK 使用 17，可通过 `JAVA8_HOME` 指定组件编译器
- 命名区分执行节点与 AI：节点模型使用 `Agent`，执行运行时使用 `AgentNode` / `AgentRuntime`，AI 接口使用 `NodeAiAgent` / `PlatformAiAgent`；资产与连接上下文继续使用 `Host` / `Session`。
- 节点 HTTP API 使用 `/agent-node`，管理 API 使用 `/platform/agent-manage`，节点 Skills scope 使用 `agent-node`。命名切换见 [命名与部署说明](docs/agent-naming.md)；历史数据不兼容，部署时使用全新数据库重建。
- 集合、控制台视图、父子节点、AI 运行实例与连接测试的具体名称遵循 [语义命名约定](docs/naming-conventions.md)；提交前运行 `python3 tools/check_semantic_names.py --frontend ../LeoVueAi`。

## 注意事项

- 发布入口会安装 lockfile 依赖，执行前端 lint/测试/构建、静态目录逐文件比对、组件 Java 6 API 与运行字节码检查、工具测试以及后端 `clean verify`。本地已准备依赖时可使用 `--skip-install`，CI 使用完整入口。
- 静态比对失败时先同步前端构建产物并审阅差异；检查器保持后端静态目录原样。组件比对只忽略源码、行号与局部变量调试表，仍校验指令、访问标志、注解及反射方法参数。
- GitHub Actions 的 `Release checks` 需输入前端仓库与精确提交 SHA。私有前端仓库可配置只读的 `FRONTEND_READ_TOKEN`；流程仅做检查和打包，不自动发布或修改仓库。

- 不要在代码中包含任何真实的密钥、凭据或敏感信息
- 新功能请附带基本的使用说明
- 通信协议以当前服务端和节点实现为准；修改协议时同步更新两端及测试，不保留旧版兼容分支
