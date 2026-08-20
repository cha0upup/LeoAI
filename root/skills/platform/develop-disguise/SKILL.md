---
name: develop-disguise
description: 当用户希望在平台侧开发、测试、创建或更新 Disguise 时使用。该 skill 用于生成符合平台约束的 trafficEncodeBody、trafficDecodeBody、headersJson、description 和规范名称，并优先调用 testDisguise 验证 traffic 编解码是否可互逆，再创建或更新 Disguise。
---

# 开发平台侧 Disguise

当用户要求开发、修改、测试、完善或保存 Disguise 时，使用这个 skill。

## 职责边界

| 侧 | trafficEncode（出站） | trafficDecode（入站） |
|---|---|---|
| 平台侧 | PayloadCodec 序列化/压缩/加密，再经 trafficEncodeBody 伪装 | 先经 trafficDecodeBody 还原字节，再由 PayloadCodec 解密/解压/反序列化 |
| puppet 侧 | PayloadCodec 序列化/压缩/加密，再经 trafficEncodeBody 伪装 | 先经 trafficDecodeBody 还原字节，再由 PayloadCodec 解密/解压/反序列化 |

平台侧 `trafficEncodeBody` 和 `trafficDecodeBody` 只处理不透明字节，必须与 puppet 侧协议严格匹配，且在平台侧测试逻辑下必须可互逆。

---

## 一、核心约束

### 1.1 方法签名（不可更改）

```java
public byte[] encodeTraffic(byte[] payload) throws Exception
public byte[] decodeTraffic(byte[] body) throws Exception
```

### 1.2 互逆性

`decodeTraffic(encodeTraffic(bytes))` 必须返回与输入完全相等的任意字节序列。序列化、压缩和加密由固定 `PayloadCodec` 负责。

### 1.3 单次保存

- 一次请求中只允许调用 **一次** `addDisguise` 或 `updateDisguise`。
- 中间草案只能存在于上下文或通过 `testDisguise` 验证，不得保存。
- 若已保存成功，后续想调整命名/headers/description，应告知用户可在同一 `disguiseId` 上更新，不要新建第二个。
- 比较多个候选时只输出草案，用户选定后保存唯一一个。

### 1.4 测试先行

保存前 **必须** 调用 `testDisguise`。测试未通过时禁止调用 `addDisguise` / `updateDisguise`。

### 1.5 依赖限制

- 只使用 JDK 内部类，不引入第三方库。
- 语法版本尽可能低：不使用 lambda、record、text block、switch 新语法，除非用户明确要求且确认兼容。

### 1.6 安全与审计

- `description` / `remark` 必须包含授权测试、协议适配、兼容性验证或检测验证用途说明。
- 不得生成以规避安全监控、隐藏恶意通信、降低告警概率为目的的描述或实现。
- 如果用户提出此类目标，应改写为授权测试/检测验证/协议适配/规则调优，并保留可审计说明。

---

## 二、平台测试机制

平台 `testDisguise` 执行以下不透明字节互逆校验：

```java
byte[] sample = new byte[] {0, 1, 2, 3, 7, 13, 42, (byte) 0xff};
byte[] encoded = encodeTraffic(sample);
byte[] decoded = decodeTraffic(encoded);
assert java.util.Arrays.equals(sample, decoded); // 必须为 true
```

### 代码必须满足

1. `encodeTraffic` 接收完整不透明字节，不得解析或修改 PayloadCodec 内容。
2. `decodeTraffic` 必须返回原始字节，不得新增、删除或改写字段。
3. 如果协议需要 JSON/表单/Base64/URL 编码等包装，只包装字节，不负责序列化、压缩或加密。
4. `decodeTraffic` 的输入就是 `encodeTraffic` 的返回值；编码必须成对出现，不要单边处理。

### 推荐载荷处理模式

```
PayloadCodec: Map → ObjectOutputStream 序列化 → GZIP → AES → byte[]
traffic: byte[] → Base64/JSON/表单等业务包装 → byte[]
```

