---
name: shell-obfuscation
description: 理解 LeoAI Java/PHP WebShell 与 Java 内存马生成链路，根据用户本次选择的通信、伪装、兼容性和混淆参数生成独立制品。用户要求生成、变体生成、调整兼容性或排查 Shell 无法连接时使用；始终通过 ShellGeneratorTools 完成确定性生成与结果交付。
---

# Shell 生成与结构变体

Shell 是独立生成的制品，不从平台已有 Puppet 自动继承配置。只有用户明确要求“匹配/复制某个 Puppet”时，才可以查询该指定节点；不得因为平台上只有一个节点、最近操作过某个节点或当前页面选中了节点就读取它。

## 生成模型

一次生成由五部分组成：

1. 运行时与承载方式：Java WebShell、Java 内存马或 PHP WebShell。
2. 用户本次选择的通信参数：传输协议、请求伪装器、响应伪装器。
3. 兼容与结构参数：JSP/JSPX、Java/Servlet 版本、容器、注入器、Packer、输出模式和混淆策略。
4. Java WebShell 的 Core 字节码只保存在服务端 `CoreArtifactStore`，AI 仅设计不含 Payload 的 Wrapper 模板。
5. 结果交付：生成器把完整结果写入 `ShellResultStore`，工具返回 `resultId`、元数据和取回按钮。

不要手写或转述完整生成代码，不要虚构工具未返回的结果。

## 参数确认原则

1. 生成前调用 `getShellGeneratorMeta()` 获取协议、Java/Servlet、注入器、Packer 和混淆步骤等合法值。
2. 调用 `getDisguises()` 获取当前可选请求/响应伪装器；不得用 Puppet 查询代替该步骤。
3. 用户未给出的重要生成偏好必须通过 `request_user_input` 询问。Java WebShell 至少确认：
   - 传输协议：`http` 或 `httpchunk`；
   - 请求伪装器与响应伪装器；
   - 文件类型：`JSP` 或 `JSPX`；
   - 是否启用混淆。
4. Java WebShell 必须显式传 `obfuscate=true/false`；启用默认混淆时不传步骤，只有用户指定步骤时才从元数据中选择并保持顺序。
5. Java 版本和 Servlet 命名空间未指定时可使用 `auto`；已知 Jakarta 环境时显式使用 `jakarta`。
6. 类名未指定时留空，让生成器随机生成。不要为了填满参数而猜测用户意图。

## Java WebShell 工作流

1. 调用 `getShellGeneratorMeta()` 和 `getDisguises()`。
2. 对尚未明确的传输协议、请求/响应伪装、JSP/JSPX 和混淆开关调用 `request_user_input`；调用后停止本轮，等待用户回答。
3. 调用 `createJavaCoreArtifact(...)`，只接收 `coreArtifactId`、哈希和契约元数据；不得请求 Core 字节码或 Base64。
4. 可调用 `getWebShellWrapperContract(...)` 查看五个阶段占位符和无 Payload 基线模板。
5. 调用 `designWebShellWrapper(coreArtifactId, shellType, requirements)`。工具内部让 AI 设计外层并验证，成功后只返回 `wrapperTemplateId`。
6. 调用 `assembleWebShellWrapper(...)`，明确传入 `obfuscate=true/false`；平台再次验证模板后才注入真实 Core 并组装结果。
7. 检查返回元数据中的 Core 哈希、协议、类型、版本、命名空间和混淆信息。
8. 原样嵌入工具返回的 `[[shell-result:...]]` 取回按钮。

Java WebShell 的五个阶段占位符必须各出现一次、独占一行并保持顺序。真实加载、读取、调用和响应代码由平台注入，以保证 LeoCore 单次调用和同一 buffer 数据流；AI 不得展开或改写这些阶段。

## Java 内存马工作流

1. 调用 `getShellGeneratorMeta()` 和 `getDisguises()`。
2. 询问尚未明确的协议、请求/响应伪装、目标容器、注入器、Packer 和是否混淆；不要从 Puppet 推断。
3. 根据元数据检查 `packerCompatibility`、`packerAvailability` 和可用混淆步骤。
4. 只有用户明确要求结构变体且 Packer 为 `ClassLoaderJSP` 或 `DefineClassJSP` 时，才调用 `mutateJspTemplate(...)`；其他 Packer 不接受 AI 自定义模板。
5. 调用 `generateMemoryShell(...)`，检查兼容性警告并交付取回按钮。

## PHP WebShell 工作流

1. 调用 `getShellGeneratorMeta()` 和 `getDisguises()`，确认 PHP generator 的真实能力。
2. 询问请求/响应伪装和输出模式；PHP 当前仅支持 `http`，应向用户说明而不是查询 Puppet。
3. 要求用户提供 PHP PayloadCodec AES 密钥；不得使用默认值或环境变量。
4. `headerName` 与 `headerValue` 必须同时设置或同时留空；不要在回复中显示 Header 密钥。
5. 调用 `generatePhpWebShell(...)`，检查最低版本、运行要求、输出模式和警告后交付结果。

## 明确匹配 Puppet 的例外

只有用户明确给出或选择了目标 Puppet，并明确要求生成结果匹配该节点时，才允许读取该节点配置。读取结果只能服务于这次显式匹配；不能把它变成后续独立生成的默认值。排查“某个已生成 Shell 为什么连不上”时，也只有在用户把该 Shell 与具体 Puppet 建立关联后才比较两者配置。

## 失败处理

- 伪装器为空或不存在：停止生成，重新展示可用伪装器并询问用户。
- 参数不在元数据中：重新读取元数据并让用户从合法候选中选择，不重复提交同一无效值。
- PHP 请求 `httpchunk`：说明当前只支持 `http`，等待用户确认后再生成。
- 模板连续变异失败：报告失败，不得自行拼接 JSP 冒充生成成功。
- CoreArtifact 或 Wrapper 模板过期：重新执行对应生成阶段，不得要求工具返回缓存中的 Core Payload。
- 工具返回兼容性警告：区分“生成成功”和“已验证可运行”。
- 没有真实 `resultId`：不得输出取回按钮或声称生成完成。

## 回复要求

简洁报告生成类型、用户选择的协议与伪装、关键兼容/混淆参数、警告，以及工具返回的取回按钮。除非用户明确要求匹配 Puppet，否则回复中不应出现目标 Puppet。
