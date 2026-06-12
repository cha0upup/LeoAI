package org.leo.core.component;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import javax.net.ssl.HostnameVerifier;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 指纹识别组件
 * <p>
 * 在 puppet 端执行 HTTP/TCP 指纹扫描，用 JavaScript 脚本判定指纹命中。
 * 支持多目标并发、暂停/恢复/停止控制。
 * <p>
 * SSL 处理参考 HttpRequestComponent：实现 InvocationHandler，用 Proxy 动态生成
 * HostnameVerifier / X509TrustManager，避免直接实现多接口导致 ASM COMPUTE_FRAMES
 * 在类型层次解析时触发 "Type not present" 错误。
 * <p>
 * 遵循 COMPONENT_GUIDE.md：Java 1.6 语法，无 lambda/匿名内部类/diamond。
 *
 * @author LeoSpring
 * @version 3.0
 */
public class FingerprintComponent implements Runnable, InvocationHandler {

    private HashMap params;
    private HashMap results;

    // ==================== 常量 ====================

    private static final String STATE_RUNNING = "RUNNING";
    private static final String STATE_PAUSED  = "PAUSED";
    private static final String STATE_STOPPED = "STOPPED";

    private static final int  DEFAULT_THREADS        = 5;
    private static final int  MAX_THREADS            = 64;
    private static final int  DEFAULT_TIMEOUT        = 3000;
    private static final int  DEFAULT_MAX_BODY_BYTES = 1024 * 1024;
    private static final long STOPPED_TASK_TTL_MILLIS = 30L * 60L * 1000L;

    // ==================== 静态状态 ====================

    private static final ConcurrentHashMap tasks     = new ConcurrentHashMap();
    private static final ConcurrentHashMap taskLocks = new ConcurrentHashMap();

    private static volatile SSLSocketFactory trustAllFactory;

    // ==================== worker 字段 ====================

    private String taskId;
    private Map    target;

    // ==================== 构造器 ====================

    public FingerprintComponent() {}

    private FingerprintComponent(String taskId, Map target) {
        this.taskId = taskId;
        this.target = target;
    }

