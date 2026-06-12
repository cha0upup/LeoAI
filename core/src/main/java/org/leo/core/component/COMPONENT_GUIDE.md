# Component 开发检查清单

Component 类会被编译为 `.payload`，通过 `CloneWithJavassist` 重命名后发送到 puppet 端执行。
puppet 端 JVM 版本不可控（最低 Java 6），且只加载单个 `.class` 文件，因此有严格的编写约束。

---

## 语法约束（-source 1.6 -target 1.6）

- [ ] **不使用 lambda 表达式**（Java 8）：`() -> {}` 禁用
- [ ] **不使用匿名内部类**：会生成独立 `$1.class`，CloneWithJavassist 不会发送到 puppet 端，运行时 `ClassNotFoundException`
- [ ] **不使用 diamond operator**（Java 7）：写 `new ArrayList<String>()` 或 raw `new ArrayList()`，不写 `new ArrayList<>()`
- [ ] **不使用 try-with-resources**（Java 7）：用 `try/finally` 手动关闭资源
- [ ] **不使用 multi-catch**（Java 7）：`catch (A | B e)` 拆成多个 catch 块
- [ ] **不使用 String switch**（Java 7）：用 `if/else if` 链替代
- [ ] **不使用 `(int) Object` 直接拆箱**（Java 7）：改为 `((Number) obj).intValue()`
- [ ] **不使用二进制字面量和数字下划线**（Java 7）：`0b1010`、`1_000` 禁用

## 依赖约束

- [ ] **只用 JDK 标准库**：`java.lang`、`java.util`、`java.io`、`java.net`、`java.security`、`javax.crypto` 等
- [ ] **不引用项目内其他包**：component 包被独立编译，无法引用 `org.leo.core.util` 等
- [ ] **不引用第三方库**：puppet 端 classpath 无 fastjson / okhttp 等
- [ ] **谨慎使用高版本 JDK API**：`AutoCloseable`（Java 7）、`ConcurrentHashMap.newKeySet()`（Java 8）等在低版本 JVM 不存在

## 结构约束

- [ ] **入口方法**：实现 `Runnable`，框架通过 `new Thread(component).start()` 调用 `run()`。`run()` 内通过 `Thread.currentThread().getContextClassLoader()` 拿到框架注入的 `InvocationHandler`，再用 `h.invoke(null, null, null)` 获取 `params`，用 `h.invoke(null, null, new Object[]{results})` 写回结果
- [ ] **字段声明**：`private HashMap params;` 和 `private HashMap results;`，使用 raw type（Javassist clone 会处理字段访问，不需要 public）
- [ ] **不产生额外 .class 文件**：不用匿名类、内部类、局部类。如需多线程回调，使用 `static ConcurrentHashMap<Thread, Object[]>` 传参模式（见下方"多线程回调模式"）
- [ ] **run() 防御**：多线程模式下 `run()` 入口先检查 THREAD_PARAMS，若命中则走 worker 分支并 return，避免重复执行主逻辑

## 类型安全

- [ ] **整数取值**：`((Number) params.get("key")).intValue()`，兼容 Integer/Long/Short/String 反序列化
- [ ] **字符串取值**：`(String) params.get("key")` 或 `params.get("key").toString()`
- [ ] **布尔取值**：`Boolean.TRUE.equals(params.get("key"))`
- [ ] **byte[] 取值**：`(byte[]) params.get("key")`，注意 base64 编码场景需手动解码
- [ ] **空值检查**：所有从 params 取值的关键参数做 null 检查，抛出明确错误信息

## 静态字段

- [ ] **明确生命周期**：静态字段在 puppet 端同一 ClassLoader 下跨调用持续存在，确认这是预期行为
- [ ] **资源清理**：持有 Socket/ServerSocket/Thread 等资源的静态字段，提供 cleanup 操作或在重新初始化时关闭上一轮残留
- [ ] **线程安全**：多线程访问的静态字段使用 `ConcurrentHashMap`/`ConcurrentLinkedQueue`/`volatile`

