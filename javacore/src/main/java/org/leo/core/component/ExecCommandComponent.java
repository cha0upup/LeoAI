package org.leo.core.component;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.UnsupportedEncodingException;
import java.io.Writer;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Persistent interactive terminal component.
 *
 * Unix uses one full PTY backend (Python) and one dependency-free pipe shell.
 * Windows prefers an installed winpty bridge and falls back to the native
 * command pipe. Keeping one optional PTY path and one basic fallback makes
 * startup behavior predictable across old and new hosts.
 *
 * Java 6 single-class payload: no lambdas, inner classes or Java 7+ APIs.
 */
public class ExecCommandComponent implements Runnable {

    private static final int OP_WRITE = 0;
    private static final int OP_READ = 1;
    private static final int OP_STOP = 2;
    private static final int OP_RESIZE = 3;

    private static final int BUFFER_SIZE = 1024;
    private static final int MAX_OUTPUT_BYTES = 10 * 1024 * 1024;
    private static final int MAX_PROCESS_COUNT = 32;
    private static final long IDLE_TIMEOUT_MS = 30L * 60L * 1000L;
    private static final long START_TIMEOUT_MS = 2500L;
    private static final long STOP_WAIT_MS = 750L;
    private static final long BACKEND_PROBE_MS = 300L;
    private static final int MAX_READ_WAIT_MS = 2000;

    private static final String KEY_LAST_ACCESS_TIME = "lastAccessTime";
    private static final String KEY_BACKEND = "backend";
    private static final String KEY_PTY = "pty";
    private static final String KEY_RESIZABLE = "resizable";
    private static final String KEY_BACKEND_FAILURES = "backendFailures";
    private static final String KEY_INSTANCE_ID = "instanceId";
    private static final String KEY_LONG_POLLING = "longPolling";

    private static final Map env = new ConcurrentHashMap();
    private static final Map THREAD_PARAMS = new ConcurrentHashMap();
    private static final String INSTANCE_ID = createInstanceId();
    private static final AtomicInteger THREAD_SEQUENCE = new AtomicInteger();

    private static final String PYTHON_PTY_BRIDGE =
        "from __future__ import print_function\n" +
        "import errno,fcntl,os,pty,select,signal,struct,sys,termios,time\n" +
        "shell_path,size_path=sys.argv[1:3]\n" +
        "environment=os.environ.copy()\n" +
        "environment['TERM']=environment.get('TERM') or 'xterm-256color'\n" +
        "environment['COLORTERM']=environment.get('COLORTERM') or 'truecolor'\n" +
        "environment['HISTFILE']=os.devnull\n" +
        "pid,master=pty.fork()\n" +
        "if pid==0: os.execve(shell_path,[shell_path,'-i'],environment)\n" +
        "flags=fcntl.fcntl(master,fcntl.F_GETFL)\n" +
        "fcntl.fcntl(master,fcntl.F_SETFL,flags|os.O_NONBLOCK)\n" +
        "running=[True]\n" +
        "def stop(sig,frame):\n" +
        " running[0]=False\n" +
        " try: os.kill(pid,sig)\n" +
        " except OSError: pass\n" +
        "for sig in (signal.SIGTERM,signal.SIGHUP,signal.SIGINT): signal.signal(sig,stop)\n" +
        "last_size=[None]\n" +
        "def copy(source,target):\n" +
        " try: data=os.read(source,65536)\n" +
        " except OSError as error:\n" +
        "  if error.errno in (errno.EAGAIN,errno.EINTR): return True\n" +
        "  if source==master and error.errno==errno.EIO: return False\n" +
        "  raise\n" +
        " if not data: return False\n" +
        " while data:\n" +
        "  try:\n" +
        "   written=os.write(target,data)\n" +
        "   if written<=0: return False\n" +
        "   data=data[written:]\n" +
        "  except OSError as error:\n" +
        "   if error.errno in (errno.EAGAIN,errno.EINTR): time.sleep(0.01)\n" +
        "   else: raise\n" +
        " return True\n" +
        "def resize():\n" +
        " try:\n" +
        "  stream=open(size_path,'rb'); raw=stream.read(64); stream.close()\n" +
        "  if raw==last_size[0]: return\n" +
        "  last_size[0]=raw\n" +
        "  if not isinstance(raw,str): raw=raw.decode('ascii','ignore')\n" +
        "  cols,rows=[int(value) for value in raw.strip().split(',')]\n" +
        "  cols=max(20,min(500,cols)); rows=max(5,min(200,rows))\n" +
        "  fcntl.ioctl(master,termios.TIOCSWINSZ,struct.pack('HHHH',rows,cols,0,0))\n" +
        "  try: os.kill(pid,signal.SIGWINCH)\n" +
        "  except OSError: pass\n" +
        " except (IOError,OSError,ValueError): pass\n" +
        "try:\n" +
        " while running[0]:\n" +
        "  resize()\n" +
        "  ready=select.select([master,0],[],[],0.20)[0]\n" +
        "  if master in ready and not copy(master,1): break\n" +
        "  if 0 in ready and not copy(0,master): break\n" +
        "  if os.waitpid(pid,os.WNOHANG)[0]==pid: pid=0; break\n" +
        "finally:\n" +
        " if pid>0:\n" +
        "  try: os.kill(pid,signal.SIGTERM)\n" +
        "  except OSError: pass\n" +
        "  try: os.waitpid(pid,0)\n" +
        "  except OSError: pass\n" +
        " try: os.close(master)\n" +
        " except OSError: pass\n";

