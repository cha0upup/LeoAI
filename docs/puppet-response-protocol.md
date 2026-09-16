# Puppet 侧返回协议分析

日期：2026-07-18

## 1. 分层模型

Puppet 的返回数据不是固定的 HTTP JSON，而是四层结构：

1. **HTTP/Servlet/PHP SAPI 外层**：负责读取请求体、设置 HTTP 状态和响应头。
2. **Disguise 编解码层**：把 `Map/array` 编码为实际请求体；具体格式由所选 Disguise 决定。
3. **Core RPC 层**：通过 `M` 区分连接测试、转发、组件加载和组件调用。
4. **Component 结果层**：组件返回 `code`、`msg` 以及能力特有字段。

因此，文档中的对象均指 **Disguise 解码之后的逻辑对象**，不代表线上一定是明文 JSON。

## 2. 外层传输

### 2.1 普通 HTTP

- Java JSP/JSPX HTTP wrapper 读取完整请求体，交给 Java Core，随后将 Core 编码后的字节直接写入响应体。
- PHP wrapper 读取 `php://input`，执行 `request decode -> phpcore -> response encode`。
- 外层 HTTP 状态码由生成配置决定，通常不等于组件对象中的 `code`。
- PHP wrapper 发生未处理异常时生成逻辑对象 `{"code":500}`；响应编码本身失败时才将 HTTP 状态改为 500。

### 2.2 Java HTTP Chunk

Java JSP/JSPX chunk wrapper 使用大端长度前缀帧：

```text
request  = int32 length + length bytes
response = int32 length + length bytes
```

特殊帧：UTF-8 文本 `heartbeat` 原样返回 `heartbeat`。其余帧逐帧进入同一个 Java Core。

### 2.3 二进制字段

PHP portable JSON 协议用以下对象表达二进制：

```json
{"$leoBinary":"BASE64"}
```

该标记递归生效；平台解码后恢复为 `byte[]`。Java协议中的二进制值在逻辑层直接为 `byte[]`，最终表示方式由 Disguise 决定。

## 3. Core RPC 协议

### 3.1 公共请求字段

| 字段 | 类型 | 说明 |
|---|---|---|
| `M` | number | Core 操作码，0–3 |
| `hostId` | string | M2/M3 的实例校验；M0 不要求 |
| `componentName` | string | M2/M3 的组件 ID |
| `action` / `op` / `methodName` | string/number | 组件内部操作选择器 |

Padding 字段可能附加到请求 Map，但解码后不属于业务返回协议。

### 3.2 Java Core

| M | 操作 | 请求特有字段 | 返回 |
|---:|---|---|---|
| 0 | test | 无 | `code:200, hostId:string, components:string[]` |
| 1 | redirect | `rUrl, headers, body:byte[]` | `reqUrl:string, respData:byte[]`；没有固定 `code` |
| 2 | load | `componentName, bytecode:byte[], hostId` | 成功 `code:200` |
| 3 | invoke | `componentName, hostId, ...componentParams` | 组件写入的 Map |

Java Core 的统一异常返回：

```json
{"code":500,"msg":"exception message"}
```

Java M2/M3 的 `hostId` 不匹配时不会执行操作，Core 原始结果为空对象；管理端通常把空解码结果归类为通信失败并最终转换为 `code:500`。

### 3.3 PHP Core

| M | 操作 | 请求特有字段 | 返回 |
|---:|---|---|---|
| 0 | test | 无 | `code:200, hostId:string, components:string[]` |
| 1 | forward | `rUrl, headers, body:binary` | `code:200, reqUrl:string, respData:binary` |
| 2 | load | `componentName, componentKey, source, hostId` | `code:200, cached:boolean` |
| 3 | invoke | `componentName, componentKey, action, hostId, ...params` | 组件结果 |

PHP Core 补充规则：

- 组件返回非数组时包装为 `{"result": value}`。
- 组件结果缺少 `code` 时自动补 `code:200`。
- 组件文件不存在时返回 `code:424`。
- M2/M3 `hostId` 不匹配时返回 `code:403`。
- 未知 `M` 返回 `code:404`。
- wrapper 捕获异常后返回 `code:500`。

### 3.4 管理端映射

PHP `PhpRpcClient` 使用统一 Envelope 映射：成功响应的 `data` 对象字段映射到组件结果顶层，错误 Envelope 的 `error.message` 映射为组件结果的 `msg`。组件错误必须使用 `msg`。

