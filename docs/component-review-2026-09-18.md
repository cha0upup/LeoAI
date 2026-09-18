# Component 逐项检查与优化记录（2026-09-18）

## 优化落实情况

本轮已处理 F1—F7，修改 9 个组件及对应 payload；此前 FileComponent 的工作区修改继续保留。

| 项目 | 落实结果 |
|---|---|
| F1 下载范围 | 在 long 范围内应用 1 MiB 块上限；移除纯转发，统一空文件与普通块的响应。覆盖 2 GiB、4 GiB、Long.MAX_VALUE 请求。 |
| F2 ZIP 目录 | 写入目录条目，保留空源目录和嵌套空目录；排除规则、归档自身排除及回滚继续生效。 |
| F3 参数解析 | FileEnhance、HttpRequest、ExecCommandSimple 复用各自的 UTF-8 文本解析，兼容 Number/String/byte[]；HTTP 布尔参数同步支持字节数组，超时默认值及上限保留。 |
| F4 Spring 规则 | 包含与排除规则统一转换，支持 String 和模式对象；单项失败保留其他拦截器，复用 handler mapping 获取方法。 |
| F5 解压清理 | 输出流成功关闭后才提交临时文件，关闭异常保留原文件；GZIP 构造失败显式关闭原始输入。合并源文件校验、输出目录检查和 TAR 统计响应。 |
| F6 任务预算 | 限制目标、阶段、计划及待消费结果，保留有上限的错误摘要；ACK 释放结果预算，停止后释放注册表中的目标和计划引用。 |
| F7 信息动作 | 按动作拆出处理方法，未知动作返回 400，支持 UTF-8 action；进程来源随实际返回结果的采集器确定。 |

HttpRequest 的长入口已拆成连接配置、请求体写入、响应头读取、响应体读取和响应转换。ExecCommandSimple 统一成功与超时结果公共字段。以上整理均位于组件类内部，继续保持单 class 加载约束。

NetworkProbe 的具体边界如下：

- 每个任务最多 4096 个目标，与服务端已有 NODE_BATCH_SIZE 一致；最多 8 个阶段。
- 计划估算预算为 `2 × 1024 × 1024`，待消费结果预算为 `4 × 1024 × 1024`；估算按文本字符、字节数组长度和容器固定开销累加，不是精确 JVM 堆字节数。
- 待消费 observation 最多 4096 条，保留前 128 条普通错误摘要，另返回错误总数和摘要截断标志。
- 待消费结果达到上限后，任务以 `STOPPED / CANCELLED` 结束，保留已生成结果供分页读取，并返回 `truncated / truncateReason` 及 `RESULT_LIMIT` 错误诊断。诊断放在 errors 中，确保现有编排层保存停止原因。
- ACK 扣除已消费 observation 的估算预算；错误摘要保留到任务释放。停止时删除任务注册表中的 targets/plan 引用，正在退出的 worker 使用局部引用。
- 过期任务清理仍由后续请求或显式 cleanup 触发；本轮没有增加后台回收线程。大计划会被拒绝，停止消费的任务可能因结果预算耗尽而结束。

验证记录：188 项组件测试通过（新增 34 项），27 项调用层测试通过；24 个组件的 Java 6 API、单 class、源码/payload 一致性检查通过。新增用例同时验证源码类和 payload，并覆盖关闭异常注入、损坏 GZIP 输入释放、HTTP 本地回环、任务预算与 ACK、模拟 Spring 存储差异、进程采集回退来源。实际 Java 6 JVM 和真实框架版本部署仍未验证。

本轮完成了报告的缺陷修复和主要结构整理。下方 24 类表格保留为优化前的审查快照；其中容器类去重等低优先级建议尚未实施，表中行数也是审查时的数据。

## 优化前的审查记录

检查了 `org.leo.core.component` 下全部 24 个 Java Component，共 11,775 行。结论是：可以继续做类内简化，但优先处理已复现的边界问题；容器版本回退、归档回滚和并发清理占据了不少代码，不能仅按行数删除。