    private HashMap<String, Object> params;
    private HashMap<String, Object> results;

    public void run() {
        String workerProcessId = (String) THREAD_PARAMS.remove(Thread.currentThread());
        if (workerProcessId != null) {
            runProcess(workerProcessId);
            return;
        }

        java.lang.reflect.InvocationHandler handler =
                (java.lang.reflect.InvocationHandler) Thread.currentThread().getContextClassLoader();
        try {
            params = (HashMap) handler.invoke(null, null, null);
            results = new HashMap();
            execCommand();
        } catch (Throwable error) {
            if (results == null) results = new HashMap();
            results.put("code", Integer.valueOf(500));
            results.put("msg", error.getMessage() != null ? error.getMessage() : error.getClass().getName());
        }
        try {
            handler.invoke(null, null, new Object[]{results});
        } catch (Throwable ignored) {
        }
    }

    private void runProcess(String processId) {
        Map processMap = (Map) env.get(processId);
        if (processMap == null) return;

        Process process = null;
        InputStream stdout = null;
        InputStreamReader reader = null;
        try {
            process = startCompatibleProcess(processId, processMap);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            processMap.put("stdin", process.getOutputStream());
            processMap.put("output", output);
            processMap.put("process", process);
            notifyStateChange(processMap);

            if (Boolean.TRUE.equals(processMap.get("stopped"))) {
                terminateProcessTree(process);
                return;
            }

            stdout = process.getInputStream();
            reader = new InputStreamReader(stdout, detectCharset());
            char[] buffer = new char[BUFFER_SIZE];
            int length;
            while ((length = reader.read(buffer)) != -1) {
                byte[] utf8 = new String(buffer, 0, length).getBytes("UTF-8");
                synchronized (output) {
                    int remaining = MAX_OUTPUT_BYTES - output.size();
                    if (remaining > 0) {
                        output.write(utf8, 0, Math.min(remaining, utf8.length));
                    }
                    if (utf8.length > remaining) processMap.put("outputTruncated", Boolean.TRUE);
                }
                notifyStateChange(processMap);
            }
        } catch (Throwable error) {
            processMap.put("error", error.getMessage() != null ? error.getMessage() : error.getClass().getName());
            if (process != null) {
                terminateProcessTree(process);
            }
        } finally {
            if (reader != null) {
                try { reader.close(); } catch (IOException ignored) {}
            } else if (stdout != null) {
                try { stdout.close(); } catch (IOException ignored) {}
            }
            if (process != null) closeProcessStreams(process);
            deleteTerminalFiles(processMap);
            processMap.put("exited", Boolean.TRUE);
            notifyStateChange(processMap);
        }
    }

