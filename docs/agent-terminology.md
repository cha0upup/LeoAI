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
刷新当前工作区时可保留已有目录；切换工作区时先清空旧节点与实时会话，新工作区加载失败也不回显旧数据。
控制台通过 `POST /agent-node/capabilities` 同时读取能力和会话绑定的 `currentHostId`；未绑定时该字段为 `null`。
缓存会话的 `currentHostId` 表示缓存所属实例，不表示已连接。
`POST /agent-node/basic-info` 读取该实例的基础信息；缓存会话读取已有快照。该接口不返回接入节点的连接配置。
基础信息页的「已采集」表示已取得一次采集结果，不是持续在线检测。

代码中，`AbstractAgentNode` 表示平台侧的运行时适配对象，不是 `agents` 表中的配置记录。

平台 AI 面板在没有对话时调用 `POST /platform/ai/thread/initialize` 建立初始线程。
该接口和手动新建对话共用 `threadId`、`title`、`configId` 返回结构；两者均不创建接入节点。