最初审查基于 DatabaseComponent 已提交、FileComponent 已重构但尚未提交的工作区。以下发现描述修复前的行为。

## 已复现的问题

以下复现分别运行于 Maven 编译的源码类和现有 `.payload`，两者结果一致。文件操作只使用临时目录；2 GiB 文件使用稀疏文件，复现后已删除。HTTP、命令执行的检查只调用参数解析方法；Spring 使用模拟对象。

| 编号 | 位置 | 触发条件和实际结果 | 建议 |
|---|---|---|---|
| F1 | [FileDownloadComponent:112](/Users/lichao/IdeaProjects/LeoAI/javacore/src/main/java/org/leo/core/component/FileDownloadComponent.java:112) | 文件剩余长度和请求 `size` 均为 `2147483648`，在应用 1 MiB 上限前转成 `int`，抛出 `NegativeArraySizeException: -2147483648`。不是所有大文件下载都会触发，必须请求范围也足够大。 | 先在 `long` 范围内应用块大小上限，再转 `int`；补充超过 `Integer.MAX_VALUE` 的请求边界测试。 |
| F2 | [CompressComponent:171](/Users/lichao/IdeaProjects/LeoAI/javacore/src/main/java/org/leo/core/component/CompressComponent.java:171) | 源目录包含 `empty/` 和 `one.txt`，ZIP 中只有 `source/one.txt`。递归只为文件创建条目，空目录丢失，仍返回成功。 | 为目录生成 ZIP 条目，验证空源目录和嵌套空目录；保持原有排除规则及目标文件回滚。 |
| F3 | [FileEnhanceComponent:637](/Users/lichao/IdeaProjects/LeoAI/javacore/src/main/java/org/leo/core/component/FileEnhanceComponent.java:637)、[HttpRequestComponent:414](/Users/lichao/IdeaProjects/LeoAI/javacore/src/main/java/org/leo/core/component/HttpRequestComponent.java:414)、[ExecCommandSimpleComponent:237](/Users/lichao/IdeaProjects/LeoAI/javacore/src/main/java/org/leo/core/component/ExecCommandSimpleComponent.java:237) | UTF-8 `byte[]` 数字没有先解码：FileEnhance 的 `action="1"` 返回 `400 / 未知 action: -1`；HTTP 数字 `1234` 回落到传入的默认值 `5000`；ExecCommandSimple 的 1 秒超时变成默认 30 秒。 | 按开发约定处理 `Number`、`String`、UTF-8 `byte[]`，在各类内部复用文本解析；补充三种输入形式的一致性测试。 |
| F4 | [SpringFrameworkManageComponent:163](/Users/lichao/IdeaProjects/LeoAI/javacore/src/main/java/org/leo/core/component/SpringFrameworkManageComponent.java:163) | 模拟 `MappedInterceptor.excludePatterns` 为非空 `String[]`，枚举调用字符串的 `getPatternString()`，抛出 `NoSuchMethodException`；外层吞异常后返回空的拦截器列表。 | 排除规则与包含规则共用已有 `patternStrings()`；覆盖字符串和模式对象两种存储形态，并验证单项失败不会抹掉整批结果。 |

F3 的附加观察：HTTP 布尔解析对 UTF-8 `byte[]("true")` 返回 `false`。数字形式明确违反当前组件开发约定；布尔值是否也应兼容字节数组，应结合调用协议统一定义，避免默认值被静默覆盖。

F4 验证的是具体对象存储形态，不代表已经对所有 Spring 版本进行了部署验证。

## 静态检查发现，尚未做故障或压力复现

