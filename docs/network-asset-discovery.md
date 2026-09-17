# 网络资产发现

工作流按配置依次执行主机探活、端口扫描、服务识别，以及显式启用的组件识别。节点负责执行探测，服务端负责计划与阶段编排，前端负责展示和操作。

## 扫描阶段配置

预览和启动请求通过 `scan.stages` 选择阶段，例如跳过探活：

```json
{
  "scan": {
    "targets": { "items": ["192.168.1.0/24"] },
    "stages": ["PORT_SCAN", "SERVICE_PROBE"],
    "portPolicy": { "profile": "quick" }
  }
}
```

| 配置 | 行为 |
| --- | --- |
| `REACHABILITY` | 仅探活并返回存活主机，不展开扫描端口或执行服务识别 |
| `PORT_SCAN` | 跳过探活，直接检查所有目标的端口开放状态 |
| `REACHABILITY, PORT_SCAN` | 探活后仅扫描存活主机的端口 |
| `PORT_SCAN, SERVICE_PROBE` | 跳过探活，扫描端口后识别开放端口上的服务 |
| `REACHABILITY, PORT_SCAN, SERVICE_PROBE` | 完整流程，也是未传 stages 时的默认行为 |

阶段去重后按固定顺序执行；空配置、未知阶段和缺少端口扫描的服务识别配置都会被拒绝。前端勾选服务识别时自动勾选端口扫描，取消端口扫描时同时取消服务识别。预览回传实际阶段，前端确认与所选阶段一致后才允许启动；配置变更会清空旧预览。

探活使用 TCP 连接，不使用 ICMP。仅探活时，裸主机使用 80、443、22、8080、8443；显式 `host:port` 或 URL 使用对应端口，端口扫描策略不参与。未连接成功表示未确认存活，不能据此断定主机离线。跳过探活时，只将实际发现开放端口的主机计为存活。

任务快照、持久化记录及进度只包含启用的阶段。因没有存活主机或开放端口而无法执行的后续阶段标记为跳过；暂停、继续、停止始终作用于当前实际执行的阶段。仅探活任务展示主机结果，不显示端口结果表。

## 组件识别

`FINGERPRINT` 是可选的第四阶段，依赖 `PORT_SCAN` 和 `SERVICE_PROBE`。未传 `stages` 时仍只执行原来的三个阶段。前端在“新建扫描”中勾选组件识别后，可以选择全部 HTTP 指纹、按任一标签筛选，或指定规则；规则来源为平台指纹管理。

```json
{
  "sessionId": "当前会话ID",
  "scan": {
    "targets": { "items": ["https://example.internal:8443/app/"] },
    "stages": ["PORT_SCAN", "SERVICE_PROBE", "FINGERPRINT"],
    "fingerprint": { "ids": ["spring-boot-actuator_any"], "tags": [] }
  }
}
```

`fingerprint.ids` 非空时优先使用指定规则，否则按 `tags` 筛选，两者为空时使用全部 HTTP 规则。预览与启动共用规则校验，未知规则、空集合、无效匹配表达式会被拒绝。启动时复制完整规则到任务配置，之后的文件修改或删除不影响该任务；每项识别结果记录规则内容的 SHA-256。

预览返回 `fingerprintRuleCount`、`fingerprintRequestsPerApplication`、`fingerprintProbeUpperBound`、`fingerprintMaxReadBytes`。请求上界按候选应用计算，不代表实际请求数。当前最多 64 条规则、每规则 16 个请求、组件阶段最多 50,000 个逻辑请求，并发最多 32；超过逻辑请求上限时该阶段失败，已发现的端口结果继续保留。规则声明的正文上限受节点 8192 字节读取限制约束，PHP 此限制包含 HTTP 头部。

端口探测仍按 IP/端口去重，组件识别保留同一端点下的不同域名及应用路径。显式 HTTP/HTTPS URL 可直接作为开放端口上的应用候选；普通主机输入使用服务识别确认的 HTTP/HTTPS 协议。URL 的路径被视为应用基路径，查询串和片段不参与基路径：`https://host/app/` 配合规则 `/actuator` 请求 `/app/actuator`。旧规则 `uri` 与编辑器的 `path` 均受支持，`uri` 非空时优先。Java 用域名 URL 发起 HTTP/TLS 请求；PHP 连接已解析地址并保留域名 Host/SNI。Java 请求期间仍可能重新进行 DNS 解析，不保证绑定到预览时的地址。