    // ==================== InvocationHandler（SSL 代理用） ====================

    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        String name = method.getName();
        if ("verify".equals(name))             return Boolean.TRUE;
        if ("getAcceptedIssuers".equals(name)) return new X509Certificate[0];
        return null;
    }

    // ==================== 组件入口 ====================

    public void run() {
        if (taskId == null) {
            // 主控模式：框架通过 newInstance().run() 调用
            java.lang.reflect.InvocationHandler h =
                    (java.lang.reflect.InvocationHandler) Thread.currentThread().getContextClassLoader();
            try {
                Object raw = h.invoke(null, null, null);
                params  = toHashMap(raw);
                results = new java.util.HashMap();
                dispatch();
            } catch (Throwable t) {
                if (results == null) results = new java.util.HashMap();
                results.put("code", Integer.valueOf(500));
                results.put("msg", t.getMessage() != null ? t.getMessage() : t.getClass().getName());
            }
            if (results != null) {
                try { h.invoke(null, null, new Object[]{results}); } catch (Throwable ignored) {}
            }
            return;
        }

        // worker 模式：线程池调用
        HashMap task = (HashMap) tasks.get(taskId);
        if (task == null || target == null) return;

        String key = targetKey(target);
        try {
            if (!waitIfPaused(task)) return;

            Map  rule     = (Map)  task.get("rule");
            List requests = (List) rule.get("requests");
            List resp          = new ArrayList();
            List requestErrors = new ArrayList();

            for (int i = 0; i < requests.size(); i++) {
                if (!waitIfPaused(task)) return;
                Map req = (Map) requests.get(i);
                try {
                    if (isTcpTarget(target)) {
                        resp.add(execTcp(target, req));
                    } else {
                        resp.add(execHttp(target, req, this));
                    }
                } catch (Exception e) {
                    HashMap err = new HashMap();
                    err.put("requestIndex", Integer.valueOf(i));
                    err.put("errorType",    e.getClass().getName());
                    err.put("error",        e.getMessage());
                    resp.add(err);
                    requestErrors.add(err);
                }
            }

            boolean hit = false;
            try {
                hit = evalJs(resp, stringVal(rule.get("script")));
            } catch (Exception e) {
                HashMap err = new HashMap();
                err.put("stage",     "script");
                err.put("errorType", e.getClass().getName());
                err.put("error",     e.getMessage());
                requestErrors.add(err);
            }

            ((Map) task.get("results")).put(key, Boolean.valueOf(hit));
            if (!requestErrors.isEmpty()) {
                ((Map) task.get("errors")).put(key, requestErrors);
            }
        } finally {
            markCompleted(task);
        }
    }

    // ==================== 方法调度 ====================

    private void dispatch() throws Exception {
        String method = stringVal(params.get("methodName"));
        if ("startScan".equals(method)) {
            String id = startScan(params);
            results.put("taskId", id);
            results.put("code",   Integer.valueOf(200));
        } else if ("queryResult".equals(method)) {
            queryResult(params.get("taskId"));
        } else if ("pauseScan".equals(method)) {
            pause(params.get("taskId"));
            results.put("code", Integer.valueOf(200));
        } else if ("resumeScan".equals(method)) {
            resume(params.get("taskId"));
            results.put("code", Integer.valueOf(200));
        } else if ("stopScan".equals(method)) {
            stop(params.get("taskId"));
            results.put("code", Integer.valueOf(200));
        } else {
            throw new IllegalArgumentException("Unknown fingerprint method: " + method);
        }
    }

    // ==================== 扫描生命周期 ====================

    private String startScan(Map p) {
        cleanupStoppedTasks();

        List targets  = requireList(p.get("targets"),     "targets");
        Map  rule     = requireMap (p.get("rule"),        "rule");
        List requests = requireList(rule.get("requests"), "rule.requests");
        String script = stringVal(rule.get("script"));

        if (script.trim().length() == 0) throw new IllegalArgumentException("rule.script cannot be empty");
        if (targets.isEmpty())           throw new IllegalArgumentException("targets cannot be empty");
        if (requests.isEmpty())          throw new IllegalArgumentException("rule.requests cannot be empty");

        int threads = intVal(p.get("threads"), DEFAULT_THREADS);
        if (threads < 1)              threads = 1;
        if (threads > MAX_THREADS)    threads = MAX_THREADS;
        if (threads > targets.size()) threads = targets.size();

        String id = UUID.randomUUID().toString();
        HashMap task = new HashMap();
        task.put("taskId",    id);
        task.put("status",    STATE_RUNNING);
        task.put("rule",      rule);
        task.put("targets",   targets);
        task.put("total",     Integer.valueOf(targets.size()));
        task.put("results",   new ConcurrentHashMap());
        task.put("errors",    new ConcurrentHashMap());
        task.put("completed", new AtomicInteger(0));
        task.put("createdAt", Long.valueOf(System.currentTimeMillis()));

        Object lock = new Object();
        tasks.put(id, task);
        taskLocks.put(id, lock);

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        task.put("executor", pool);
        for (int i = 0; i < targets.size(); i++) {
            Object item = targets.get(i);
            if (!(item instanceof Map)) throw new IllegalArgumentException("targets[" + i + "] must be an object");
            pool.execute(new FingerprintComponent(id, (Map) item));
        }
        pool.shutdown();
        return id;
    }

    // ==================== 暂停 / 恢复 / 停止 ====================

    private boolean waitIfPaused(HashMap task) {
        Object lock = taskLocks.get(taskId);
        if (lock == null) return false;
        synchronized (lock) {
            while (STATE_PAUSED.equals(task.get("status"))) {
                if (Thread.currentThread().isInterrupted()) return false;
                try { lock.wait(1000); } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            return !STATE_STOPPED.equals(task.get("status")) && !Thread.currentThread().isInterrupted();
        }
    }

    private void pause(Object id) {
        HashMap task = requireTask(id);
        Object  lock = taskLocks.get(id);
        if (lock == null) throw new IllegalStateException("Fingerprint task lock not found: " + id);
        synchronized (lock) {
            if (STATE_STOPPED.equals(task.get("status")))
                throw new IllegalStateException("Fingerprint task already stopped: " + id);
            task.put("status", STATE_PAUSED);
        }
    }

    private void resume(Object id) {
        HashMap task = requireTask(id);
        Object  lock = taskLocks.get(id);
        if (lock == null) throw new IllegalStateException("Fingerprint task lock not found: " + id);
        synchronized (lock) {
            if (STATE_STOPPED.equals(task.get("status")))
                throw new IllegalStateException("Fingerprint task already stopped: " + id);
            task.put("status", STATE_RUNNING);
            lock.notifyAll();
        }
    }

    private void stop(Object id) {
        HashMap task = requireTask(id);
        Object  lock = taskLocks.get(id);
        if (lock != null) {
            synchronized (lock) {
                task.put("status",     STATE_STOPPED);
                task.put("finishedAt", Long.valueOf(System.currentTimeMillis()));
                lock.notifyAll();
            }
        } else {
            synchronized (task) {
                task.put("status",     STATE_STOPPED);
                task.put("finishedAt", Long.valueOf(System.currentTimeMillis()));
            }
        }
        Object executor = task.get("executor");
        if (executor instanceof ExecutorService) ((ExecutorService) executor).shutdownNow();
        task.remove("executor");
    }

    private void markCompleted(HashMap task) {
        AtomicInteger completed = (AtomicInteger) task.get("completed");
        int done  = completed != null ? completed.incrementAndGet() : 0;
        int total = intVal(task.get("total"), 0);
        if (total > 0 && done >= total) {
            Object lock = taskLocks.get(task.get("taskId"));
            if (lock != null) {
                synchronized (lock) {
                    if (!STATE_STOPPED.equals(task.get("status"))) task.put("status", STATE_STOPPED);
                    if (task.get("finishedAt") == null) task.put("finishedAt", Long.valueOf(System.currentTimeMillis()));
                    lock.notifyAll();
                }
            } else {
                synchronized (task) {
                    if (!STATE_STOPPED.equals(task.get("status"))) task.put("status", STATE_STOPPED);
                    if (task.get("finishedAt") == null) task.put("finishedAt", Long.valueOf(System.currentTimeMillis()));
                }
            }
            task.remove("executor");
        }
    }

    // ==================== 查询 ====================

    private void queryResult(Object id) {
        cleanupStoppedTasks();
        HashMap task = (HashMap) tasks.get(id);
        if (task == null) {
            results.put("code", Integer.valueOf(404));
            results.put("msg",  "Fingerprint task not found: " + id);
            return;
        }
        AtomicInteger completed = (AtomicInteger) task.get("completed");
        HashMap simple = new HashMap();
        simple.put("taskId",     task.get("taskId"));
        simple.put("status",     task.get("status"));
        simple.put("total",      task.get("total"));
        simple.put("completed",  Integer.valueOf(completed != null ? completed.get() : 0));
        simple.put("results",    task.get("results"));
        simple.put("errors",     task.get("errors"));
        simple.put("createdAt",  task.get("createdAt"));
        simple.put("finishedAt", task.get("finishedAt"));
        results.put("result", simple);
        results.put("code",   Integer.valueOf(200));
    }

    // ==================== HTTP 执行 ====================

    static HashMap execHttp(Map target, Map req) throws Exception {
        return execHttp(target, req, new FingerprintComponent());
    }

    static HashMap execHttp(Map target, Map req, InvocationHandler sslHandler) throws Exception {
        String baseUrl = stringVal(target.get("baseUrl"));
        if (baseUrl.length() == 0) throw new IllegalArgumentException("HTTP target requires baseUrl");

        String uri = stringVal(req.get("uri"));
        if (uri.length() == 0) uri = stringVal(req.get("path"));
        if (uri.length() == 0) uri = "/";

        String method = stringVal(req.get("method")).toUpperCase(Locale.ENGLISH);
        if (method.length() == 0) method = "GET";

        int    timeout      = timeoutVal(req.get("timeout"));
        String charset      = charsetVal(req.get("charset"));
        int    maxBodyBytes = maxBodyBytesVal(req.get("maxBodyBytes"));

        HttpURLConnection conn = null;
        OutputStream os = null;
        InputStream  is = null;
        try {
            URL url = new URL(buildUrl(baseUrl, uri));
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(timeout);
            conn.setReadTimeout(timeout);
            conn.setUseCaches(false);
            conn.setInstanceFollowRedirects(false);

            if (conn instanceof HttpsURLConnection) {
                HttpsURLConnection httpsConn = (HttpsURLConnection) conn;
                httpsConn.setSSLSocketFactory(buildTrustAllFactory(sslHandler));
                ClassLoader cl = Thread.currentThread().getContextClassLoader();
                HostnameVerifier hnv = (HostnameVerifier) Proxy.newProxyInstance(
                        cl, new Class[]{HostnameVerifier.class}, sslHandler);
                httpsConn.setHostnameVerifier(hnv);
            }

            conn.setRequestMethod(method);
            conn.setDoInput(true);

            Object headersObj = req.get("headers");
            if (headersObj instanceof Map) {
                Map headers = (Map) headersObj;
                for (Iterator it = headers.keySet().iterator(); it.hasNext(); ) {
                    Object k = it.next();
                    Object v = headers.get(k);
                    if (k != null && v != null) conn.setRequestProperty(String.valueOf(k), String.valueOf(v));
                }
            }

            Object bodyObj = req.get("body");
            if (bodyObj != null && ("POST".equals(method) || "PUT".equals(method) || "PATCH".equals(method))) {
                conn.setDoOutput(true);
                byte[] body = String.valueOf(bodyObj).getBytes(charset);
                conn.setFixedLengthStreamingMode(body.length);
                os = conn.getOutputStream();
                os.write(body);
                os.flush();
            }

            int     status  = conn.getResponseCode();
            is = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
            HashMap bodyMap = readBody(is, charset, maxBodyBytes);

            HashMap resp = new HashMap();
            resp.put("status",     Integer.valueOf(status));
            resp.put("body",       bodyMap.get("body"));
            resp.put("bodyLength", bodyMap.get("bodyLength"));
            resp.put("truncated",  bodyMap.get("truncated"));
            resp.put("headers",    conn.getHeaderFields());
            return resp;
        } finally {
            closeQuietly(os);
            closeQuietly(is);
            if (conn != null) conn.disconnect();
        }
    }

    // ==================== TCP 执行 ====================

    static HashMap execTcp(Map target, Map req) throws Exception {
        String host = stringVal(target.get("host"));
        if (host.length() == 0) throw new IllegalArgumentException("TCP target requires host");

        int port = intVal(target.get("port"), -1);
        if (port <= 0 || port > 65535) throw new IllegalArgumentException("TCP target port is invalid: " + target.get("port"));

        int    timeout      = timeoutVal(req.get("timeout"));
        String charset      = charsetVal(req.get("charset"));
        int    maxBodyBytes = maxBodyBytesVal(req.get("maxBodyBytes"));

        Socket       socket = null;
        OutputStream os     = null;
        InputStream  is     = null;
        try {
            socket = new Socket();
            socket.connect(new InetSocketAddress(host, port), timeout);
            socket.setSoTimeout(timeout);

            Object tcpBody = req.get("body");
            if (tcpBody != null) {
                os = socket.getOutputStream();
                os.write(String.valueOf(tcpBody).getBytes(charset));
                os.flush();
            }

            is = socket.getInputStream();
            HashMap bodyMap = readTcpBody(is, charset, maxBodyBytes);

            HashMap resp = new HashMap();
            resp.put("raw",        bodyMap.get("body"));
            resp.put("bytes",      bodyMap.get("bytes"));
            resp.put("bodyLength", bodyMap.get("bodyLength"));
            resp.put("truncated",  bodyMap.get("truncated"));
            return resp;
        } finally {
            closeQuietly(os);
            closeQuietly(is);
            closeQuietly(socket);
        }
    }

    // ==================== JavaScript 脚本引擎 ====================

    static boolean evalJs(List resp, String script) throws Exception {
        ScriptEngine engine = findScriptEngine();
        engine.put("resp", resp);
        if (resp.size() == 1 && resp.get(0) instanceof Map) {
            Map m = (Map) resp.get(0);
            for (Iterator it = m.keySet().iterator(); it.hasNext(); ) {
                Object k = it.next();
                engine.put(String.valueOf(k), m.get(k));
            }
        }
        Object r = engine.eval(script);
        if (r instanceof Boolean) return ((Boolean) r).booleanValue();
        if (r instanceof Number)  return ((Number)  r).intValue() != 0;
        return "true".equalsIgnoreCase(String.valueOf(r));
    }

    private static ScriptEngine findScriptEngine() {
        ScriptEngineManager mgr = new ScriptEngineManager();
        String[] names = new String[]{"js", "nashorn", "graal.js", "JavaScript", "ecmascript"};
        for (int i = 0; i < names.length; i++) {
            ScriptEngine e = mgr.getEngineByName(names[i]);
            if (e != null) return e;
        }
        String ver = System.getProperty("java.version", "unknown");
        throw new IllegalStateException(
                "No JavaScript engine available (Java " + ver + "). "
                + "Nashorn was removed in Java 15+. "
                + "Add GraalJS (org.graalvm.js:js-scriptengine) to classpath.");
    }

    // ==================== SSL ====================

    private static SSLSocketFactory buildTrustAllFactory(InvocationHandler handler) throws Exception {
        SSLSocketFactory f = trustAllFactory;
        if (f != null) return f;
        synchronized (FingerprintComponent.class) {
            if (trustAllFactory == null) {
                ClassLoader cl = Thread.currentThread().getContextClassLoader();
                TrustManager tm = (TrustManager) Proxy.newProxyInstance(
                        cl, new Class[]{X509TrustManager.class}, handler);
                SSLContext ctx = SSLContext.getInstance("TLS");
                ctx.init(null, new TrustManager[]{tm}, new SecureRandom());
                trustAllFactory = ctx.getSocketFactory();
            }
            return trustAllFactory;
        }
    }

    // ==================== I/O ====================

    private static HashMap readBody(InputStream is, String charset, int maxBytes) throws Exception {
        HashMap m = readBytes(is, maxBytes, false);
        m.put("body", new String((byte[]) m.get("bytes"), charset));
        m.remove("bytes");
        return m;
    }

    private static HashMap readTcpBody(InputStream is, String charset, int maxBytes) throws Exception {
        HashMap m = readBytes(is, maxBytes, true);
        m.put("body", new String((byte[]) m.get("bytes"), charset));
        return m;
    }

    private static HashMap readBytes(InputStream is, int maxBytes, boolean tcpMode) throws Exception {
        ByteArrayOutputStream baos      = new ByteArrayOutputStream();
        boolean               truncated = false;
        if (is != null) {
            byte[] buf = new byte[4096];
            while (true) {
                int len;
                try { len = is.read(buf); }
                catch (SocketTimeoutException e) {
                    if (tcpMode && baos.size() > 0) break;
                    throw e;
                }
                if (len < 0) break;
                if (len == 0) continue;
                int allowed = maxBytes - baos.size();
                if (allowed <= 0) { truncated = true; break; }
                if (len > allowed) { baos.write(buf, 0, allowed); truncated = true; break; }
                baos.write(buf, 0, len);
            }
        }
        byte[]  bytes = baos.toByteArray();
        HashMap m     = new HashMap();
        m.put("bytes",      bytes);
        m.put("bodyLength", Integer.valueOf(bytes.length));
        m.put("truncated",  Boolean.valueOf(truncated));
        return m;
    }

    // ==================== 任务清理 ====================

    private void cleanupStoppedTasks() {
        long now = System.currentTimeMillis();
        for (Iterator it = tasks.keySet().iterator(); it.hasNext(); ) {
            Object id   = it.next();
            Map    task = (Map) tasks.get(id);
            if (task == null || !STATE_STOPPED.equals(task.get("status"))) continue;
            long finishedAt = longVal(task.get("finishedAt"), 0L);
            if (finishedAt > 0L && now - finishedAt > STOPPED_TASK_TTL_MILLIS) {
                tasks.remove(id);
                taskLocks.remove(id);
            }
        }
    }

    // ==================== 工具方法 ====================

    private static HashMap toHashMap(Object obj) {
        if (obj instanceof HashMap) return (HashMap) obj;
        if (obj instanceof Map)     return new HashMap((Map) obj);
        throw new ClassCastException("Expected Map but got: "
                + (obj == null ? "null" : obj.getClass().getName()));
    }

    private HashMap requireTask(Object id) {
        HashMap task = (HashMap) tasks.get(id);
        if (task == null) throw new IllegalArgumentException("Fingerprint task not found: " + id);
        return task;
    }

    private static List requireList(Object value, String name) {
        if (!(value instanceof List)) throw new IllegalArgumentException(name + " must be a list");
        return (List) value;
    }

    private static Map requireMap(Object value, String name) {
        if (!(value instanceof Map)) throw new IllegalArgumentException(name + " must be an object");
        return (Map) value;
    }

    private static String targetKey(Map t) {
        if (isTcpTarget(t)) return stringVal(t.get("host")) + ":" + stringVal(t.get("port"));
        return stringVal(t.get("baseUrl"));
    }

    private static boolean isTcpTarget(Map t) {
        return "tcp".equalsIgnoreCase(stringVal(t.get("protocol")));
    }

    private static String buildUrl(String baseUrl, String uri) {
        String lower = uri.toLowerCase(Locale.ENGLISH);
        if (lower.startsWith("http://") || lower.startsWith("https://")) return uri;
        if (baseUrl.endsWith("/")  && uri.startsWith("/"))  return baseUrl + uri.substring(1);
        if (!baseUrl.endsWith("/") && !uri.startsWith("/")) return baseUrl + "/" + uri;
        return baseUrl + uri;
    }

    private static int timeoutVal(Object v) {
        int t = intVal(v, DEFAULT_TIMEOUT);
        return t > 0 ? t : DEFAULT_TIMEOUT;
    }

    private static int maxBodyBytesVal(Object v) {
        int m = intVal(v, DEFAULT_MAX_BODY_BYTES);
        return m > 0 ? m : DEFAULT_MAX_BODY_BYTES;
    }

    private static int intVal(Object v, int def) {
        if (v instanceof Number) return ((Number) v).intValue();
        if (v != null) { try { return Integer.parseInt(String.valueOf(v)); } catch (Exception ignored) {} }
        return def;
    }

    private static long longVal(Object v, long def) {
        if (v instanceof Number) return ((Number) v).longValue();
        if (v != null) { try { return Long.parseLong(String.valueOf(v)); } catch (Exception ignored) {} }
        return def;
    }

    private static String stringVal(Object v) {
        return v == null ? "" : String.valueOf(v);
    }

    private static String charsetVal(Object v) {
        String s = stringVal(v).trim();
        return s.length() == 0 ? "UTF-8" : s;
    }

    private static void closeQuietly(Object c) {
        if (c == null) return;
        try {
            if (c instanceof InputStream)       ((InputStream)  c).close();
            else if (c instanceof OutputStream) ((OutputStream) c).close();
            else if (c instanceof Socket)       ((Socket)       c).close();
        } catch (Exception ignored) {}
    }
}
