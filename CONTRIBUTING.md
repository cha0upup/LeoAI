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
- Node.js 18+（前端开发）

更完整的本地构建、运行和前端调试步骤可参考 [开发环境指南](docs/development.md)。

## 代码规范

- Java 代码遵循项目现有风格
- 前端代码使用 ESLint + Prettier 格式化
- 提交前确保 `mvn package -DskipTests` 能通过

## 注意事项

- 不要在代码中包含任何真实的密钥、凭据或敏感信息
- 新功能请附带基本的使用说明
- 涉及通信协议变更时需确保向后兼容
