package org.leo.web.service.ssh;

import net.schmizz.sshj.connection.channel.direct.Session;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/** One PTY channel. Bounded output provides SSH window backpressure, not unbounded history. */
final class SshTerminalSession {
    private final Session.Shell shell;
    private final String instanceId;
    private final Runnable onEnd;
    private final byte[] buffer = new byte[1024 * 1024];
    private int head, size, readers = 2;
    private long sequence;
    private boolean ended;

    SshTerminalSession(Session.Shell shell, String instanceId, Runnable onEnd) {
        this.shell = shell;
        this.instanceId = instanceId;
        this.onEnd = onEnd;
    }
    void start() {
        pump(shell.getInputStream());
        pump(shell.getErrorStream());
    }
    private void pump(InputStream input) {
        Thread reader = new Thread(() -> {
            try {
                byte[] bytes = new byte[8192];
                int count;
                while ((count = input.read(bytes)) >= 0) {
                    int offset = 0;
                    synchronized (this) {
                        while (offset < count && !ended) {
                            while (size == buffer.length && !ended) wait();
                            if (ended) break;
                            int tail = (head + size) % buffer.length;
                            int n = Math.min(count - offset, Math.min(buffer.length - size, buffer.length - tail));
                            System.arraycopy(bytes, offset, buffer, tail, n);
                            size += n; offset += n; notifyAll();
                        }
                    }
                }
            } catch (Exception ignored) {
                // EOF/error is reported through terminal state; never log terminal bytes.
            } finally {
                boolean finish;
                synchronized (this) { finish = --readers == 0; if (finish) { ended = true; notifyAll(); } }
                if (finish) onEnd.run();
            }
        }, "SSH-Terminal-Output");
        reader.setDaemon(true);
        reader.start();
    }
    synchronized Map<String, Object> read(long waitMillis) throws InterruptedException {
        long deadline = System.nanoTime() + waitMillis * 1_000_000L;
        while (size == 0 && !ended && waitMillis > 0) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) break;
            wait(Math.max(1, remaining / 1_000_000L));
        }
        int count = Math.min(size, 64 * 1024);
        byte[] bytes = new byte[count];
        int first = Math.min(count, buffer.length - head);
        System.arraycopy(buffer, head, bytes, 0, first);
        System.arraycopy(buffer, 0, bytes, first, count - first);
        head = (head + count) % buffer.length;
        size -= count;
        notifyAll();
        Map<String, Object> result = metadata();
        result.put("data", Base64.getEncoder().encodeToString(bytes));
        result.put("outputSequence", ++sequence);
        result.put("hasMore", size > 0);
        result.put("eof", ended && size == 0);
        return result;
    }
    synchronized Map<String, Object> metadata() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("code", 200); result.put("backend", "ssh"); result.put("pty", true);
        result.put("resizable", true); result.put("longPolling", true); result.put("lineInput", false);
        result.put("alive", !ended); result.put("instanceId", instanceId);
        return result;
    }
    synchronized Map<String, Object> response(boolean includeOutput) throws InterruptedException {
        Map<String, Object> result = metadata();
        if (includeOutput) result.put("output", read(0));
        return result;
    }
    void write(String value) throws Exception {
        synchronized (this) { if (ended) throw new java.io.IOException("SSH 终端已结束"); }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        synchronized (shell) { shell.getOutputStream().write(bytes); shell.getOutputStream().flush(); }
    }
    void resize(String value) throws Exception {
        if (!value.matches("[0-9]{1,3},[0-9]{1,3}")) throw new IllegalArgumentException("终端尺寸无效");
        String[] parts = value.split(",");
        int cols = Math.max(20, Math.min(500, Integer.parseInt(parts[0])));
        int rows = Math.max(5, Math.min(200, Integer.parseInt(parts[1])));
        shell.changeWindowDimensions(cols, rows, 0, 0);
    }
    synchronized void close() { ended = true; notifyAll(); }
}
