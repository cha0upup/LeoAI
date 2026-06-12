---
name: develop-fingerprint
description: 当用户希望在平台侧编写、生成、完善、检查、保存、更新或删除指纹规则时使用。该 skill 用于生成符合 FingerprintComponent 约束的 HTTP/TCP 指纹 rule.requests 与 rule.script，并要求通过平台 FingerprintTools 查询已有指纹、读取详情、保存和删除，不直接读写 VFS 指纹文件。
---

# 开发平台侧指纹

使用这个 skill 辅助用户编写平台指纹。所有平台指纹管理动作都必须通过 Tools 完成，不要让 Agent 自己读写 `root/fingerprint`、VFS 文件或本地 JSON 文件。

可用工具来自 `platform_fingerprint_agent`，对应 `FingerprintTools`：

- `getFingerprints()`: 获取全部指纹摘要。
- `getFingerprintsByProtocol(protocol)`: 按 `http`、`tcp` 等协议筛选摘要。
- `getFingerprintById(fingerprintId)`: 获取完整指纹对象，包含 `rule`。
- `saveFingerprint(userId, name, ruleJson, infoJson, protocol, tagsJson, version)`: 创建或覆盖保存指纹。
- `deleteFingerprint(userId, fingerprintId)`: 删除指定指纹。

如果需要保存或删除但缺少 `userId`，优先通过平台用户工具确认；仍无法确定时询问用户。不要猜测 `userId`。

## 工作流程

### 执行前：制定计划

在开始任何工具调用前，先输出以下三项：

1. **要干什么**：明确用户意图（查询/新建/修改/删除），说明目标服务和协议类型。
2. **要怎么干**：按"查询现状 → 生成指纹规则 → 自检规则 → 确认后保存"顺序执行。如果用户给了 fingerprintId，先用 getFingerprintById 读取当前内容。
3. **干到什么程度结束**：指纹规则通过自检并保存成功，或用户明确要求只生成草案时停止。

---

1. 明确用户意图：
   - 只生成指纹草稿
   - 创建并保存新指纹
   - 修改已有指纹
   - 检查或解释已有指纹
   - 删除指纹
2. 查询现状：
   - 用户给了 `fingerprintId`：先调用 `getFingerprintById(fingerprintId)`。
   - 用户只给了产品名或协议：先调用 `getFingerprints()` 或 `getFingerprintsByProtocol(protocol)`，检查是否已有相近指纹。
3. 生成或修改指纹：
   - 只生成 `name`、`protocol`、`tags`、`info`、`rule.requests`、`rule.script`。
   - 不手工生成或写入文件；`fingerprintId` 由保存工具按 `name + "_" + version` 生成。
4. 自检规则：
   - `rule.requests` 必须是非空数组。
   - `rule.script` 必须非空。
   - HTTP 指纹使用 HTTP 响应字段。
   - TCP 指纹使用 `protocol=tcp`，脚本读取 `resp[n].raw`。
   - 脚本对空值和请求失败保持容错。
5. 保存：
   - 用户明确要求“保存”“创建”“更新”时才调用 `saveFingerprint`。
   - 用户只要求“写一个”“给草稿”“看看怎么写”时不要保存。
   - 保存前如果同名同版本可能覆盖已有指纹，先说明覆盖风险，除非上下文已经明确是更新。
6. 输出：
   - 如果已调用 Tool，报告 Tool 动作和返回的 `fingerprintId`。
   - 如果未保存，明确说明这是草稿。

## 指纹对象规范

完整指纹对象推荐结构如下。保存 Tool 的 `ruleJson` 只传其中的 `rule` 对象，不传完整对象。

```json
{
  "name": "nginx",
  "protocol": "http",
  "tags": ["web", "server"],
  "info": {
    "version": "1.0",
    "description": "Detects Nginx by HTTP server header or default page markers.",
    "vulnerabilities": [
      {
        "title": "示例漏洞标题",
        "cve": "CVE-XXXX-YYYY",
        "severity": "high",
        "description": "命中后说明可能存在的利用方式",
        "exploitSkill": "exploit-xxx-post",
        "references": ["https://..."]
      }
    ]
  },
  "rule": {
    "requests": [
      {
        "method": "GET",
        "uri": "/",
        "timeout": 3000,
        "charset": "UTF-8",
        "maxBodyBytes": 1048576
      }
    ],
    "script": "var b = String(body || '').toLowerCase(); var h = String(headers || '').toLowerCase(); status == 200 && (h.indexOf('nginx') >= 0 || b.indexOf('welcome to nginx') >= 0)"
  }
}
```

必填字段：

- `name`: 指纹名称。
- `rule.requests`: 非空数组。
- `rule.script`: 非空 JavaScript 脚本。
- `info.version` 或 `version`: 保存时用于生成 `fingerprintId`。

推荐字段：

- `protocol`: `http` 或 `tcp`。
- `tags`: 短标签，例如 `web`、`server`、`database`、`middleware`、`cms`。
- `info.description`: 说明识别依据、适用版本和误报边界。
- `info.vulnerabilities`: 命中该指纹后已知的漏洞清单，供后续漏洞利用建议消费。

## info.vulnerabilities 规范

`vulnerabilities` 是可选数组。如果对应产品/版本存在已知问题（包括无 CVE 编号的配置类、未授权访问类、自研利用类问题），按以下结构列出；没有已知问题则省略该字段。

每个数组元素字段：

| 字段 | 必填 | 类型 | 说明 |
|------|------|------|------|
| `title` | ✅ | string | 漏洞简短名称，例如 "RememberMe Cookie 反序列化" |
| `cve` | ✗ | string | CVE 编号，仅在有官方编号时填写。配置类、自研利用类问题不填 |
| `severity` | ✗ | string | 优先级，取值 `low` / `medium` / `high` / `critical` |
| `description` | ✗ | string | 详细说明利用方式或风险点 |
| `exploitSkill` | ✗ | string | 平台中对应的 skill 名称，便于 AI 直接调用；没有对应 skill 不填 |
| `references` | ✗ | string[] | 参考链接数组 |