    private void execCommand() throws Exception {
        String processId = getStringParam("processId");
        if (processId == null || processId.trim().length() == 0 || processId.length() > 128) {
            throw new IllegalArgumentException("invalid processId");
        }
        Object operationValue = params.get("op");
        if (!(operationValue instanceof Number)) throw new IllegalArgumentException("op must be a number");
        int operation = ((Number) operationValue).intValue();
        cleanupExpiredProcesses();

        Map processMap = (Map) env.get(processId);
        if (processMap == null) {
            if (operation != OP_WRITE) {
                writeMissingProcessResult(operation);
                return;
            }
            processMap = startProcess(processId);
        }

        touchProcess(processMap);
        if (operation == OP_WRITE) {
            try {
                waitForProcessReady(processMap);
            } catch (Exception error) {
                destroyProcess(processId, processMap);
                throw error;
            }
            String command = getStringParam("cmd");
            if (!"init".equals(command)) writeCommand(processMap);
            writeTerminalMetadata(processMap);
            results.put("initialized", Boolean.TRUE);
            results.put("alive", Boolean.TRUE);
        } else if (operation == OP_READ) {
            waitForReadableOutput(processMap, getBoundedIntParam("waitMs", 0, MAX_READ_WAIT_MS));
            readOutput(processMap);
        } else if (operation == OP_RESIZE) {
            resizeTerminal(processMap);
        } else if (operation == OP_STOP) {
            destroyProcess(processId, processMap);
            results.put("alive", Boolean.FALSE);
            results.put("stopped", Boolean.TRUE);
        } else {
            throw new IllegalArgumentException("Invalid op: " + operation);
        }
        results.put("code", Integer.valueOf(200));
    }

    private Map startProcess(String processId) {
        ConcurrentHashMap placeholder = new ConcurrentHashMap();
        long now = System.currentTimeMillis();
        placeholder.put(KEY_LAST_ACCESS_TIME, Long.valueOf(now));
        Map existing;
        synchronized (env) {
            existing = (Map) env.get(processId);
            if (existing == null) {
                if (env.size() >= MAX_PROCESS_COUNT) {
                    throw new IllegalStateException("too many active terminal processes, max=" + MAX_PROCESS_COUNT);
                }
                ((ConcurrentHashMap) env).put(processId, placeholder);
            }
        }
        if (existing != null) return existing;

        Object hostId = params != null ? params.get("hostId") : null;
        Thread worker = new Thread(this, workerThreadName(hostId, processId));
        worker.setDaemon(true);
        THREAD_PARAMS.put(worker, processId);
        try {
            worker.start();
        } catch (RuntimeException error) {
            THREAD_PARAMS.remove(worker);
            ((ConcurrentHashMap) env).remove(processId, placeholder);
            throw error;
        }
        return placeholder;
    }