Java `ComponentService` 使用内部字段 `reqStatus/reqMsg` 表示编解码与通信状态；成功返回前会移除 `reqStatus`，重试耗尽后转换成 `code:500, msg`。

## 4. 状态码语义

这些是逻辑对象状态码，不是外层 HTTP 状态码。

| code | 当前语义 |
|---:|---|
| 100 | PHP 文件下载还有后续分块 |
| 200 | 成功，或操作已接受 |
| 204 | 当前没有可读数据，但连接仍存在 |
| 400 | 参数、action、op 或 methodName 无效 |
| 403 | PHP Core 的 hostId 不匹配 |
| 404 | 任务、连接、监听器或资源不存在；部分场景也表示对端关闭 |
| 409 | 重复 connId/listenId 或目标冲突 |
| 413 | 请求、响应或资源超过组件限制 |
| 416 | 文件下载 offset/range 无效 |
| 424 | PHP 组件尚未加载 |
| 500 | 组件异常、I/O 失败或通信失败 |
| 501 | Java Screen 组件在当前环境不受支持 |
| 503 | PHP 扩展、驱动或后台 worker 缺失 |
| 504 | PHP worker 启动或连接超时 |

## 5. PHP Component 返回协议

### 5.1 BasicInfoComponent

- action：管理端发送 `get`，组件实际忽略 action。
- 返回：`code, BasicInfo`。
- `BasicInfo`：
  - `collectTime`
  - `OSInfo`
  - `UserInfo`
  - `MiddlewareInfo`
  - `PhpRuntimeInfo`
  - `ProcessInfo`
  - `EnvironmentInfo`
  - `HardwareInfo`
  - `NetworkInfo[]`
  - `FileSystemInfo[]`

### 5.2 ExecCommandComponent

公共参数：`processId, cmd?, includeOutput?`。`cmd` 仅用于 `write/resize`，`includeOutput` 仅用于 `init/write`。

| action | 返回字段 |
|---|---|
| `init`（无 cmd） | `code, initialized, alive, pty, resizable, backend, instanceId, backendFailures` |
| `write` | `code, written, alive, pty, resizable, backend, instanceId, backendFailures` |
| `read` | `code, data:binary, alive, eof, hasMore?, busy?, pty?, resizable?, backend?, exitCode?, instanceId, backendFailures?` |
| `resize` | `code, cols, rows, pty, resizable, resized, instanceId` |
| `stop` | `code, stopped, alive, instanceId` |

不存在的 read 会返回 `code:200, data:empty, alive:false, missing:true, eof:true`。

`alive:false` 只表示进程结束，客户端仍须读取到 `eof:true` 才能停止读取并刷新 UTF-8 解码器。`hasMore` 表示当前尚有缓冲输出；Java 输出采集线程尚未读完时允许 `alive:false, hasMore:false, eof:false`。当前终端协议要求明确返回 `eof`，不以空数据代替 EOF。

PHP 命令模式在命令执行时返回 `busy:true`。写入请求释放会话锁，允许独立请求读取输出；单独写入 `\x03` 请求中断，`stop` 请求结束会话。正在执行时拒绝其他写入（组件码 409）。单次提交共享 20 秒执行预算，每条命令输出上限为 1MiB，客户端写入请求超时为 45 秒；该模式不保留跨命令环境变量，也不支持交互式标准输入。组件的非 200 返回在 HTTP 控制器中作为失败处理，并记录失败审计。

### 5.3 ExecCommandSimpleComponent

- 管理端 action：`exec`；组件实际忽略 action。
- 参数：`cmd, timeoutSeconds?`。
- 原始组件返回：`stdout, stderr, output, exitCode`。
- PHP Core 最终补充 `code:200`。

### 5.4 FileComponent

| action | 返回字段 |
|---|---|
| `roots` | `code, absolutePath, fileList[], count` |
| `list` | `code, absolutePath, fileList[], count` |
| `md5` | `code, md5, filePath, fileSize` |
| `mkdir` | `code, success, absolutePath` |
| `delete` | `code, success` |
| `create` / `edit` | `code, success, absolutePath` |
| `copy` / `move` | `code, success, newPath` |

`fileList[]` 项字段：`name, path, isDirectory, isFile, size, modified, permissions, canRead, canWrite, canExecute, exists, extension`。

