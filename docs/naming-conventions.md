# 系统语义命名约定

## 角色先于词面

- `Host` 是资产和宿主身份，`Agent` 是节点配置，`AgentNode` 是执行对象，`Session` 是连接上下文。
- `NodeAiAgent` / `PlatformAiAgent` 是 AI 接口。AI 注册表的运行实例使用 `AiRuntime`，不与 Java/PHP 的 `AgentRuntime` 混用。
- `NodeAiSettings` / `PlatformAiSettings` 表示 AI 配置分组，`AiExecutionSettings` 表示执行限制。外部配置仍绑定原有 `leo.ai.agent.agent-node.main.*` 和 `leo.ai.agent.platform.main.*`。
- Vue 控制台组件使用 `AgentConsoleView`，不把视图叫作持久化 `Entity`；打开控制台使用 `openAgentConsole` / `open-agent-console`，增加标签页使用 `addAgentConsoleTab`。
- 父子节点使用 `parentAgentConfig`、`parentAgentId` 和 `add-child-agent`；发现到的真实宿主仍使用 `hostId` / `selectedHostId`。

## 方法和变量

- 返回节点、用户、团队、会话集合时使用复数：`getAllAgents`、`getAllUsers`、`getAllTeams`、`getAllSessions`、`findUsersByTeamId`、`findTeamsByLeaderId`。容器内集合查询同样使用 `getAllFilters`、`getAllServlets`、`getAllControllers` 等复数方法名。
- 前端读取数据并写入视图状态的动作使用 `loadDisguises`，区别于直接返回数据的 `getAllDisguises`。
- 数组或列表变量表达其内容，如 `allAgents`、`users`、`teams`、`allDisguises`；布尔状态和操作函数使用 lowerCamelCase，组件/类型使用 PascalCase。
- 连接相关内部服务使用 `AgentConnectionService` / `ConnectionTestService`。未保存节点配置的连接测试使用 `useAgentConnectionTest` / `testAgentConnectionConfigApi`，不是 AI 配置测试。
- AI 缓存区分对话身份和模型配置：`conversationCacheKey` 与 `modelRuntimeCacheKey`；存储运行实例的集合使用 `aiRuntimes`。
- 变量名保留单位和领域，例如现有的 `timeoutMs`、`expiresAt`、`sessionId`；`i`、`params`、`result` 等在短且明确的作用域内仍是正常命名。
- 不按字符串片段机械替换。`AiAgentConfig` 是真实 AI 配置类；前端节点连接测试误带 `Ai` 前缀时应按职责单独修正。

## 契约边界

这轮名称整理保留 HTTP 路由、JSON/数据库字段、RPC Component ID、第三方库接口和配置绑定键。
`connLink`、`type`、`reqDisguiseId` 等既有传输字段保持原值，内部变量则可在作用域明确的前提下使用更完整的名称。
`TestConnComponent` 是现有 RPC Component ID，与内部的 `ConnectionTestService` 不是同一命名层。
生成产物从源码重建，不直接替换压缩 JS 或已编译字节码。

Puppet → Agent 切换按 [命名与部署说明](agent-naming.md) 使用全新数据库部署，历史数据不兼容。本轮命名整理不追加数据库结构变更。

## 回归检查

```sh
python3 tools/check_semantic_names.py --frontend ../LeoVueAi
python3 -m unittest discover -s tools -p 'test_*.py' -v
(cd javacore && bash compile-components.sh --check)
./mvnw clean verify
```

前端仓库执行 `npm test` 和 `npm run build`。
涉及 Java Component 源码时，先在 `javacore` 执行 `bash compile-components.sh`，从源码重建 `.payload`，再执行只读一致性检查。
命名检查覆盖 Java、PHP、Vue、JavaScript、TypeScript 及相应测试源文件，排除生成的静态包和 vendored 框架源码。
检查器只保护已确定的语义边界，不将主观风格偏好当作所有变量的通用规则。
