# 文件管理能力与可靠性约定

文件能力由节点 `FileComponent` 的 `profile` 响应声明，前端文件管理器将
`capabilities` 提供给操作菜单和解压弹窗。未声明的增强操作默认隐藏，前后端应同步更新。

| 能力字段 | Java 节点 | PHP 节点 |
| --- | --- | --- |
| `grep`、`touch`、`pack`、`rename` | 支持 | 不支持 |
| `chmod` | 非 Windows 节点声明支持 | 不支持 |
| `copyDirectory` | 不支持 | 不支持 |
| `compressionFormats` | `zip` | 有 ZipArchive 时为 `zip`，否则为空 |
| `extractionFormats` | `zip`、`gzip`、`tar`、`tar.gz` | 有 ZipArchive 时为 `zip`，否则为空 |
| `maxUploadChunkBytes` | 1048576 | 1048576 |

能力声明表示已实现该操作，实际执行仍可能受节点账户权限或运行环境限制。
Java 无系统 chmod 时使用反射调用 POSIX API 精确设置权限；旧 JVM 或非 POSIX
文件系统无法精确表示权限时返回失败，不再将仅所有者权限扩展给其他用户。

## 传输与预览

- 下载进度达到 100% 只表示字节传输完成。只有服务端返回 `COMPLETED` 才能标记成功；
  后续校验失败、暂停和取消状态必须保留。
- 上传先写同目录临时文件并校验 MD5，再进入 `COMMITTING` 执行最终移动。
  在提交之前可以取消；进入提交后拒绝暂停和取消，等待远端操作结果。
- 浏览器分片上传在清理临时文件前等待所有在途写入结束。服务端暂停后必须等旧执行流程
  退出，才能恢复，避免多个执行流程同时操作同一临时文件。
- 浏览器计算 Blob 的 MD5 时每次读取 1 MiB，不再读取整个文件到内存。
- 保存成功只推进请求发起时的编辑内容基线，不覆盖等待响应期间的继续输入。
  换行和 BOM 转换应用于保存内容，重复保存保持选择的格式。
- 打开、刷新和编码切换共用预览请求生命周期，旧响应和旧确认结果不能覆盖新预览，
  刷新状态持续到内容与编辑器更新结束。普通读取和编码重载共用 `loadFile` 入口。
- 编码切换取消或失败时恢复实际显示编码；保存期间不允许刷新或重载编码。
- 大文件预览保留流式解码状态，中文和 emoji 跨分片时不会被分别解码成替代字符。
  切换文件或关闭预览会重置该状态。

## 节点文件操作

- Java 与 PHP 的 ZIP 压缩先写同目录临时归档，完成关闭后再替换目标；生成失败保留原归档。
  替换失败尝试恢复备份；恢复也失败时错误信息包含备份路径。该过程不保证断电原子性。
- Java POSIX 节点替换归档保留原来的 rwx 权限。Java 6 无精确权限 API 时退回仅当前用户访问，
  因此旧 JVM 上原来的共享访问权限可能收窄。
- Java 递归修改时间戳跳过符号链接，避免递归访问选中目录之外的目标。
- PHP 压缩实现排除表达式、空目录和空归档处理，并检查添加条目及关闭归档的返回值。
- PHP 解压只接受 ZIP，预先校验全部条目路径，拒绝目录穿越、已有符号链接和重复目标。
  最多 10000 个条目、单文件展开 256 MiB、累计展开 1 GiB；写入时也检查实际展开量。
  每个文件先写临时文件，验证大小和 CRC 后替换。
- PHP 解压按文件提交，不是整个目录事务。后续条目失败时，先前已提交的文件会保留。
  路径检查不等同于对并发文件系统修改的完全隔离。
- PHP 分片上传拒绝负偏移、整数溢出和超过 1 MiB 的请求；持有排他锁并循环处理短写。

## 验证入口

节点与服务回归测试包括 `FileSafetyTest`（源码和实际 payload）、`PhpFileComponentTest`
（真实 PHP CLI）、`UploadEngineServiceTest`（校验中取消、提交阶段控制、暂停后恢复）。
PHP CLI 测试需要 zip 扩展，缺失时会跳过。

前端测试位于 `src/components/PuppetConsole/File`、`TaskManager/taskManagerModel.test.js`
和 `src/composables/useFile*.test.js`、`useLargeFile.test.js`。

Java 组件修改后必须同步更新对应 `src/main/resources/component/*.payload`。
`javacore/compile-components.sh --check` 验证 Java 6 字节码、API 和源码/payload 一致性。
本批运行验证使用 JDK 17 和 PHP 8.3；Java 6 与 PHP 5.6 未做真实运行时验证。

后续可独立推进目录分页、编辑版本冲突检测以及真实远程节点的端到端回归。