### 5.5 FileDownloadComponent

- 参数：`path, offset, size`；action 被忽略。
- 返回：`code, data:binary, offset, length, bytesRead, nextOffset, isComplete`。
- `code=100` 表示还有后续块，`code=200` 表示完成。

### 5.6 FileUploadComponent

- 参数：`path, offset, data:binary`；action 被忽略。
- 返回：`code, success, written, offset, nextOffset`。

### 5.7 CompressComponent / DecompressComponent

- 参数：`src, des`；`exclude/format` 当前未影响 PHP 实现；action 被忽略。
- 压缩返回：`code, success, path, size`。
- 解压返回：`code, success, path`。

### 5.8 ExecScriptComponent

- 参数：`language=php, script`；action 被忽略。
- 原始返回：`output, returnValue`；Core 补 `code:200`。

### 5.9 DatabaseComponent

- action：空字符串或 `exec`。
- 参数：`provider=pdo, pdoDriver, dsn, username?, password?, timeoutSeconds?, sql`。
- 固定返回字段：
  - `code, msg`
  - `columns[]`
  - `rows[]`
  - `rowCount`
  - `affectedRows`
  - `generatedKey`
- 成功时附加：`serverVersion, runtimeMetadata:{provider:"pdo",driver}`。
- `columns[]` 至少包含 `name, label, type, nativeType`，并可能包含 `length, precision, table`。

### 5.10 HttpRequestComponent

- 参数：`method, url, headers?, body?, connectTimeout?, readTimeout?, followRedirects?`。
- action 被忽略。
- 返回：
  - `code:200`
  - `statusCode, statusMessage`
  - `responseHeaders`
  - `body, bodyType, bodySize`
  - `backend`：`curl` 或 `stream`
  - `elapsedMs, effectiveUrl, redirectCount`
  - 可选 `truncated, truncateReason`
- 文本 body 为 string，二进制 body 使用 binary marker。

### 5.11 PluginComponent

- 参数：`source, pluginParams`。
- 插件返回数组时直接作为组件结果；非数组时包装为 `result`。
- Core 在缺少 `code` 时补 `code:200`，因此插件返回字段属于动态协议。

### 5.12 ProxyForwardComponent

公共参数：`connId`。

| op | 操作 | 返回 |
|---:|---|---|
| 0 | open | `code, msg` |
| 1 | write | `code, bytesWritten` |
| 2 | read | 有数据：`code:200, bytesRead, data`；暂无数据：`code:204, bytesRead:0, data:empty` |
| 3 | close | `code:200, msg` |

open 额外参数：`targetHost, targetPort, connectTimeout?`。

### 5.13 ReverseTunnelComponent

| op | 操作 | 返回 |
|---:|---|---|
| 0 | start listen | `code, msg, listenPort, bindAddr` |
| 1 | stop listen | `code, msg` |
| 2 | accept/poll | `code, newConns[]` |
| 3 | read | `code, bytesRead, data`，暂无数据时 code 204 |
| 4 | write | `code, bytesWritten` |
| 5 | close connection | `code, msg` |
| 6 | list listens | `code, listens[]` |

`newConns[]`：`connId, clientAddr, clientPort`。`listens[]`：`listenId, listenPort, bindAddr`。

## 6. Java Component 返回协议

Java组件必须自行写入 `code`；Core不会像 PHP Core 一样自动补充。

### 6.1 基础、系统与容器

| Component | 操作选择 | 顶层返回字段 |
|---|---|---|
| BasicInfoComponent | 无 | `code, BasicInfo, msg?` |
| ScreenComponent | 无；参数 `format,quality,delay` | `code, screenBytes, format, width, height, imageSize, captureTime, timestamp, errorType?, msg?` |
| SpringFrameworkManageComponent | methodName=`getFrameworkInfo/removeController/removeInterceptor` | 查询返回 `code, frameworkInfo`；修改返回 `status, matched, changed, verified, code` |
| TomcatContainerManageComponent | methodName=`inspectRuntime/removeFilter/removeServlet/removeValve/removeListener` | 查询返回 `code, contexts, features`；修改返回 `status, matched, changed, verified, code` |
| WeblogicContainerManageComponent | methodName=`inspectRuntime/removeFilter/removeServlet/removeListener` | 查询返回 `code, contexts`；修改返回 `status, matched, changed, verified, code` |
| GenericServletContainerManageComponent | methodName=`inspectRuntime` | `code, contexts`；通用适配器严格只读 |