| 编号 | 位置 | 发现与影响 | 后续验证 |
|---|---|---|---|
| F5 | [DecompressComponent:168](/Users/lichao/IdeaProjects/LeoAI/javacore/src/main/java/org/leo/core/component/DecompressComponent.java:168)、[DecompressComponent:270](/Users/lichao/IdeaProjects/LeoAI/javacore/src/main/java/org/leo/core/component/DecompressComponent.java:270) | ZIP、GZIP、TAR 在提交临时文件前调用会吞异常的 `closeStream(fos)`；关闭失败仍可能提交并报告成功。GZIP 还直接嵌套创建 `FileInputStream`，若 `GZIPInputStream` 构造失败，底层流没有显式关闭路径。 | 注入关闭异常验证原目标保留；损坏 GZIP 头部验证底层流释放。成功路径使用可传播异常的关闭，`finally` 才安静清理。 |
| F6 | [NetworkProbeComponent:149](/Users/lichao/IdeaProjects/LeoAI/javacore/src/main/java/org/leo/core/component/NetworkProbeComponent.java:149)、[NetworkProbeComponent:589](/Users/lichao/IdeaProjects/LeoAI/javacore/src/main/java/org/leo/core/component/NetworkProbeComponent.java:589)、[NetworkProbeComponent:673](/Users/lichao/IdeaProjects/LeoAI/javacore/src/main/java/org/leo/core/component/NetworkProbeComponent.java:673) | 任务数、线程数和单条证据有限制，但目标列表整体复制；`observations`、`errors` 没有任务级总量上限。ACK 仅删除 observations，errors 持续保留。大任务、失败结果多或客户端停止消费时，仍可能积累大量内存。 | 用合成任务验证目标数和结果保留预算、错误列表释放、客户端不再消费时的资源回收；本次未运行大规模探测。 |
| F7 | [BasicInfoComponent:72](/Users/lichao/IdeaProjects/LeoAI/javacore/src/main/java/org/leo/core/component/BasicInfoComponent.java:72)、[BasicInfoComponent:483](/Users/lichao/IdeaProjects/LeoAI/javacore/src/main/java/org/leo/core/component/BasicInfoComponent.java:483) | 未知 action 会进入默认完整信息收集，字节数组 action 也会走该路径；进程 `source` 按类是否存在推断，与实际发生的回退没有关联，可能标错数据来源。 | 区分缺省 action 和非法 action；将实际成功的数据来源与结果一同记录。验证回退分支和返回结构。 |

## 24 个组件逐项结论

“保留”表示本轮没有找到值得立即重构的收益，不表示已经证明所有环境下都没有缺陷。下表测试覆盖指当前已存在的验证，缺口是后续修改时应补充的重点。