### 常见失败与修复

| 错误特征 | 原因 | 修复 |
|---|---|---|
| `Input byte[] should at least have 2 bytes for base64` | `decode` 在解码不合法的 Base64 输入 | 检查 `encode` 是否真的返回了 Base64 编码后的字节 |
| 字节互逆失败 | `decodeTraffic` 修改了字节或元数据包装不完整 | 确保 traffic 层只做可逆包装，不要附加载荷字段 |
| `ClassNotFoundException` | 使用了目标 JDK 不存在的类 | 用反射兼容 `java.util.Base64` 和 `sun.misc.BASE64Encoder` |

---

## 三、代码编写规范

1. `encodeTraffic` 只处理完整不透明字节，不能从载荷中取单个字段。
2. `decodeTraffic` 返回类型必须是 `byte[]`。
3. 使用全限定类名或 JDK 基础类均可。
4. 不修改方法名、参数名、返回类型或 `throws Exception`。
5. Base64 优先使用反射兼容方案（见参考骨架），同时支持 Java 8+ 和旧版 JDK。
6. 如果用户未要求复杂加密，优先使用简单、稳定、可逆、易兼容的实现。

---

## 四、协议设计规范

### 4.1 伪装原则

通信格式应贴近目标系统已有的正常业务协议风格，包括请求头、Content-Type、字段命名、编码方式和响应结构。生成时应说明其业务风格依据（JSON API / 表单提交 / 文件上传 / Ajax / 内部 RPC）。

### 4.2 Headers 规则

未指定时默认使用接近浏览器或常规 API 客户端的请求头：

```json
{
  "Accept": "application/json, text/plain, */*",
  "Content-Type": "application/json;charset=UTF-8",
  "User-Agent": "Mozilla/5.0",
  "X-Requested-With": "XMLHttpRequest"
}
```

按协议形态调整：

| 协议形态 | Content-Type |
|---|---|
| JSON 包体 | `application/json;charset=UTF-8` |
| 表单协议 | `application/x-www-form-urlencoded` |
| 二进制上传 | `application/octet-stream` |

Headers 与协议体必须一致：JSON Content-Type 时 encode 产出 JSON 风格载荷；表单 Content-Type 时载荷符合键值结构；二进制 Content-Type 时 description 必须说明合法用途。

不要主动加入明显可疑的自定义头，不要使用"绕过""免杀""规避检测"等措辞。

### 4.3 Description 要求

必须覆盖以下内容：

- 流量包装方式（Base64、URL 编码、表单或 JSON 等）
- 数据承载字段名（`data`、`payload`、`msg` 等）
- Header 风格 / Content-Type 特征
- 流量仿真目标（仿浏览器 / 仿 Ajax / 仿普通接口）
- 用途说明（授权测试 / 协议适配 / 兼容性验证）

示例：

> JSON 协议，面向授权测试环境的业务协议适配；请求和响应只将 PayloadCodec 输出的字节做 URLSafe Base64 包装并放入 payload 字段，Headers 模拟普通 Ajax JSON 请求。

### 4.4 名称生成规则

用户未指定 `disguiseName` 时自动生成：

- 只使用字母、数字、下划线或中划线
- 优先体现协议特征：`JsonBase64Envelope`、`FormAesCbcEnvelope`、`PlainXorFrame`
- 不使用 `test`、`new_disguise`、`temp123` 等临时名称

### 4.5 版本规则

- 用户未指定时默认 `1.0.0`。
- 更新现有 Disguise 时，若改动了编解码逻辑，建议递增版本号并在 remark 中说明变更。

---

## 五、工具

### 主工具：`DisguiseTools`

