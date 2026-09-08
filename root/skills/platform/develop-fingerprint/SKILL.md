---
name: develop-fingerprint
description: 当用户希望在平台侧编写、生成、完善、检查、保存、更新或删除指纹规则时使用。指纹由 NetworkProbe 采集证据，服务侧使用声明式 rule.match 判定。
---

# 开发平台侧指纹

所有指纹管理动作必须通过 `FingerprintTools` 完成，不直接读写 VFS 文件。

可用工具：`listFingerprints(protocol?)`、`getFingerprintById(fingerprintId)`、`saveFingerprint(...)`、`deleteFingerprint(...)`。

## 工作流程

1. 查询同协议或同 ID 的现有指纹。
2. 设计无副作用的 `rule.requests`。
3. 使用声明式 `rule.match` 表达命中条件，不生成 JavaScript。
4. 保存前确认 `userId`、名称、版本和覆盖风险。
5. 保存后报告 `fingerprintId`，并建议在授权目标上验证误报与漏报。

## 规则结构

```json
{
  "name": "nginx",
  "protocol": "http",
  "tags": ["web", "server"],
  "info": {"version": "1.0"},
  "rule": {
    "requests": [
      {"method": "GET", "path": "/", "timeout": 3000, "maxBodyBytes": 8192}
    ],
    "match": {
      "any": [
        {"field": "headers", "operator": "contains", "value": "nginx"},
        {"field": "body", "operator": "contains", "value": "welcome to nginx"}
      ]
    }
  }
}
```

`rule.requests` 必须为非空数组。HTTP 请求支持 `method`、`path`/`uri`、`headers`、`body`、`timeout`、`charset`、`maxBodyBytes`；TCP 请求支持 `body`、`timeout`、`maxBodyBytes`。证据最多读取 8192 字节。

`rule.match` 支持：

- 组合：`all`、`any`、`not`
- 字段：`status`、`body`、`headers`、`raw`、`bodyLength`、`truncated`、`error`、`errorCode`
- 操作：`contains`、`notcontains`、`equals`、`in`、`exists`、`startswith`、`endswith`
- 多请求规则用 `request` 指定从 0 开始的请求序号
- 字符串默认忽略大小写；需要区分大小写时设置 `"ignoreCase": false`

TCP 示例：

```json
{
  "requests": [{"body": "PING\r\n", "timeout": 3000}],
  "match": {
    "any": [
      {"field": "raw", "operator": "contains", "value": "+PONG"},
      {"field": "raw", "operator": "contains", "value": "-NOAUTH"}
    ]
  }
}
```

优先组合两个以上稳定证据，避免只靠宽泛关键词。探测请求不得修改目标状态，除非用户明确授权且任务确有必要。