| # | Component | 结论及可简化位置 | 必须保留的行为／主要测试缺口 |
|---|---|---|---|
| 1 | [BasicInfoComponent](/Users/lichao/IdeaProjects/LeoAI/javacore/src/main/java/org/leo/core/component/BasicInfoComponent.java)（953 行） | **建议后续整理。** 按 action 分出处理方法，减少响应公共字段重复；优先澄清 F7 的动作与来源语义。 | 保留 ProcessHandle、可选反射和 `/proc` 回退。已有系统快照测试；缺少采集失败后回退、非法 action 的验证。 |
| 2 | [CompressComponent](/Users/lichao/IdeaProjects/LeoAI/javacore/src/main/java/org/leo/core/component/CompressComponent.java)（333 行） | **优先修复 F2。** 之后可减少仅用于计算 canonical 路径的实例状态，整理路径校验。 | 保留临时归档、成功关闭 ZIP 后再提交、权限保留、备份恢复、自身输出排除和目录去重。已有失败保留旧归档及自包含排除测试；缺少空目录。 |
| 3 | [CredentialHarvestComponent](/Users/lichao/IdeaProjects/LeoAI/javacore/src/main/java/org/leo/core/component/CredentialHarvestComponent.java)（683 行） | **本轮保留主体。** 维护重点是输入校验、错误响应、资源关闭和结果最小化，不能因多个来源结构类似而合并异常边界。 | 保留分来源失败隔离；JNDI 枚举对象的关闭路径值得补查。现有测试主要是合成属性、非法操作及字节码转换，不能据此推断真实环境行为。 |
| 4 | [DatabaseComponent](/Users/lichao/IdeaProjects/LeoAI/javacore/src/main/java/org/leo/core/component/DatabaseComponent.java)（778 行） | **保留本轮前已完成的重构。** 执行、结果读取、响应及资源清理已分离，继续压缩收益较低。 | 保留数据库差异、分页和输出上限、连接关闭、驱动加载边界。已有执行与专项边界测试；真实 JDBC 驱动组合仍需环境验证。 |
| 5 | [DecompressComponent](/Users/lichao/IdeaProjects/LeoAI/javacore/src/main/java/org/leo/core/component/DecompressComponent.java)（613 行） | **值得整理，先处理 F5。** ZIP/GZIP 可复用已有源文件、输出目录校验；TAR/TAR.GZ 可统一统计响应。 | 保留路径穿越检查、条目数/单文件/总展开量限制、临时文件和原目标恢复。已有格式、混合入参、损坏 GZIP、大小限制测试；缺少关闭失败注入。 |
| 6 | [ExecCommandComponent](/Users/lichao/IdeaProjects/LeoAI/javacore/src/main/java/org/leo/core/component/ExecCommandComponent.java)（847 行） | **保留主体，仅小范围整理。** 少量元数据拼装可统一；当前复杂度主要来自进程、读取线程和输出队列生命周期。 | 保留初始化约束、输出序号、截断、空闲清理及锁顺序。已有较多生命周期测试；不建议引入新的通用状态框架来追求减少行数。 |
| 7 | [ExecCommandSimpleComponent](/Users/lichao/IdeaProjects/LeoAI/javacore/src/main/java/org/leo/core/component/ExecCommandSimpleComponent.java)（277 行） | **优先修复 F3。** 成功/超时分支的结果公共字段可以共用方法。 | 保留 worker 的 volatile 可见性、超时上限和达到输出上限后继续排空的行为。已有执行、超时和截断测试；补字节数组超时解析。 |
| 8 | [ExecScriptComponent](/Users/lichao/IdeaProjects/LeoAI/javacore/src/main/java/org/leo/core/component/ExecScriptComponent.java)（101 行） | **不建议继续抽象。** 代码已短，修正 Java 版本注释即可；脚本执行时间与输出资源约束应作为单独问题评估。 | 引擎不可用、脚本异常和结果格式需稳定。已有执行兼容性测试；现代 JDK 可能没有默认脚本引擎，不能把“无引擎分支通过”视为实际脚本执行覆盖。 |
| 9 | [FileComponent](/Users/lichao/IdeaProjects/LeoAI/javacore/src/main/java/org/leo/core/component/FileComponent.java)（706 行） | **保留刚完成的重构。** 目标准备、父目录处理、覆盖备份、内容写入和响应已经归并。 | 保留上传、复制、覆盖失败时的原文件保护及权限策略。已有基本、文件安全和本次前新增的边界测试。 |
| 10 | [FileDownloadComponent](/Users/lichao/IdeaProjects/LeoAI/javacore/src/main/java/org/leo/core/component/FileDownloadComponent.java)（186 行） | **优先修复 F1。** 可以去掉 `invoke → fileDownload` 的纯转发，统一块响应字段。 | 保留 1 MiB 限制、分块读满逻辑、空文件特定响应以及 416 范围错误。现有 3 项测试没有覆盖 long 转 int 溢出。 |
| 11 | [FileEnhanceComponent](/Users/lichao/IdeaProjects/LeoAI/javacore/src/main/java/org/leo/core/component/FileEnhanceComponent.java)（651 行） | **优先修复 F3，再整理。** 统一参数解析和路径校验；类注释应与实际 5 个 action 对齐。 | 保留 touch/pack 的符号链接与递归边界、权限回退和失败归档清理。grep 的符号链接策略与另外两个操作不同，应明确约定并补测试。 |
| 12 | [FileUploadComponent](/Users/lichao/IdeaProjects/LeoAI/javacore/src/main/java/org/leo/core/component/FileUploadComponent.java)（163 行） | **可做小幅简化，优先级低。** 去掉纯转发层、整理响应与参数校验即可。 | 保留分块大小、偏移与溢出校验，以及按 canonical 路径分组的写锁。已有混合入参和越界测试；并发上传同一路径值得补测。 |
| 13 | [GenericServletContainerManageComponent](/Users/lichao/IdeaProjects/LeoAI/javacore/src/main/java/org/leo/core/component/GenericServletContainerManageComponent.java)（414 行） | **可做小幅简化。** 去掉监听器收集辅助方法中没有使用的参数，整理重复的反射包装和固定元数据。 | 保留只读约束、递归深度和对象身份去重。已有标准注册表、非法操作和集合转换测试；没有真实多容器版本矩阵。 |
| 14 | [HttpRequestComponent](/Users/lichao/IdeaProjects/LeoAI/javacore/src/main/java/org/leo/core/component/HttpRequestComponent.java)（442 行） | **优先修复 F3，再拆分长入口。** 按校验、配置连接、写请求、读响应分成类内私有方法。 | 保留协议/方法白名单、请求响应大小、连接/读取超时、错误流及 disconnect。已有非法协议/方法和超大请求测试；补参数等价性与连接异常路径。 |
| 15 | [JavaWebFrameworkManageComponent](/Users/lichao/IdeaProjects/LeoAI/javacore/src/main/java/org/leo/core/component/JavaWebFrameworkManageComponent.java)（403 行） | **适度整理。** Struts 两类拦截器视图的公共字段、JSF 生命周期枚举的重复代码可在类内归并。 | 保留框架与 javax/jakarta 分支。当前操作结果测试不等于真实运行时缓存与注册表的一致性验证；`verified` 字段应与实际验证范围一致。 |
| 16 | [NetworkProbeComponent](/Users/lichao/IdeaProjects/LeoAI/javacore/src/main/java/org/leo/core/component/NetworkProbeComponent.java)（897 行） | **先补 F6 的资源预算，再考虑整理。** 任务查找、公共快照和重复响应可以微调，整体重写收益有限。 | 保留线程/队列限制、任务锁、停止检查、游标与 ACK。已有探测和生命周期测试；缺少大量目标、错误累积及消费中断下的内存边界测试。 |
| 17 | [PluginComponent](/Users/lichao/IdeaProjects/LeoAI/javacore/src/main/java/org/leo/core/component/PluginComponent.java)（212 行） | **保留主体。** 仅响应拼装有少量整理空间。 | 保留类文件大小、常量池校验、路径限制和临时文件 finally 清理。不要为了简短改回依赖高版本 JDK 受限反射的加载方式。已有载荷初始化及执行测试。 |
| 18 | [ProxyForwardComponent](/Users/lichao/IdeaProjects/LeoAI/javacore/src/main/java/org/leo/core/component/ProxyForwardComponent.java)（289 行） | **可做局部清理。** 整理重复错误响应和多余异常声明，现有动作分派已经清晰。 | 保留容量检查、连接身份校验和关闭同步。空闲清理由请求触发，应明确这一生命周期限制。已有本地回环和连接清理测试。 |
| 19 | [ResourceComponent](/Users/lichao/IdeaProjects/LeoAI/javacore/src/main/java/org/leo/core/component/ResourceComponent.java)（224 行） | **保留主体。** 同一次请求中对相同 ClassLoader 的重复尝试可选去重。 | 保留多类加载器回退、16 MiB 限制、零字节读取防空转及超限状态重置。已有资源读取、字节路径和状态重置测试。 |
| 20 | [ReverseTunnelComponent](/Users/lichao/IdeaProjects/LeoAI/javacore/src/main/java/org/leo/core/component/ReverseTunnelComponent.java)（407 行） | **保留主体、局部整理。** 可统一连接查找和错误响应；多个映射的生命周期需要一起考虑。 | 保留监听器关闭时的子连接清理、accept 容量限制和竞态身份校验。空闲连接回收同样依赖后续请求。已有本地回环生命周期测试。 |
| 21 | [ScreenComponent](/Users/lichao/IdeaProjects/LeoAI/javacore/src/main/java/org/leo/core/component/ScreenComponent.java)（286 行） | **可小幅简化。** 图形能力检查、macOS 检查和捕获分别创建 Robot；格式检查也重复，可整理为单次准备流程。 | 保留 headless 响应、透明图像转 JPEG 和图像资源释放。现有测试使用合成图像；没有验证真实截图权限、多显示器或 NaN 质量参数。 |
| 22 | [SpringFrameworkManageComponent](/Users/lichao/IdeaProjects/LeoAI/javacore/src/main/java/org/leo/core/component/SpringFrameworkManageComponent.java)（443 行） | **优先修复 F4。** 复用规则转换和 handler mapping 查找；减少结果拼装重复。 | 保留方法/字段两种注册表访问，以及 Spring、Servlet 命名空间回退。已有转换、协议结构测试；补不同规则存储形态与部分枚举失败测试。 |
| 23 | [TomcatContainerManageComponent](/Users/lichao/IdeaProjects/LeoAI/javacore/src/main/java/org/leo/core/component/TomcatContainerManageComponent.java)（808 行） | **建议后续整理。** 一次 inspectRuntime 调用中，contexts 和 features 分别重新发现 context，可以共用本次发现结果；去掉 `getContexts → getContext` 纯转发。 | 保留多版本字段、继承字段查找、集合/数组差异和部署身份约束。已有模拟对象测试；真实 Tomcat 版本、JDK 模块访问限制需单独验证。 |
| 24 | [WeblogicContainerManageComponent](/Users/lichao/IdeaProjects/LeoAI/javacore/src/main/java/org/leo/core/component/WeblogicContainerManageComponent.java)（659 行） | **建议后续整理。** 两条运行时枚举路径中的 `componentRuntimes / children` 遍历重复，监听器字段清单也重复；可整理为类内 helper 和单一字段定义。 | 保留 MBean/线程回退及字段位置差异。统一检查失败返回 `null` 与空集合的语义、操作结果的验证范围。已有模拟对象测试；缺真实 WebLogic 版本验证。 |