## 错误处理

- [ ] **统一返回格式**：成功 `results.put("code", 200)`，失败 `results.put("code", 500)` + `results.put("msg", "...")`
- [ ] **run() 中 catch 兜底**：确保任何异常都被捕获并写入 results，不要让异常逃逸导致 puppet 端崩溃
- [ ] **敏感信息**：生产环境考虑不返回完整堆栈，只返回错误码 + 简短描述

## 编译与验证

- [ ] **编译命令**：`javac -source 1.6 -target 1.6 -Xlint:-options -d out *.java`
- [ ] **确认字节码版本**：`javap -v XxxComponent.class | grep "major version"` → 应为 50（Java 6）
- [ ] **确认无额外 class**：编译输出目录中只有 `XxxComponent.class`，无 `XxxComponent$1.class` 等
- [ ] **放入 resources**：将 `.class` 重命名为 `.payload` 拷贝到 `core/src/main/resources/component/`
- [ ] **或使用脚本**：`cd core && bash compile-components.sh`

## 框架入口标准模板

所有 Component 必须实现 `Runnable`，`run()` 是唯一入口：

```java
public class XxxComponent implements Runnable {

    private HashMap params;
    private HashMap results;

    public void run() {
        java.lang.reflect.InvocationHandler h =
                (java.lang.reflect.InvocationHandler) Thread.currentThread().getContextClassLoader();
        try {
            params = (java.util.HashMap) h.invoke(null, null, null);
            results = new java.util.HashMap();
            invoke();
        } catch (Throwable t) {
            if (results == null) results = new java.util.HashMap();
            results.put("code", 500);
            results.put("msg", t.getMessage() != null ? t.getMessage() : t.getClass().getName());
        }
        if (results != null) {
            try { h.invoke(null, null, new Object[]{results}); } catch (Throwable ignored) {}
        }
    }

    private void invoke() throws Exception {
        // 业务逻辑
    }
}
```

## 多线程回调模式（替代 lambda / 匿名类）

当 Component 需要为每个连接启动独立线程，或需要把阻塞 I/O（进程读取、网络收发）移出框架 HTTP 线程时，使用此模式。

```java
// 1. 组件自身实现 Runnable
public class XxxComponent implements Runnable {

    private HashMap params;
    private HashMap results;

    // 2. 静态 Map 传递线程参数，key 为 Thread 对象本身
    private static final java.util.Map THREAD_PARAMS =
            new java.util.concurrent.ConcurrentHashMap();

    public void run() {
        // 3. run() 入口先检查是否为工作线程
        Object[] target = (Object[]) THREAD_PARAMS.remove(Thread.currentThread());
        if (target != null) {
            doWork((java.net.Socket) target[0], (String) target[1]);
            return;
        }

        // 4. 否则为框架调用，执行主逻辑
        java.lang.reflect.InvocationHandler h =
                (java.lang.reflect.InvocationHandler) Thread.currentThread().getContextClassLoader();
        try {
            params = (java.util.HashMap) h.invoke(null, null, null);
            results = new java.util.HashMap();
            invoke();
        } catch (Throwable t) {
            if (results == null) results = new java.util.HashMap();
            results.put("code", 500);
            results.put("msg", t.getMessage() != null ? t.getMessage() : t.getClass().getName());
        }
        if (results != null) {
            try { h.invoke(null, null, new Object[]{results}); } catch (Throwable ignored) {}
        }
    }

    private void invoke() throws Exception {
        // 5. 主循环（如 accept loop）
        mainLoop();
    }

    private void mainLoop() {
        // ...
        // 6. 启动工作线程：先 put 参数，再 start
        Thread t = new Thread(this, "XxxComponent-Worker");
        t.setDaemon(true);
        THREAD_PARAMS.put(t, new Object[]{socket, connId});
        t.start();
    }

    private void doWork(java.net.Socket socket, String connId) {
        // 阻塞 I/O 在此执行，不影响框架 HTTP 线程
    }
}
```
