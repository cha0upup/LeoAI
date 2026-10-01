# Agent 语义命名与部署

## 命名边界

这次调整只统一命名，不改变权限策略、工具能力、运行时行为或主机身份。

| 角色 | 当前命名 |
| --- | --- |
| 资产 | `Host`，界面继续显示“主机资产” |
| 节点配置模型 | `Agent`，`agentId` / `agentName` / `parentAgentId` |
| 执行节点与连接 | `AgentNode` / `AgentNodeSession` |
| Java/PHP 运行时 | `AgentRuntime` / `AgentRuntimeModule` |
| 节点 AI | `NodeAiAgent` / `NodeAiAgentRegistry` / `NodeAiAssistant` |
| 平台 AI | `PlatformAiAgent` / `PlatformAiAgentRegistry` |
| AI 运行状态解析 | `AiRuntimeResolver`，与节点 `AgentRuntime` 区分 |
| AI 配置与任务工作空间 | `AiAgentConfig` / `AiWorkspaceService`，与远程节点文件系统区分 |

Java 节点包统一为 `org.leo.core.agent`，PHP 节点包为 `org.leo.phpcore.agent`。
节点操作 API 为 `/agent-node/...`，管理 API 为 `/platform/agent-manage/...`；
项目接口中的节点集合为 `agents`，请求字段使用 `agentId` / `agentIds` / `parentAgentId`。
控制台路由为 `/main/agent/:tabKey`，节点 Skills scope 为 `agent-node`。
第三方 `User-Agent`、AI `subagent`、`HostId` 和 `sessionId` 保持原含义。

## 部署边界

前端与后端需要一起部署。旧 HTTP 路由、JSON 字段和导出格式不作为运行时别名保留。
后端 `web/src/main/resources/static` 从新前端构建生成，避免 JAR 内仍打包旧接口。
节点脚本、包含旧 Java 包名的自定义插件和缓存制品应重新构建。

## 历史数据不兼容，重新部署并重建数据库

本版本按全新数据库基线部署，历史数据库、旧配置包和旧会话缓存不作为升级输入。
旧命名数据库会触发启动检查，提示使用全新的数据库路径或数据目录重新部署。
程序不会自动转换历史表，也不会自动删除或重置现有数据库。

1. 停止旧服务，备份历史数据库及数据目录。
2. 准备全新的 SQLite 文件路径或空数据目录；Docker 部署使用新的持久化数据卷或数据目录。
3. 同步部署当前前后端，启动后由当前 `schema.sql` 自动创建表结构，初始化基础配置与管理员账户。
4. 重新创建业务数据和节点配置，重新生成节点脚本、建立会话。

仅重启服务或重新构建 Docker 容器会继续使用原数据库或数据卷，并不会重建数据库。
已有备份仅用于留存与回滚旧版本，不导入当前数据库。

## VFS、Skills 与浏览器状态

- 默认 VFS 根目录是 `root`，也可由 `VfsPath` 配置覆盖。全新部署使用新的 VFS 数据目录。
- 自定义 Skills 按当前 `agent-node` scope 和工具契约重新配置；系统内置 Skills 使用当前版本。
- 更新浏览器书签为新控制台路径，刷新页面并重新登录；控制台偏好在当前版本重新设置。

## 验证

```sh
python3 tools/check_semantic_names.py --frontend ../LeoVueAi
python3 -m unittest discover -s tools -p 'test_*.py' -v
./mvnw clean verify
# 前端仓库执行
npm test
npm run build
```

回滚时同时恢复旧前后端及对应旧数据目录，新旧数据库保持独立。