| 工具 | 用途 |
|---|---|
| `getDisguises` | 查看所有 Disguise |
| `getDisguiseById` | 查看指定 Disguise 详情 |
| `testDisguise(trafficEncodeBody, trafficDecodeBody)` | 验证不透明字节互逆性 |
| `addDisguise(userId, disguiseName, trafficEncodeBody, trafficDecodeBody, headersJson, version, description, remark, disguiseId)` | 创建 |
| `updateDisguise(disguiseId, disguiseName, trafficEncodeBody, trafficDecodeBody, headersJson, version, description, remark)` | 更新 |

### 辅助工具：`UserTools`

创建 Disguise 且缺少 `userId` 时，用 `getUser(userId?, userName?)` 或 `listUsers(withoutTeam?)` 补齐。

---

## 六、工作流程

### 执行前：制定计划

查询、生成、测试、保存等多个依赖阶段同时存在时调用 `createPlan` 跟踪真实进度；
简单查看或单次测试直接执行，不额外输出固定计划模板。

### 执行步骤

```
1. 明确意图 → 新建 / 修改 / 仅测试 / 优化描述
2. 查询现状 → 若有 disguiseId，先 getDisguiseById
3. 生成/调整字段 → disguiseName, trafficEncodeBody, trafficDecodeBody, headersJson, description, version, remark
4. 调用 testDisguise
   ├─ 通过 → 进入步骤 5
   └─ 失败 → 按修复顺序调整后重新测试（不保存）
5. 保存
   ├─ 新建 → addDisguise
   └─ 更新 → updateDisguise
6. 确认 success=true 后停止，不再重复保存
```

### 测试失败修复顺序

1. 确认方法签名完全正确
2. 确认 `encodeTraffic` 接收并返回不透明字节，不解析载荷
3. 确认 `decodeTraffic` 返回原始字节，无新增/删除/改写
4. Base64 异常 → 检查 traffic 编解码是否严格成对
5. JSON/表单/分隔符包装 → 只包装 PayloadCodec 输出的字节
6. 读取 `rootCauseMessage` 和 `stackTrace` 作为下一轮修复依据，不要把失败结果直接回复用户后停止

---

## 七、输出格式

```markdown
## 计划
目标协议特征 + 执行步骤

## 协议特征
编码方式、字段、Header 风格

## 生成结果
- disguiseName
- trafficEncodeBody（Java 流量伪装代码）
- trafficDecodeBody（Java 流量伪装代码）
- headersJson

## 测试结果
是否已调用 testDisguise + 结果

## 保存结果
创建/更新 + disguiseId

## 下一步建议
1~2 条具体建议
```

建议示例：

- 已保存 → "建议在目标 Puppet 配置中选择该 Disguise，并发起一次连接测试验证通信正常"
- 测试多次失败 → "建议检查 traffic 编解码是否保持任意字节互逆，PayloadCodec 不应出现在伪装代码中"
- 只生成草案 → "草案已就绪，确认后可调用 addDisguise 保存"

---

## 八、决策规则

| 场景 | 行为 |
|---|---|
| 用户只要求"写代码"/"先设计协议" | 输出完整草案，不保存 |
| 用户要求"创建"/"保存" | 先测试再保存 |
| testDisguise 失败 | 修正后重测，不保存 |
| 已成功保存一次 | 停止，不再新建第二个 |
| 用户描述不完整 | 补齐最小可运行方案，不停在抽象建议 |
| 用户未要求复杂加密 | 优先简单稳定可逆实现 |
| 用户要求非常规 Header | 提示兼容性和审计风险 |
| 用户要求规避安全监控 | 改写为授权测试/检测验证，保留审计说明 |
| 用户要求比较多个候选 | 只输出草案，选定后保存一个 |
| 误保存了中间版本 | 不继续新建；向用户说明，建议保留最终版本 |

---

## 附录：traffic-only 参考骨架

```java
public byte[] encodeTraffic(byte[] payload) {
    return java.util.Base64.getUrlEncoder().withoutPadding().encode(payload);
}

public byte[] decodeTraffic(byte[] body) {
    return java.util.Base64.getUrlDecoder().decode(body);
}
```