规则请求通过现有编排层分批下发，响应按应用、规则、请求序号聚合，在服务端执行 `all/any/not` 及叶子条件。超时或无有效 HTTP 状态码记为 `ERROR`，不能通过否定条件成为命中；截断响应中找到正向子串可以命中，无法判断的条件记为 `INCONCLUSIVE`，经 `not` 也不会变成命中。无 HTTP 应用时跳过该阶段。暂停、继续和停止作用于当前子任务。

结果分为 `MATCHED`、`NOT_MATCHED`、`ERROR`、`INCONCLUSIVE`、`PENDING`、`CANCELLED`。`scan_fingerprint_results` 保存每个应用规则的执行明细，`scan_endpoint_results.fingerprint_json` 保存多个组件及统计摘要，原始响应保存到现有 observations/evidence 表。三者在同一事务中提交后才确认结果游标，重复接收幂等；规则请求不覆盖端点的首页标题、状态码及服务摘要。服务端重启把未完成的证据标记为不足，历史任务仍可查看已完成识别。删除任务或销毁会话会级联清理识别明细。

新增的会话隔离查询接口：

- `POST /puppet-node/network-probe/workflow/fingerprints/query`：`sessionId/taskId/endpointId/page/pageSize`，返回规则执行明细，命中项优先。
- `POST /puppet-node/network-probe/workflow/fingerprints/evidence`：`sessionId/taskId/matchKey`，返回本次规则快照及已采集响应。
- 原结果查询的 `filter.hasFingerprint` 和 `filter.component` 支持组件筛选；任务摘要增加 `fingerprintCount`（应用规则命中数）和 `identifiedApplicationCount`（命中应用数）。

资产表展示组件标签，详情展示所有规则状态及请求响应证据，并可下载 JSON。历史任务未执行组件识别时显示“—”。规则中的 `info.version` 保持元数据含义，不作为实测组件版本；漏洞关联信息也不自动转为漏洞确认。

## 第二期：补扫、请求合并、版本与规则调试

资产表勾选已确认的 HTTP/HTTPS 开放资产后，点击“补扫组件”并选择规则。服务端从来源任务读取所选端点及原始应用地址，直接执行 `FINGERPRINT`，不重复探活、端口扫描或服务识别。结果写入独立任务，保留来源任务编号及原有首页摘要，来源结果不被改写。一次可选 1–256 个端点，仍受 64 条规则及 50,000 个逻辑请求限制。

补扫入口为 `POST /puppet-node/network-probe/workflow/fingerprints/start`：

```json
{
  "sessionId": "执行会话ID",
  "sourceTaskId": "来源扫描任务ID",
  "endpointIds": ["tcp|192.168.1.10|8080"],
  "fingerprint": { "ids": ["spring-boot-actuator_any"], "tags": [] }
}
```

端点来自数据库，不能通过此接口任意替换主机或端口；来源任务必须属于执行会话。普通新建扫描的阶段依赖保持不变。新任务均保留原始域名和应用路径，供后续补扫使用；老历史记录缺少这些信息时只能使用已保存端点的协议与地址。

同一任务、同一应用内，目标地址、方法、路径、请求头、正文、字符集、超时及响应读取上限一致的 GET/HEAD 请求会合并。POST 等其他方法保持独立执行。不同域名、应用路径或请求配置不会合并。规则分别判断，共享响应只保存一次，各规则通过请求编号引用该证据；不同任务之间不共用响应缓存。阶段进度按合并后的节点请求计数，阶段摘要另含 `logicalRequestCount`、`networkRequestCount`（合并后的计划请求数）和 `savedRequestCount`，预览仍为合并前的上限。

在规则编辑器“版本提取”中填写可选 JSON，对应 `rule.version`：