    private void waitForProcessReady(Map processMap) throws Exception {
        long deadline = System.currentTimeMillis() + START_TIMEOUT_MS;
        synchronized (processMap) {
            while (processMap.get("stdin") == null && processMap.get("error") == null
                    && !Boolean.TRUE.equals(processMap.get("exited"))) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) break;
                processMap.wait(remaining);
            }
        }
        if (processMap.get("stdin") == null) {
            Object error = processMap.get("error");
            throw new IllegalStateException(error != null ? String.valueOf(error) : "terminal startup timed out");
        }
    }

    private void notifyStateChange(Map processMap) {
        synchronized (processMap) {
            processMap.notifyAll();
        }
    }

    private void writeCommand(Map processMap) throws IOException {
        byte[] command = getBytesParam("cmd");
        OutputStream writer = (OutputStream) processMap.get("stdin");
        if (writer == null) throw new IllegalStateException("terminal stdin is not ready");
        if (isWindows() && !Boolean.TRUE.equals(processMap.get(KEY_PTY))) {
            command = convertCrForWindows(command);
            echoForWindows(processMap, command);
        }
        synchronized (writer) {
            writer.write(command);
            writer.flush();
        }
    }

    private void waitForReadableOutput(Map processMap, int waitMillis) {
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

    private boolean hasReadableOutput(Map processMap) {
        ByteArrayOutputStream output = (ByteArrayOutputStream) processMap.get("output");
        if (output == null) return false;
        synchronized (output) {
            return output.size() > 0;
        }
    }

    private void readOutput(Map processMap) throws IOException {
        ByteArrayOutputStream output = (ByteArrayOutputStream) processMap.get("output");
        byte[] data = new byte[0];
        if (output != null) {
            synchronized (output) {
                data = output.toByteArray();
                output.reset();
            }
        }
        if (Boolean.TRUE.equals(processMap.remove("outputTruncated"))) {
            byte[] marker = "\r\n[terminal output truncated]\r\n".getBytes("UTF-8");
            ByteArrayOutputStream combined = new ByteArrayOutputStream(data.length + marker.length);
            combined.write(data);
            combined.write(marker);
            data = combined.toByteArray();
        }
        results.put("data", data);

        Process process = (Process) processMap.get("process");
        if (process == null) {
            results.put("alive", Boolean.valueOf(!Boolean.TRUE.equals(processMap.get("exited"))));
            results.put("starting", Boolean.TRUE);
        } else {
            try {
                results.put("exitCode", Integer.valueOf(process.exitValue()));
                results.put("alive", Boolean.FALSE);
            } catch (IllegalThreadStateException running) {
                results.put("alive", Boolean.TRUE);
            }
        }
        Object error = processMap.get("error");
        if (error != null) results.put("error", error);
        writeTerminalMetadata(processMap);
    }

    private void resizeTerminal(Map processMap) throws IOException {
        int[] size = parseTerminalSize(getStringParam("cmd"));
        boolean resized = false;
        File sizeFile = (File) processMap.get("sizeFile");
        if (Boolean.TRUE.equals(processMap.get(KEY_RESIZABLE)) && sizeFile != null) {
            writeTextFile(sizeFile, size[0] + "," + size[1]);
            resized = true;
        }
        results.put("cols", Integer.valueOf(size[0]));
        results.put("rows", Integer.valueOf(size[1]));
        results.put("resized", Boolean.valueOf(resized));
        writeTerminalMetadata(processMap);
    }

    private int[] parseTerminalSize(String value) {
        if (value == null) throw new IllegalArgumentException("resize expects cols,rows");
        int separator = value.indexOf(',');
        if (separator <= 0 || separator != value.lastIndexOf(',')) {
            throw new IllegalArgumentException("resize expects cols,rows");
        }
        try {
            int cols = Integer.parseInt(value.substring(0, separator).trim());
            int rows = Integer.parseInt(value.substring(separator + 1).trim());
            cols = Math.max(20, Math.min(500, cols));
            rows = Math.max(5, Math.min(200, rows));
            return new int[]{cols, rows};
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("resize expects cols,rows");
        }
    }

    private void writeTerminalMetadata(Map processMap) {
        results.put(KEY_BACKEND, valueOrDefault(processMap.get(KEY_BACKEND), "starting"));
        results.put(KEY_PTY, Boolean.valueOf(Boolean.TRUE.equals(processMap.get(KEY_PTY))));
        results.put(KEY_RESIZABLE, Boolean.valueOf(Boolean.TRUE.equals(processMap.get(KEY_RESIZABLE))));
        results.put(KEY_INSTANCE_ID, INSTANCE_ID);
        results.put(KEY_LONG_POLLING, Boolean.TRUE);
        Object backendFailures = processMap.get(KEY_BACKEND_FAILURES);
        if (backendFailures != null) results.put(KEY_BACKEND_FAILURES, backendFailures);
    }

    private Object valueOrDefault(Object value, Object fallback) {
        return value != null ? value : fallback;
    }

    private void writeMissingProcessResult(int operation) {
        results.put(KEY_INSTANCE_ID, INSTANCE_ID);
        if (operation == OP_READ) {
            results.put("data", new byte[0]);
            results.put("alive", Boolean.FALSE);
            results.put("missing", Boolean.TRUE);
            results.put("code", Integer.valueOf(200));
            return;
        }
        if (operation == OP_STOP) {
            results.put("alive", Boolean.FALSE);
            results.put("missing", Boolean.TRUE);
            results.put("code", Integer.valueOf(200));
            return;
        }
        if (operation == OP_RESIZE) throw new IllegalStateException("terminal session is not initialized");
        throw new IllegalArgumentException("Invalid op: " + operation);
    }

    private void touchProcess(Map processMap) {
        processMap.put(KEY_LAST_ACCESS_TIME, Long.valueOf(System.currentTimeMillis()));
    }

    private void cleanupExpiredProcesses() {
        long now = System.currentTimeMillis();
        Iterator iterator = env.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry entry = (Map.Entry) iterator.next();
            Map processMap = (Map) entry.getValue();
            Long lastAccess = (Long) processMap.get(KEY_LAST_ACCESS_TIME);
            if (lastAccess != null && now - lastAccess.longValue() > IDLE_TIMEOUT_MS) {
                destroyProcess((String) entry.getKey(), processMap);
            }
        }
    }

    private void destroyProcess(String processId, Map processMap) {
        processMap.put("stopped", Boolean.TRUE);
        OutputStream stdin = (OutputStream) processMap.get("stdin");
        if (stdin != null) {
            try { stdin.close(); } catch (Exception ignored) {}
        }
        Process process = (Process) processMap.get("process");
        terminateProcessTree(process);
        ((ConcurrentHashMap) env).remove(processId, processMap);
        deleteTerminalFiles(processMap);
        notifyStateChange(processMap);
    }

    private void terminateProcessTree(Process process) {
        if (process == null) return;
        java.util.List descendants = snapshotDescendantProcessHandles(process);
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

    /**
     * Java 9+ 环境通过反射捕获并清理子进程树；旧运行时保持原有 Process.destroy 行为。
     * 反射实现避免生成的组件字节码直接依赖 ProcessHandle。
     */
    private java.util.List snapshotDescendantProcessHandles(Process process) {
        java.util.ArrayList handles = new java.util.ArrayList();
        Object stream = null;
        try {
            Class processHandleClass = Class.forName("java.lang.ProcessHandle");
            Object root = Process.class.getMethod("toHandle", new Class[0])
                    .invoke(process, new Object[0]);
            stream = processHandleClass.getMethod("descendants", new Class[0])
                    .invoke(root, new Object[0]);
            Class baseStreamClass = Class.forName("java.util.stream.BaseStream");
            Iterator iterator = (Iterator) baseStreamClass.getMethod("iterator", new Class[0])
                    .invoke(stream, new Object[0]);
            while (iterator.hasNext()) handles.add(iterator.next());
        } catch (Throwable ignored) {
            handles.clear();
        } finally {
            if (stream != null) {
                try {
                    Class.forName("java.util.stream.BaseStream")
                            .getMethod("close", new Class[0]).invoke(stream, new Object[0]);
                } catch (Throwable ignored) {}
            }
        }
        return handles;
    }

    private void destroyProcessHandles(java.util.List handles, boolean forcibly) {
        if (handles == null || handles.isEmpty()) return;
        try {
            Class processHandleClass = Class.forName("java.lang.ProcessHandle");
            java.lang.reflect.Method destroy = processHandleClass.getMethod(
                    forcibly ? "destroyForcibly" : "destroy", new Class[0]);
            int index;
            for (index = handles.size() - 1; index >= 0; index--) {
                try { destroy.invoke(handles.get(index), new Object[0]); } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
    }

    private void destroyProcessForcibly(Process process) {
        try {
            Process.class.getMethod("destroyForcibly", new Class[0])
                    .invoke(process, new Object[0]);
        } catch (Throwable unavailable) {
            try { process.destroy(); } catch (Exception ignored) {}
        }
    }

    private Process startCompatibleProcess(String processId, Map processMap) throws Exception {
        int attempt;
        int attempts = 2;
        for (attempt = 0; attempt < attempts; attempt++) {
            ProcessBuilder builder;
            try {
                builder = createProcessBuilder(processId, processMap, attempt);
            } catch (Throwable error) {
                recordBackendFailure(processMap,
                        String.valueOf(valueOrDefault(processMap.get(KEY_BACKEND), "terminal-backend-" + attempt)),
                        error.getMessage() != null ? error.getMessage() : error.getClass().getName());
                resetBackendFiles(processMap);
                continue;
            }
            if (builder == null) continue;
            builder.redirectErrorStream(true);
            Process candidate = null;
            try {
                candidate = builder.start();
                if (waitForBackendReady(candidate)) return candidate;
                recordBackendFailure(processMap, String.valueOf(processMap.get(KEY_BACKEND)),
                        readProcessMessage(candidate));
            } catch (Throwable error) {
                recordBackendFailure(processMap, String.valueOf(processMap.get(KEY_BACKEND)),
                        error.getMessage() != null ? error.getMessage() : error.getClass().getName());
            } finally {
                if (candidate != null && !isProcessAlive(candidate)) closeProcessStreams(candidate);
            }
            if (candidate != null && isProcessAlive(candidate)) {
                terminateProcessTree(candidate);
                closeProcessStreams(candidate);
            }
            resetBackendFiles(processMap);
        }
        throw new IOException("no compatible terminal backend: "
                + valueOrDefault(processMap.get(KEY_BACKEND_FAILURES), "no candidates"));
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

    private void recordBackendFailure(Map processMap, String backend, String message) {
        String previous = (String) processMap.get(KEY_BACKEND_FAILURES);
        String current = backend + (message == null || message.length() == 0 ? "" : ": " + message);
        processMap.put(KEY_BACKEND_FAILURES, previous == null ? current : previous + "; " + current);
    }

    private void closeProcessStreams(Process process) {
        try { process.getInputStream().close(); } catch (Exception ignored) {}
        try { process.getErrorStream().close(); } catch (Exception ignored) {}
        try { process.getOutputStream().close(); } catch (Exception ignored) {}
    }

    private void resetBackendFiles(Map processMap) {
        deleteTerminalFiles(processMap);
        processMap.remove("helperFile");
        processMap.remove("sizeFile");
    }

    private ProcessBuilder createProcessBuilder(String processId, Map processMap, int attempt) throws IOException {
        if (isWindows()) {
            String shell = selectWindowsShell();
            if (attempt == 0) {
                String winpty = findExecutable("winpty.exe");
                if (winpty == null) winpty = findExecutable("winpty");
                if (winpty == null) {
                    recordBackendFailure(processMap, "windows-winpty", "helper unavailable");
                    return null;
                }
                setBackend(processMap, "windows-winpty", true, false);
                return new ProcessBuilder(new String[]{winpty, "-Xallow-non-tty", shell, "/Q", "/D"});
            }
            if (attempt != 1) return null;
            setBackend(processMap, "windows-cmd-pipe", false, false);
            return new ProcessBuilder(new String[]{shell, "/Q", "/D"});
        }

        String shell = selectShell();
        if (attempt == 0) {
            String python = findExecutable("python3");
            if (python == null) python = findExecutable("python");
            if (python == null) {
                recordBackendFailure(processMap, "python-pty", "helper unavailable");
                return null;
            }
            setBackend(processMap, new File(python).getName() + "-pty", true, true);
            File directory = terminalDirectory();
            String key = Integer.toHexString(processId.hashCode()) + "-"
                    + Integer.toHexString(System.identityHashCode(processMap));
            File helper = new File(directory, key + ".py");
            File size = new File(directory, key + ".size");
            writeTextFile(helper, PYTHON_PTY_BRIDGE);
            writeTextFile(size, "80,24");
            processMap.put("helperFile", helper);
            processMap.put("sizeFile", size);
            return new ProcessBuilder(new String[]{python, helper.getAbsolutePath(), shell, size.getAbsolutePath()});
        }

        if (attempt != 1) return null;
        setBackend(processMap, "unix-pipe", false, false);
        return new ProcessBuilder(new String[]{shell, "-i"});
    }

    private void setBackend(Map processMap, String backend, boolean pty, boolean resizable) {
        processMap.put(KEY_BACKEND, backend);
        processMap.put(KEY_PTY, Boolean.valueOf(pty));
        processMap.put(KEY_RESIZABLE, Boolean.valueOf(resizable));
    }

    private File terminalDirectory() throws IOException {
        File directory = new File(System.getProperty("java.io.tmpdir"), ".leo-java-terminal");
        if (!directory.isDirectory() && !directory.mkdirs() && !directory.isDirectory()) {
            throw new IOException("failed to create terminal directory");
        }
        directory.setReadable(false, false);
        directory.setWritable(false, false);
        directory.setExecutable(false, false);
        directory.setReadable(true, true);
        directory.setWritable(true, true);
        directory.setExecutable(true, true);
        return directory;
    }

    private void writeTextFile(File file, String value) throws IOException {
        Writer writer = null;
        try {
            writer = new OutputStreamWriter(new FileOutputStream(file, false), "UTF-8");
            writer.write(value);
            writer.flush();
        } finally {
            if (writer != null) try { writer.close(); } catch (IOException ignored) {}
        }
        file.setReadable(true, true);
        file.setWritable(true, true);
    }

    private void deleteTerminalFiles(Map processMap) {
        File helper = (File) processMap.get("helperFile");
        File size = (File) processMap.get("sizeFile");
        if (helper != null) try { helper.delete(); } catch (Exception ignored) {}
        if (size != null) try { size.delete(); } catch (Exception ignored) {}
    }

    private String findExecutable(String name) {
        String path = System.getenv("PATH");
        if (path != null) {
            String[] directories = path.split(File.pathSeparator);
            int index;
            for (index = 0; index < directories.length; index++) {
                File candidate = new File(directories[index], name);
                if (candidate.isFile() && candidate.canExecute()) return candidate.getAbsolutePath();
            }
        }
        String[] defaults = new String[]{"/usr/bin/", "/bin/", "/usr/local/bin/", "/opt/homebrew/bin/"};
        int index;
        for (index = 0; index < defaults.length; index++) {
            File candidate = new File(defaults[index] + name);
            if (candidate.isFile() && candidate.canExecute()) return candidate.getAbsolutePath();
        }
        return null;
    }

    private String selectShell() {
        String configured = System.getenv("SHELL");
        if (configured != null) {
            File file = new File(configured);
            if (file.isFile() && file.canExecute()) return file.getAbsolutePath();
        }
        String[] candidates = new String[]{"/bin/bash", "/bin/zsh", "/bin/ksh", "/bin/sh"};
        int index;
        for (index = 0; index < candidates.length; index++) {
            File file = new File(candidates[index]);
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

    private void echoForWindows(Map processMap, byte[] command) {
        ByteArrayOutputStream output = (ByteArrayOutputStream) processMap.get("output");
        if (output == null) return;
        synchronized (output) {
            int index;
            for (index = 0; index < command.length; index++) {
                byte value = command[index];
                if (value == '\r' || value == '\n') {
                    output.write('\r'); output.write('\n');
                    if (value == '\r' && index + 1 < command.length && command[index + 1] == '\n') index++;
                } else if (value == '\b' || value == 127) {
                    output.write('\b'); output.write(' '); output.write('\b');
                } else if (value >= 32) {
                    output.write(value);
                }
            }
        }
    }

    private byte[] convertCrForWindows(byte[] input) {
        int extra = 0;
        int index;
        for (index = 0; index < input.length; index++) {
            if (input[index] == '\r' && (index + 1 >= input.length || input[index + 1] != '\n')) extra++;
        }
        if (extra == 0) return input;
        byte[] result = new byte[input.length + extra];
        int target = 0;
        for (index = 0; index < input.length; index++) {
            result[target++] = input[index];
            if (input[index] == '\r' && (index + 1 >= input.length || input[index + 1] != '\n')) {
                result[target++] = '\n';
            }
        }
        return result;
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

    private static String createInstanceId() {
        try {
            java.lang.management.RuntimeMXBean runtime = java.lang.management.ManagementFactory.getRuntimeMXBean();
            String value = ExecCommandComponent.class.getName() + "|"
                    + runtime.getName() + "|" + runtime.getStartTime();
            return Long.toHexString(runtime.getStartTime()) + "-" + Integer.toHexString(value.hashCode());
        } catch (Throwable ignored) {
            String value = ExecCommandComponent.class.getName() + "|"
                    + System.getProperty("java.vm.name", "java") + "|"
                    + System.getProperty("user.dir", "") + "|" + System.currentTimeMillis();
            return Integer.toHexString(value.hashCode());
        }
    }

    private static String workerThreadName(Object hostId, String processId) {
        String seed = String.valueOf(hostId) + "|" + processId + "|" + INSTANCE_ID;
        return "worker-" + Integer.toHexString(seed.hashCode()) + "-"
                + THREAD_SEQUENCE.incrementAndGet();
    }

    private String getStringParam(String key) {
        Object value = params.get(key);
        if (value == null) return null;
        if (value instanceof String) return (String) value;
        try {
            return new String((byte[]) value, "UTF-8");
        } catch (UnsupportedEncodingException error) {
            throw new RuntimeException(error);
        }
    }

    private byte[] getBytesParam(String key) {
        Object value = params.get(key);
        if (value instanceof byte[]) return (byte[]) value;
        try {
            return value == null ? new byte[0] : String.valueOf(value).getBytes("UTF-8");
        } catch (UnsupportedEncodingException error) {
            throw new RuntimeException(error);
        }
    }

    private int getBoundedIntParam(String key, int minimum, int maximum) {
        Object value = params.get(key);
        int parsed = minimum;
        if (value instanceof Number) {
            parsed = ((Number) value).intValue();
        } else if (value != null) {
            try { parsed = Integer.parseInt(String.valueOf(value).trim()); } catch (Exception ignored) {}
        }
        return Math.max(minimum, Math.min(maximum, parsed));
    }
}