Java `BasicInfo` 的主要子对象：`OSInfo, UserInfo, MiddlewareInfo, JavaRuntimeInfo, ProcessInfo, EnvironmentInfo, HardwareInfo, NetworkInfo[], FileSystemInfo[]`。

Web Runtime HTTP API 使用 schemaVersion=2，顶层为 `scanId, runtimes[], diagnostics[]`。
每个 runtime 包含 `runtimeId, family, productVersion, profileId, strategyId,
servletApiVersion, namespace, features, capabilities, contexts[], frameworks[]`。

容器 payload 返回中的主要集合：

- Tomcat：`allFilter, allServlet, allValve, allListener`。
- WebLogic：`allFilter, allServlet, allListener`。
- Spring：`allController, allMappedInterceptor`。

### 6.2 命令、脚本和插件

| Component | 操作选择 | 返回字段 |
|---|---|---|
| ExecCommandComponent | op 0 write | `code, written, alive, terminalMode, terminalModes, pty, lineInput` |
| ExecCommandComponent | op 1 read | `code, data:byte[], alive, exitCode?, error?, missing?` |
| ExecCommandComponent | op 2 stop | `code, stopped, alive` |
| ExecCommandComponent | op 3 resize | `code, resized, cols, rows` |
| ExecCommandComponent | op 4 write-line | `code, written, alive, lineInput, pty, terminalMode` |
| ExecCommandComponent | op 5 read-batch | `code, instanceId, terminals:Map<processId, readResult>` |
| ExecCommandComponent | op 6 init | `code, initialized, alive, terminalMode, terminalModes, pty, lineInput` |
| ExecCommandSimpleComponent | 无 | `code, data:byte[], exitCode, timedOut, msg?` |
| ExecScriptComponent | 无 | `code, result?, msg?` |
| PluginComponent | 无 | `code, result?, msg?` |

Java 终端默认 `pipe`。初始化 `type=init` 可额外指定 `terminalMode=pipe|python-pty`，会话建立后模式不可更改。Java Python PTY 仅支持 Unix，依赖 Python 标准库 `pty`；启动失败不自动降级。平台仅在 PTY 初始化时发送三行 `ptyBridge` 源码，由标准库 `pty.spawn` 启动交互 shell，输入输出均直接传递原始字节，无启动握手。Java 的 PTY `exitCode` 表示桥接进程退出码，正常结束通常为 0，不再转发子 shell 的退出码。Java 两种模式均返回 `resizable=false`，resize 请求返回 `resized=false`，不向进程发送尺寸控制。返回的 `terminalModes` 表示该操作系统支持选择的模式，不保证节点已安装兼容的 Python。管道与 Python PTY 均不创建 Java 终端辅助文件。

Java PIPE 响应报告 `lineInput=true`，允许前端本地编辑、回显后通过 HTTP `type=write-line`（组件 `op=4`）提交完整行。整个输入必须以 LF 结束且不超过 1 MiB；节点不回显、不解释编辑按键，Windows 转为节点字符集和 CRLF。PTY 不接受此操作。会话不存在时写入报错，不隐式创建；读取仍返回 `missing=true, eof=true`。PHP 命令终端及 PTY 使用 `write` 流式输入。前端流式输入采用 100ms 合并窗口，控制输入及时发送。

HTTP `read.cmd` 为空时不等待；非空时必须为 32 位非负整数，非法值返回 400，超出 10000ms 则限制到 10000ms。Java 适配器对内部调用执行相同校验，再发送整数 `waitMs`。Java 节点的 `op/waitMs` 仅接受整数、`includeOutput` 仅接受布尔值、`terminalMode` 仅接受字符串，`cmd/processId/processIds/ptyBridge` 仅接受 UTF-8 字节数组。PHP `cmd` 仅接受字符串，不支持长轮询。

`init` 是唯一可创建会话的操作；`write` 中的 `init` 是普通文本，客户端不再拆帧。重复初始化运行中的会话不会清空输入、重启进程或重置输出序号；`terminalMode` 仅允许在初始化时指定，已有会话不能改变模式。HTTP `init/stop` 不接受命令，初始化审计为 `COMMAND_INIT`，审计目标为进程 ID。