```json
{
  "requests": [{ "method": "GET", "path": "/", "timeout": 3000 }],
  "match": { "field": "headers", "operator": "contains", "value": "nginx/" },
  "version": { "request": 0, "field": "headers", "prefix": "nginx/" }
}
```

`request` 为从 0 开始的请求序号，默认 0；`field` 仅支持 `body`、`headers`。`prefix` 是必填的字面文本，可加 `suffix` 校验紧随版本的字面后缀；两者最多 256 字符，`ignoreCase` 默认 true。提取以数字开头、最长 64 字符的版本标记，后续允许数字、字母、点、下划线、加号和连字符，不执行自定义正则或脚本。例如响应 `Server: nginx/1.26.2` 可提取 `1.26.2`。

只有组件命中后才提取版本。结果包含 `detectedVersion`、`versionStatus` 和 `versionEvidence`（请求编号、响应字段、物理 probeId、字符偏移）。未找到版本记为 `NOT_FOUND`，响应截断导致无法确定版本边界则记为 `INCONCLUSIVE`；版本未知不会撤销已经成立的组件命中。`info.version` 继续用于规则命名和适用范围，不作为实际组件版本。资产标签、明细和导出均展示提取到的版本。

指纹管理详情的“调试”执行当前已保存规则；新增/编辑窗口的“调试当前草稿”执行打开调试面板时的草稿，不要求先保存。选择执行会话、输入单个 HTTP/HTTPS 应用根地址后启动独立任务，可停止并查看命中状态、各条件判断、实际请求参数和响应证据。关闭面板后任务继续，结果仍可在对应会话的网络资产发现历史中查询。

调试入口为 `POST /puppet-node/network-probe/workflow/fingerprints/debug`：

```json
{
  "sessionId": "执行会话ID",
  "target": "http://192.168.1.10:8080/app/",
  "fingerprint": {
    "name": "草稿示例",
    "rule": {
      "requests": [{ "path": "/info" }],
      "match": { "field": "body", "value": "component-marker" }
    }
  }
}
```

调试 URL 不接受认证信息、查询串或片段。规则校验和请求合并与正式扫描共用，条件轨迹仅在调试任务中保存。调试不修改指纹库；权限检查先于 DNS 解析和节点调用。通用任务查询、停止、历史及删除接口均可操作此任务；指纹明细查询省略 `endpointId` 可查看任务全部应用。

回归测试覆盖本机 HTTP 服务器与真实 Java 探测组件、补扫无端口探测、跨规则合并与证据重载、版本截断边界、来源会话隔离和未保存草稿。前端测试覆盖调试轮询、销毁后迟到响应、停止失败与重试、版本配置读写。本期不变更节点协议或 payload。

## 服务端职责

| 组件 | 职责 |
| --- | --- |
| `NetworkProbeController` | 校验会话访问权限、转换 HTTP 参数、返回结果；权限异常保留原状态码 |
| `ScanPlanService` | 校验阶段依赖，统一展开目标、选择端口、去重、校验执行参数及规模限制，输出只读计划 |
| `ScanPreviewService` | 从同一计划计算数量、结果规模估算和提示信息 |
| `NetworkProbeWorkflowService` | 推进所选扫描阶段，暂停、继续、停止任务，聚合服务识别证据 |
| `NetworkProbeAnalysisService` | 将阶段输入转为节点可执行的逻辑探测计划 |
| `NetworkProbeOrchestrationService` | 分批提交节点任务，收集增量结果，处理子任务生命周期 |
| `NetworkProbeResultStore` | 保存任务与结果，提供分页、筛选、排序和会话范围内的查询 |
| `SessionLifecycleManager` | 会话销毁时停止相关工作流并清理该会话的扫描记录 |

预览和启动分别调用同一个规划服务，不各自维护端口展开逻辑。显式 `host:port` 和 URL 只扫描指定端口；裸主机使用端口策略。同一主机混合输入时合并去重，保留 URL 信息。探活先保留显式端口，再在限制内补充默认及策略端口，避免输入顺序改变校验结果。

预览反映请求当时的计划，启动时重新解析和校验；域名的 DNS 记录变化时，实际地址可能不同。

