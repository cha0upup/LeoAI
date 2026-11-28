# Agent 术语

| 界面用词 | 对应概念 | 边界 |
| --- | --- | --- |
| 接入节点 | `Agent` / `agentId` | 平台登记的连接配置及归属。离线时配置仍存在，一个接入节点可以对应多个后端实例。 |
| 入口节点 | 项目归属中的根接入节点 | 项目按入口节点记录归属；加入项目时选中的子节点会归并到其入口。 |
| 后端实例 | `HostId` | 运行时返回的实例标识，不等同于接入节点 ID，也不用于统计物理主机。 |
| 操作会话 | `AgentNodeSession` / `sessionId` | 一次在线连接或缓存浏览。在线会话绑定一个后端实例；缓存会话可以没有实时客户端。 |
| AI 助手 | `NodeAiAgent` / `PlatformAiAgent` | 节点侧和平台侧的 AI 角色。子对话的父子关系与接入节点的 `parentAgentId` 无关。 |

项目列表（`GET /platform/projects`）的 `entryNodeCount` 表示入口节点归属数。
`activeSessionCount` 是当前调用者可见的活动会话总数，`cacheSessionCount` 是其中的缓存会话数，两者之差为实时会话数。
节点目录复用的会话来自 `GET /platform/session/sessions`，只列有运行时客户端的实时会话，不把缓存会话当作在线连接。

代码中，`AbstractAgentNode` 表示平台侧的运行时适配对象，不是 `agents` 表中的配置记录。