Java 前台读取请求 `waitMs=10000`，服务适配器与节点组件均将等待限制在 0–10000ms；有输出、进程退出或停止时提前返回。浏览器长轮询请求超时为 30 秒，响应后在下一个调度周期续接，不叠加空闲延迟。不支持长轮询的前台终端连续空读后按 3、5、10、20 秒降频，后台使用非阻塞读取且间隔至少 5 秒，连续空读后为 5、5、10、20 秒。输入、激活或输出重置空闲计数；`hasMore` 和退出后的排空跳过空闲间隔。

终端初始化和写入可携带 `includeOutput=true`，Java/PHP 适配器将该标志传入同一次组件调用。写入成功后非阻塞地消费最多 64 KiB 当前输出，嵌套在写响应的 `output` 字段中；该字段使用读取响应结构（`code, instanceId, outputSequence, data, alive, eof, hasMore` 等）。附带输出失败返回 `output.code=500, output.msg`，外层保持写入成功，客户端不可因此重发输入。该标志仅用于初始化与写操作；内部命令执行器或等待早先输出响应的前端可不携带它，将输出留给读取请求。

写响应内的输出、单次读取及批量读取共用每进程从 1 递增的 `outputSequence`，空读也分配序号；缺失进程无序号，返回 `missing=true`。序号和字节消费在同一临界区完成。前端按序解码并处理 EOF；缺口存在时暂停新增输出消费，所有在途请求结束后仍缺失的序号会触发丢失提示，再继续显示后续输出。当前客户端要求新序号协议，服务器和节点组件需同步更新，既有终端需重新创建。

Java/PHP 组件报告 `batchRead=true`。HTTP `POST /puppet-node/command/read-batch` 接收 `{sessionId, processIds:[...]}`，每批限制为同一节点的 1–16 个不同进程。HTTP 层先校验完整列表，再复用会话权限与终端能力检查。Java 适配器发送 `op=5`，`processIds` 为 LF 分隔的 UTF-8 字节；PHP 适配器发送 `action=read-batch` 和 `processIds` 数组。目标端再次校验完整列表后一次读取，每个进程最多 64 KiB，不等待新输出。

批量响应的 `terminals` 按进程 ID 索引，每项保留独立 `code`、`instanceId`、`outputSequence`、`data:byte[]`、`alive`、`eof` 和 `hasMore`。缺失进程返回 `missing=true, alive=false, eof=true`；单项失败返回 `code=500, msg`，其他项继续执行。PHP 批量读取不等待被占用的状态锁或输出锁。前端只合并同一调度轮次的普通读取，前台长轮询独立发送。网络失败不会立即降级重放已消费的读取。

Java插件参数为 `pluginBytecode, pluginParam`；PHP插件则使用 `source, pluginParams`，两者不是同一传输结构。

### 6.3 文件、资源和归档

| Component | 操作 | 返回字段 |
|---|---|---|
| FileComponent | action 1 list | `code, fileList, absolutePath, count` |
| FileComponent | action 2 delete | `code, msg, failedFiles?, failedCount?` |
| FileComponent | action 3 mkdir | `code, msg, absolutePath?` |
| FileComponent | action 4 create | `code, msg, filePath?, size?` |
| FileComponent | action 5 move | `code, msg, newPath?, skipped?, warning?` |
| FileComponent | action 6 roots | `code, fileList, count` |
| FileComponent | action 7 edit | `code, msg, filePath?, size?` |
| FileComponent | action 9 copy | `code, msg, newPath?, skipped?, warning?` |
| FileComponent | action 10 md5 | `code, md5, filePath, fileSize` |
| FileDownloadComponent | 无 | `code, data, offset, length, bytesRead, nextOffset, isComplete, msg?` |
| FileUploadComponent | 无 | `code, bytesWritten, nextOffset, msg?` |
| ResourceComponent | 无 | `code, resourcePath, size, data, msg?` |
| CompressComponent | 无 | `code, format, sourcePath, zipFile, msg` |
| DecompressComponent | 无 | `code, format, fileCount, dirCount, totalSize` 加格式相关路径字段 |

`ResourceComponent` 和 server 侧均使用 `data` 承载资源字节。

FileEnhanceComponent 使用数字 action：