## 节点运行时职责

Java 的 `NetworkProbeService` 与 PHP 的 `PhpPuppetNode` 只适配组件动作和参数。探测组件负责执行与证据采集，服务名称和规则匹配仍由服务端决定。

- Java `NetworkProbeComponent`：按动作分发、计划规范化、工作线程、协议探测、结果分页和生命周期划分方法。任务对象是状态、计数和结果缓冲区的唯一锁；共享线程池中的工作线程按需领取目标，不再维护额外锁表、信号量或每目标工作对象。队列满时拒绝提交并停止已接收的本次任务，避免请求线程同步执行扫描。
- PHP `NetworkProbeComponent.php`：按存储、探测、工作进程、清理和动作入口组织闭包。配置单独保存，状态快照不再重复包含目标列表和整个计划。状态写入使用临时文件及原子替换；写入、释放和启动失败回滚使用同一个任务文件锁。
- 协议探测保留 TCP 连接、TCP 交互、HTTP 摘要及规则请求能力。`http-head` 是兼容的阶段名，实际发出 GET 以提取标题；普通服务探测不返回完整响应正文。TCP 请求保留协议换行，PHP 原始响应字节只在生成文本证据时转换，避免二进制 Banner 导致 JSON 序列化失败。

Java 组件的 TCP 连接与交互共用连接和资源释放流程；HTTP 摘要与规则请求共用请求执行流程，分别由 `httpSummary`、`httpResponse` 提取所需证据。协议 I/O 与任务调度分开维护，避免两个 HTTP 分支在超时、请求头及流关闭行为上产生差异。

两个运行时均支持 `RUNNING → PAUSED → RUNNING`，自然完成或主动停止进入 `STOPPED`，通过 `outcome` 区分 `COMPLETED` 和 `CANCELLED`。终态保留到显式释放或过期清理；重复停止保留原结束时间和结论。空计划立即完成。

`queryTask` 按绝对游标读取增量结果；`ackTask` 只丢弃已确认的缓冲区前缀，游标不倒退，落后于确认位置的查询从当前缓冲区起点读取。停止后的在途响应不再追加结果或增加进度；释放后的 PHP 进程遇到状态文件缺失就退出。

暂停和停止在探测边界生效，已经开始的网络读写仍可能等待到完成或超时。PHP 在目标完成后按数量或时间间隔刷新批次；250 毫秒刷新阈值不意味着长时间网络读写期间也能持续返回新进度。

组件必须保持独立制品：Java 为单文件、单 class、Java 6 API/字节码兼容，PHP 为自包含文件。不能为抽公共逻辑引入其他节点组件依赖。修改 Java 源码后同步生成 `NetworkProbeComponent.payload`，并检查 API 兼容性及变换后加载。

## 前端职责

前端代码在 `LeoVueAi/src/components/PuppetConsole`：

- `Scan/ScanComposer.vue`：构建配置、请求预览、提交扫描；配置变更后丢弃旧预览响应。
- `Scan/useAssetDiscoveryTasks.js`：管理当前会话的任务选择和轮询。上次同步结束后才安排下一次；切换会话或销毁组件时失效旧请求并释放监听器、定时器。
- `File/TaskEngine.js` 与 `File/executors/scanExecutor.js`：统一承载任务中心状态、控制操作和服务端快照。控制操作推进请求版本，旧查询不得覆盖新状态；停止失败向调用方返回错误。
- `Scan/AssetDiscoveryView.vue`：组合任务状态、进度及操作入口。
- `Scan/AssetResultTable.vue`：按任务查询分页结果；会话、任务、搜索条件改变时失效旧请求。

任务中心持有可变对象，扫描页面通过新的展示快照触发 Vue 更新。页面的轮询不会另建一套后台任务；关闭页面只释放页面资源，会话销毁由服务端统一处理。

## 维护约束

新增输入规则或规模限制应进入规划层；节点传输限制进入分批执行层。前端不重复展开 CIDR 或组合主机端口。修改异步行为时重点验证慢响应、停止失败、任务删除和会话切换，避免用固定文案或源码字符串测试代替这些回归用例。