典型场景示例：

- 有 CVE 有 skill：`{ "title": "Log4j JNDI 注入", "cve": "CVE-2021-44228", "severity": "critical", "exploitSkill": "exploit-log4j-post" }`
- 无 CVE 但有 skill：`{ "title": "Actuator heapdump 泄露", "severity": "high", "exploitSkill": "exploit-spring-actuator", "description": "/actuator/heapdump 未授权访问" }`
- 纯情报（无 CVE 无 skill）：`{ "title": "默认弱口令", "severity": "medium", "description": "admin/admin 等默认账号需要手动尝试" }`

未列入此规范的字段会被下游忽略。不要在 `vulnerabilities` 元素里附加大段证据文本或原始响应体。

## saveFingerprint 参数

调用保存 Tool 时按以下方式拆分：

- `userId`: 创建/更新人。
- `name`: 指纹名称，例如 `nginx`。
- `ruleJson`: 只传 `rule` 对象的 JSON 字符串。
- `infoJson`: 传 JSON 对象字符串，至少包含 `version`。
- `protocol`: `http` 或 `tcp`。
- `tagsJson`: 传 JSON 数组字符串，例如 `["web","server"]`。
- `version`: 可直接传版本；如果为空，Tool 会从 `infoJson.version` 读取，仍为空则使用默认版本。

示例：

```text
saveFingerprint(
  userId="admin",
  name="nginx",
  ruleJson="{\"requests\":[{\"method\":\"GET\",\"uri\":\"/\",\"timeout\":3000}],\"script\":\"var b = String(body || '').toLowerCase(); var h = String(headers || '').toLowerCase(); status == 200 && (h.indexOf('nginx') >= 0 || b.indexOf('welcome to nginx') >= 0)\"}",
  infoJson="{\"version\":\"1.0\",\"description\":\"Detects Nginx by HTTP server header or default page markers.\"}",
  protocol="http",
  tagsJson="[\"web\",\"server\"]",
  version="1.0"
)
```

## HTTP 规则

HTTP request 支持：

```json
{
  "method": "GET",
  "uri": "/",
  "path": "/",
  "headers": {
    "User-Agent": "LeoAI-Fingerprint"
  },
  "body": "",
  "timeout": 3000,
  "charset": "UTF-8",
  "maxBodyBytes": 1048576
}
```

规则：

- `method` 默认 `GET`。
- `uri` 优先于 `path`；两者都为空时默认 `/`。
- `uri` 可以是绝对 URL；否则会和 target 的 `baseUrl` 拼接。
- 只有 `POST`、`PUT`、`PATCH` 且存在 `body` 时才发送请求体。
- 默认 `timeout=3000`、`charset=UTF-8`、`maxBodyBytes=1048576`。
- HTTP 不跟随重定向；HTTPS 信任所有证书。

HTTP 响应字段：

```javascript
resp[0].status
resp[0].body
resp[0].bodyLength
resp[0].truncated
resp[0].headers
```

只有一个响应时，组件会把响应字段提升为变量：

```javascript
status
body
bodyLength
truncated
headers
```

HTTP 脚本应组合多个证据，避免只靠弱关键字：

```javascript
var b = String(body || '').toLowerCase();
var h = String(headers || '').toLowerCase();
status == 200 && (h.indexOf('nginx') >= 0 || b.indexOf('welcome to nginx') >= 0)
```

## TCP 规则

TCP 指纹要求目标使用 `protocol=tcp`。request 支持：

```json
{
  "body": "PING\r\n",
  "timeout": 3000,
  "charset": "UTF-8",
  "maxBodyBytes": 1048576
}
```

TCP 响应字段：

```javascript
resp[0].raw
resp[0].bytes
resp[0].bodyLength
resp[0].truncated
```

TCP 脚本示例：

```javascript
var r = String(resp[0].raw || '');
r.indexOf('PONG') >= 0 || r.indexOf('NOAUTH') >= 0
```

## 脚本约束

`rule.script` 由 JVM JavaScript ScriptEngine 执行。返回以下值视为命中：

- Boolean `true`
- 非 0 数字
- 字符串 `"true"`，忽略大小写

编写脚本时：

- 使用 ES5 风格语法，避免依赖现代浏览器 API。
- 多请求规则使用 `resp[0]`、`resp[1]` 明确索引。
- 对 `body`、`raw`、`headers` 做字符串匹配前先用 `String(value || '')` 兜底。
- 某个 request 失败时，对应 `resp[i]` 是错误对象，包含 `requestIndex`、`errorType`、`error`。
- 不要编写会修改目标状态的探测请求，除非用户明确要求且场景合法。

## 输出格式

最终回复保持简洁，包含：

1. `计划`：目标服务、协议类型和执行步骤
2. `目标服务和协议`
3. `识别依据`
4. `生成的关键 rule 或完整 JSON 草稿`
5. `调用了哪些 Fingerprint Tools`
6. `是否已保存`
7. `保存后的 fingerprintId`（如已保存）
8. `下一步建议`：根据操作结果给出 1~2 条具体建议，例如：
   - 指纹已保存 → "建议在目标 Puppet 上执行指纹探测，验证识别效果"
   - 只生成了草案 → "草案已就绪，确认后可调用 saveFingerprint 保存"
   - 指纹规则较弱（单一关键词匹配）→ "建议增加多个证据组合（如 header + body），降低误报率"

如果 Tool 调用失败，说明失败的 Tool、错误信息和下一步修正建议。