| action | 操作 | 返回字段 |
|---:|---|---|
| 1 | grep | `code, matches, matchCount, totalFiles, scannedFiles, truncated` |
| 2 | touch | `code, newTime, modifiedCount?, msg?` |
| 3 | pack | `code, archivePath, archiveName, archiveSize, msg?` |
| 4 | rename | `code, newPath, msg?` |
| 5 | chmod | `code, mode, modifiedCount?, msg?` |

### 6.4 数据库与 HTTP

| Component | 返回字段 |
|---|---|
| DatabaseComponent | `code, columns, rows, rowCount, affectedRows, generatedKey, runtimeMetadata, msg?` |
| HttpRequestComponent | `code, statusCode, statusMessage, responseHeaders, body, bodyType, bodySize, truncated?, truncateReason?, charsetFallback?, msg?` |

Java Database 的连接参数为 `driverClass, jdbcUrl, username, password, sql`；PHP 使用 PDO 参数结构。

### 6.5 统一网络探测任务

NetworkProbeComponent 的 `methodName`：

| methodName | 返回 |
|---|---|
| `startTask` | `code, taskId` |
| `queryTask` | `code, result`；请求 `taskId, cursor, maxItems, maxBytes, includeEvidence`，返回 `incremental, cursor, nextCursor, hasMore, observations[]` |
| `ackTask` | `code, cursor`；仅在服务端完成持久化后确认游标，节点才可回收已确认页 |
| `pauseTask` / `resumeTask` / `stopTask` | `code, status?` |
| `releaseTask` | `code`；仅供服务端编排器在子任务终止后释放节点资源 |

对外 API 返回服务端逻辑任务。一个逻辑任务最多包含 8192 个探测目标，服务端按每批 128 个目标串行创建节点子任务，并在收集最终快照后调用 `releaseTask`。节点子任务 ID 不暴露给前端。

主机探活不再使用单独的原子接口。调用方通过
`/puppet-node/network-probe/workflow/start` 提交 `scan.targets.items[]`、
`scan.portPolicy` 和 `scan.execution`，再复用 workflow 的
`query / pause / resume / stop` 生命周期。服务端负责目标解析、端口策略展开和阶段编排。

逻辑任务的 `result` 包含 `taskId, scanKind, status, outcome, total, completed, progress, batchCount, batchIndex, targets, plan, observations, errors, createdAt, finishedAt?`。`status` 供任务控制使用，取值为 `RUNNING / PAUSED / STOPPED`；`outcome` 表示最终结果，取值为 `RUNNING / COMPLETED / CANCELLED / FAILED`。`batchIndex` 是已启动的批次数，`batchCount` 是总批次数。节点 observations 通过游标增量页传输并在服务端持久化，前端不得依赖完整结果数组。

### 6.5.1 单任务多阶段扫描工作流

工作流 API 将探活、端口扫描和服务识别串联为一个用户可见任务。HTTP 指纹和漏洞验证暂不属于该工作流：

| API | 请求关键字段 | 返回 |
|---|---|---|
| `POST /puppet-node/network-probe/workflow/start` | `sessionId, scan.targets.items[], scan.portPolicy, scan.execution?` | `code:200, taskId` |
| `POST /puppet-node/network-probe/workflow/query` | `sessionId, taskId` | `code:200, result` |
| `POST /puppet-node/network-probe/workflow/pause` | `sessionId, taskId` | `code:200, status:PAUSED` |
| `POST /puppet-node/network-probe/workflow/resume` | `sessionId, taskId` | `code:200, status:RUNNING` |
| `POST /puppet-node/network-probe/workflow/stop` | `sessionId, taskId` | `code:200, status:STOPPED` |
| `POST /puppet-node/network-probe/workflow/tasks` | `sessionId` | `code:200, tasks[]`；仅返回任务摘要 |
| `POST /puppet-node/network-probe/workflow/results/query` | `sessionId, taskId, page, pageSize, filter?, sort?` | `code:200, total, page, pageSize, hasMore, endpoints[]` |
| `POST /puppet-node/network-probe/workflow/evidence/query` | `sessionId, taskId, endpointId` | `code:200, endpointId, evidence[]`；证据按需读取 |

