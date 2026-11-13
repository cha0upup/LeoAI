# 模块与包边界

本轮先整理包归属，保留现有 Maven 模块、HTTP/SSE 接口和 RPC Component ID。`web` 仍是组合根，应用编排暂留其中，不移入 `service`，也不新增 `application` 模块。

## web

| 包 | 职责 |
| --- | --- |
| `controller`、`dto`、`security`、`config` | HTTP 入口、请求响应、鉴权和容器装配 |
| `adapter.ai` | SSE 订阅与发送、附件输入和 AI 工具入口 |
| `adapter.ssh`、`adapter.sql`、`adapter.skill` | SSH 连接实现、数据库接口错误映射、上传适配 |
| `application.ai` | Turn 排队与执行、租约、线程生命周期、timeline 事件和节点 AI 委派 |
| `application.agent`、`application.artifact` | 节点生命周期、会话维护、报告及用户制品用例 |
| `infrastructure.concurrent`、`infrastructure.discovery` | 有界执行器与 JDBC 探测结果存储 |

Controller 负责 HTTP Session 的读取与绑定；线程用例只接收会话标识和用户信息。应用编排不直接引用 Servlet、Spring MVC/HTTP 类型或 Controller 工具。

主 Turn 与委派 Presenter 只写运行时事件日志、整理 timeline 和处理执行收尾，不持有 `SseEmitter`。SSE 订阅独立读取持久化日志，负责发送、心跳、重连和连接关闭；后台执行不再启动一条发送目标为空的事件排空线程。工具排队事件在模型回调与执行边界同步归档，复用已持久化的事件，不重复写日志。

调度器用非阻塞执行槽位保留原有活跃 Turn 并发上限，槽位持续到异步执行完成；满载时任务留在数据库队列，由后续调度恢复。执行任务与 SSE 订阅仍使用独立的有界线程池。

当前仍有刻意保留的过渡依赖：应用用例复用现有 DTO、`ApiException` 和平台工具注册。包整理不等同于独立模块拆分；后续确认边界稳定后，再按实际需要收敛这些依赖，避免先增加一组只负责转发的接口和结果包装。

## core 与运行时实现

| 包 | 职责 |
| --- | --- |
| `core.agent.capability`、`core.agent.database` | 共享能力和数据库输入契约 |
| `core.runtime`、`core.generator`、`core.component.runtime`、`core.rpc` | 运行时/生成器 SPI、组件交付模型和 RPC 协议 |
| `core.net` | 通信接口、异常与传输限制 |
| `core.entity`、`core.session`、`core.net.layer` 等 | 共享模型、会话运行态和协议层；其中仍有实现依赖，不视作纯契约 |
| `core.infrastructure.transport` | HTTP、转发、隧道等通信实现 |
| `core.infrastructure.persistence.session` | 文件会话缓存、快照和工作目录 |
| `core.infrastructure.bytecode` | ASM、Javassist 和反编译实现 |
| `javacore.agent`、`javacore.component` | Java 节点、服务、数据库适配和 Component 实现 |
| `phpcore` | PHP 运行时实现 |

上述核心契约包保持独立于 `core.infrastructure`；基础设施本轮仍在 `core` 模块内部，尚未实现物理模块隔离。Java 实现全部归入 `org.leo.javacore.*`，不再与共享契约共用 `org.leo.core.*` 包名。

## 依赖示意

实线表示主要调用/依赖方向，虚线标出本轮保留的过渡协作；这不是新增 Maven 模块。

```mermaid
flowchart TD
    subgraph web[web 组合根]
        http[controller / dto / security / config]
        adapter[adapter：SSE / SSH / 上传 / 工具入口]
        app[application：Turn / 委派 / 节点生命周期]
        infra[infrastructure：执行器 / 探测结果仓储]
        http --> app
        http --> adapter
        adapter --> app
        app --> infra
        app -. 平台工具注册 .-> adapter
    end
    app --> ai
    app --> service
    ai --> service
    service --> dao
    service --> jmg
    web --> java[javacore]
    web --> php[phpcore]
    java --> contracts[core 核心契约]
    php --> contracts
    ai --> contracts
    service --> contracts
    dao --> contracts
    jmg --> contracts
    java --> implementation[core.infrastructure]
    models[core 共享模型与会话运行态] --> contracts
    models --> implementation
    implementation --> contracts
```

## 检查与部署

- 现有 `tools/check_architecture.py` 检查模块 DAG、应用层 HTTP 边界、核心契约到基础设施的反向引用及 Java 实现命名空间；测试继续集中在现有检查器测试文件中。
- 运行 `python3 tools/test_check_architecture.py`、`python3 tools/check_architecture.py` 和 `./mvnw verify`。
- Java Component 包名变更后，通过既有 `javacore/compile-components.sh` 重编译 `.payload`，再使用 `--check` 验证 Java 6 字节码/API 及源码一致性。
- 包名是 Java 内部兼容性变更；引用旧类名的插件、脚本或缓存字节码需要重新构建。前端接口和数据库结构保持原样。