## 建议的实施顺序

1. 修复 F1、F2：文件下载范围和 ZIP 空目录。先补失败用例，再改源码并同步 payload。
2. 修复 F3：三个组件的数字参数解析；把输入约定落实为等价性测试。
3. 修复 F4：Spring 规则转换，保留现有版本回退。
4. 验证并处理 F5、F6、F7：文件关闭与释放、任务内存预算、动作及来源语义。
5. 最后做结构整理：优先 Decompress、HttpRequest、BasicInfo，再处理容器类的重复逻辑。Database、File 和已经很短的类暂时无需再次重构。

所有结构整理均应遵守 [COMPONENT_GUIDE.md](/Users/lichao/IdeaProjects/LeoAI/javacore/src/main/java/org/leo/core/component/COMPONENT_GUIDE.md)：每个组件必须独立生成一个 Java 6 class，运行依赖限于 JDK。相似代码可以在各类内部提取私有方法；跨 Component 的公共基类、项目工具类和内部类不适合当前 payload 加载方式。已有反射版本回退、资源上限、finally 清理和备份恢复不应作为冗余直接删除。

## 审查阶段的验证与覆盖边界

使用 JDK 17 执行全部组件测试：

```sh
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-17.jdk/Contents/Home \
./mvnw -pl javacore -am '-Dtest=org.leo.core.component.*Test' \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

结果：**154 项测试通过，0 失败、0 错误、0 跳过**。

在 `javacore` 目录执行：

```sh
bash compile-components.sh --check
```

结果：**24 个组件全部通过 Java 6 字节码/API、单 class 和源码/payload 一致性检查**。检查过程中只有已有的 unchecked 编译提示。

额外的临时复现确认了 F1—F4，运行对象同时覆盖源码类和原始 payload。它没有替代 Javassist 重命名后的行为验证，相关转换覆盖来自已有测试。F5—F7 是代码路径检查结论，本次没有进行文件关闭故障注入、内存压力测试或实际回退环境部署。

本次通过不等于所有真实环境通过：没有实际运行 Java 6 JVM，没有真实 Tomcat/WebLogic/Spring 多版本部署，没有实际截图权限验证。已有测试集中在当前 JDK、模拟框架对象、本地回环和临时文件边界。