工作流 `result` 包含 `scanKind=network-workflow`、`status`、`outcome`、`progress`、`currentStage`、`stageCount`、`completedStageCount`、`stages[]`、`reachableHostList`、`openPortResults` 和 `serviceResults`。`stages[]` 的阶段名固定为 `REACHABILITY`、`PORT_SCAN`、`SERVICE_PROBE`，每个阶段包含 `status`、`progress`、`total`、`completed`，因前置阶段无结果时可标记为 `SKIPPED` 并返回 `reason`。

工作流只在服务侧做阶段编排和结果聚合，底层网络连接、读取和节点任务生命周期仍复用 NetworkProbeComponent；`probeServices=false` 时跳过服务识别阶段，未发现存活主机或开放端口时自动跳过后续阶段。

结果面采用“节点游标分页 + 服务端 SQLite 持久化 + 前端服务端分页”。单页同时受条数和字节数上限约束，ACK 丢失时允许重放并由服务端去重；大证据默认不随列表返回，只通过 evidence API 按端点读取。

节点只返回受限网络证据；当前工作流只聚合端口和服务识别结果。
探活任务的 `result.analysis` 额外包含 `kind=reachability, total, completed,
reachableHostList, unreachableHostList, pendingHostList, hitCount`。运行期间仅完成全部探测且未开放端口的主机进入 `unreachableHostList`，未完成主机不会被误报为不可达。

### 6.6 凭据结果

CredentialHarvestComponent：

| op | 类型 |
|---:|---|
| 0 | all |
| 1 | dataSources |
| 2 | systemProperties |
| 3 | envVars |
| 4 | jndiDataSources |
| 5 | springEnvProperties |

顶层返回：`code, credentials, msg?`。

`credentials` 根据 op 包含 `dataSources, systemProperties, envVars, jndiDataSources, springEnvProperties, errors` 等集合；条目常见字段包括 `source, key, value, beanName, className, url, username, password, jndiPath`。

### 6.7 代理与隧道

ProxyForwardComponent：

| op | 返回 |
|---:|---|
| 0 open | `code, msg?` |
| 1 write | `code, bytesWritten` |
| 2 read | `code, bytesRead, data`；暂无数据时 code 204 |
| 3 close | `code, msg?` |

ReverseTunnelComponent：

| op | 返回 |
|---:|---|
| 0 start listen | `code, listenPort, bindAddr, msg?` |
| 1 stop listen | `code, closedConns?, msg?` |
| 2 accept | `code, newConns` |
| 3 read | `code, bytesRead, data` |
| 4 write | `code, bytesWritten` |
| 5 close | `code, msg?` |
| 6 list | `code, listens` |

## 7. PHP/Java 同能力差异

| 能力 | Java | PHP |
|---|---|---|
| Core hostId 不匹配 | 空结果，管理端最终常归一为 500 | 明确 403 |
| 组件缺失 | 管理端按缓存决定先加载 | Core 明确 424，客户端收到后重载 |
| 组件成功码 | 组件必须写 `code` | Core 自动补 `code:200` |
| 文件下载进行中 | 通常依靠 `isComplete` | `code:100` + `isComplete:false` |
| 文件上传字节数 | `bytesWritten` | `written` |
| 一次性命令输出 | `data:byte[]` | `data:string` |
| 终端操作选择 | 数字 `op` | 字符串 `action` |
| 文件操作选择 | 数字 `action` | 字符串 `action` |
| 压缩结果 | `sourcePath/zipFile/format/msg` | `success/path/size` |
| 解压结果 | 统计与格式相关路径字段 | `success/path` |
| HTTP元数据 | 通用响应与截断字段 | 额外 `backend/elapsedMs/effectiveUrl/redirectCount` |
| 插件输入 | `pluginBytecode/pluginParam` | `source/pluginParams` |

## 8. 兼容性注意项

1. `code` 是业务状态码，调用方不应使用外层 HTTP 状态判断组件成功。
2. 成功判断宜采用 `200 <= code < 300`；但 PHP 下载的 `code:100` 是正常中间状态。
3. `204` 表示轮询暂时无数据，不等于连接关闭。
4. `404` 在网络组件中可能表示 peer closed，而不只是资源从未存在。
5. Java与PHP的同能力通过统一能力接口暴露，控制器只读取规范字段；运行时差异由适配器内部处理。
6. PHP插件返回属于动态协议，平台只能保证最终存在数值型 `code`。
7. Disguise 多层转发时，中间层响应固定嵌套在 `respData` 中，最内层才是业务对象。
