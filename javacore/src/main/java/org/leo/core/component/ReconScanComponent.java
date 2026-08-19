package org.leo.core.component;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
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
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 侦察扫描组件（Recon Scan）
 * <p>
 * 以"目标优先"视角批量执行指纹探测：
 * 输入一批目标 + 规则选择器（协议/标签/指纹ID），组件自动对所有匹配
 * (target × rule) 组合并发执行，返回二维结果 {targetKey → {fingerprintId → Boolean}}。
 * <p>
 * 复用 FingerprintComponent 中的 execHttp / execTcp / evalJs 静态方法，
 * 不重复 I/O 实现。
 * <p>
 * 遵循 COMPONENT_GUIDE.md：Java 1.6 语法，无 lambda/匿名内部类/diamond。
 *
 * @author LeoSpring
 * @version 1.0
 */
public class ReconScanComponent implements Runnable, InvocationHandler, ThreadFactory {

    private static final AtomicInteger THREAD_SEQUENCE = new AtomicInteger();

    private HashMap<String, Object> params;
    private HashMap<String, Object> results;

    private static final String STATE_RUNNING = "RUNNING";
    private static final String STATE_PAUSED  = "PAUSED";
    private static final String STATE_STOPPED = "STOPPED";

    private static final int DEFAULT_THREADS      = 10;
    private static final int MAX_THREADS          = 128;
    private static final int MAX_TASKS            = 64;
    private static final int MAX_TARGETS          = 4096;
    private static final int MAX_RULES            = 256;
    private static final int MAX_WORK_ITEMS       = 65536;
    private static final int DEFAULT_TIMEOUT      = 3000;
    private static final int DEFAULT_MAX_BODY_BYTES = 1024 * 1024;
    private static final int MAX_TIMEOUT          = 300000;
    private static final int MAX_BODY_BYTES       = 10 * 1024 * 1024;
    private static final long STOPPED_TASK_TTL_MILLIS = 30L * 60L * 1000L;

    /** taskId → task HashMap */
    private static final ConcurrentHashMap tasks     = new ConcurrentHashMap();
    /** taskId → Object (monitor) */
    private static final ConcurrentHashMap taskLocks = new ConcurrentHashMap();
    private static volatile SSLSocketFactory trustAllFactory;

    // ── worker 字段 ──────────────────────────────────────────────────────────
    private String taskId;
    private Map    target;
    private Map    rule;
    private String threadSeed;



    public ReconScanComponent() {
    }

    /** worker 构造器 */
    private ReconScanComponent(String taskId, Map target, Map rule) {
        this.taskId = taskId;
        this.target = target;
        this.rule   = rule;
    }

    // ==================== 组件入口（Runnable.run 双用途） ====================

    public void run() {
        // ── 主控模式（框架反射调用） ──
        if (taskId == null) {
            java.lang.reflect.InvocationHandler h =
                    (java.lang.reflect.InvocationHandler)
                            Thread.currentThread().getContextClassLoader();
            try {
                params  = (HashMap) h.invoke(null, null, null);
                results = new HashMap();
                invoke();
            } catch (Throwable t) {
                if (results == null) results = new HashMap();
                results.put("code", Integer.valueOf(500));
                results.put("msg", t.getMessage());
            }
            if (results != null) {
                try { h.invoke(null, null, new Object[]{results}); } catch (Throwable ignored) {}
            }
            return;
        }

        // ── worker 模式（线程池调用） ──
        HashMap task = (HashMap) tasks.get(taskId);
        if (task == null || target == null || rule == null) {
            return;
        }

        String ruleId    = stringVal(rule.get("fingerprintId"));
        String targetKey = targetKey(target);

        // 确保 targetKey 在结果 map 中有槽位
        ConcurrentHashMap allResults = (ConcurrentHashMap) task.get("results");
        ConcurrentHashMap targetMap  = getOrCreateTargetMap(allResults, targetKey);

        try {
            if (!waitIfPaused(task)) {
                return;
            }

            Map     ruleBody = (Map)  rule.get("rule");
            List    requests = (List) ruleBody.get("requests");
            String  script   = stringVal(ruleBody.get("script"));

            List resp           = new ArrayList();
            List requestErrors  = new ArrayList();

            for (int i = 0; i < requests.size(); i++) {
                if (!waitIfPaused(task)) {
                    return;
                }
                Map req = (Map) requests.get(i);
                try {
                    if (isTcpTarget(target)) {
                        resp.add(execTcp(target, req));
                    } else {
                        resp.add(execHttp(target, req));
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
                hit = evalJs(resp, script);
            } catch (Exception e) {
                HashMap err = new HashMap();
                err.put("stage",     "script");
                err.put("errorType", e.getClass().getName());
                err.put("error",     e.getMessage());
                requestErrors.add(err);
            }

            targetMap.put(ruleId, Boolean.valueOf(hit));

            if (!requestErrors.isEmpty()) {
                ConcurrentHashMap allErrors = (ConcurrentHashMap) task.get("errors");
                allErrors.put(targetKey + "|" + ruleId, requestErrors);
            }

        } finally {
            markCompleted(task);
        }
    }

    // ==================== 独立 I/O 实现 ====================

    /**
     * SSL 动态代理回调。Recon payload 必须自包含，不能直接引用另一个
     * Component；远端每次只加载并重命名一个 class。
     */
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        String name = method.getName();
        if ("verify".equals(name)) return Boolean.TRUE;
        if ("getAcceptedIssuers".equals(name)) return new X509Certificate[0];
        return null;
    }

    private static HashMap execHttp(Map target, Map req) throws Exception {
        String baseUrl = stringVal(target.get("baseUrl"));
        if (baseUrl.length() == 0) throw new IllegalArgumentException("HTTP target requires baseUrl");

        String uri = stringVal(req.get("uri"));
        if (uri.length() == 0) uri = stringVal(req.get("path"));
        if (uri.length() == 0) uri = "/";
        String method = stringVal(req.get("method")).toUpperCase(Locale.ENGLISH);
        if (method.length() == 0) method = "GET";
        int timeout = timeoutVal(req.get("timeout"));
        String charset = charsetVal(req.get("charset"));
        int maxBodyBytes = maxBodyBytesVal(req.get("maxBodyBytes"));

        HttpURLConnection conn = null;
        OutputStream os = null;
        InputStream is = null;
        try {
            conn = (HttpURLConnection) new URL(buildUrl(baseUrl, uri)).openConnection();
            conn.setConnectTimeout(timeout);
            conn.setReadTimeout(timeout);
            conn.setUseCaches(false);
            conn.setInstanceFollowRedirects(false);
            if (conn instanceof HttpsURLConnection) {
                HttpsURLConnection https = (HttpsURLConnection) conn;
                ReconScanComponent handler = new ReconScanComponent();
                https.setSSLSocketFactory(buildTrustAllFactory(handler));
                ClassLoader cl = Thread.currentThread().getContextClassLoader();
                HostnameVerifier verifier = (HostnameVerifier) Proxy.newProxyInstance(
                        cl, new Class[]{HostnameVerifier.class}, handler);
                https.setHostnameVerifier(verifier);
            }
            conn.setRequestMethod(method);
            conn.setDoInput(true);

            Object headersObj = req.get("headers");
            if (headersObj instanceof Map) {
                Map headers = (Map) headersObj;
                for (Iterator it = headers.keySet().iterator(); it.hasNext(); ) {
                    Object key = it.next();
                    Object value = headers.get(key);
                    if (key != null && value != null) {
                        conn.setRequestProperty(String.valueOf(key), String.valueOf(value));
                    }
                }
            }

            Object bodyObj = req.get("body");
            if (bodyObj != null && ("POST".equals(method) || "PUT".equals(method)
                    || "PATCH".equals(method))) {
                byte[] body = String.valueOf(bodyObj).getBytes(charset);
                conn.setDoOutput(true);
                conn.setFixedLengthStreamingMode(body.length);
                os = conn.getOutputStream();
                os.write(body);
                os.flush();
            }

            int status = conn.getResponseCode();
            is = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
            HashMap bodyMap = readBody(is, charset, maxBodyBytes);
            HashMap response = new HashMap();
            response.put("status", Integer.valueOf(status));
            response.put("body", bodyMap.get("body"));
            response.put("bodyLength", bodyMap.get("bodyLength"));
            response.put("truncated", bodyMap.get("truncated"));
            response.put("headers", conn.getHeaderFields());
            return response;
        } finally {
            closeQuietly(os);
            closeQuietly(is);
            if (conn != null) conn.disconnect();
        }
    }

    private static HashMap execTcp(Map target, Map req) throws Exception {
        String host = stringVal(target.get("host"));
        if (host.length() == 0) throw new IllegalArgumentException("TCP target requires host");
        int port = intVal(target.get("port"), -1);
        if (port <= 0 || port > 65535) {
            throw new IllegalArgumentException("TCP target port is invalid: " + target.get("port"));
        }
        int timeout = timeoutVal(req.get("timeout"));
        String charset = charsetVal(req.get("charset"));
        int maxBodyBytes = maxBodyBytesVal(req.get("maxBodyBytes"));

        Socket socket = null;
        OutputStream os = null;
        InputStream is = null;
        try {
            socket = new Socket();
            socket.connect(new InetSocketAddress(host, port), timeout);
            socket.setSoTimeout(timeout);
            Object bodyObj = req.get("body");
            if (bodyObj != null) {
                os = socket.getOutputStream();
                os.write(String.valueOf(bodyObj).getBytes(charset));
                os.flush();
            }
            is = socket.getInputStream();
            HashMap bodyMap = readBytes(is, maxBodyBytes, true);
            byte[] bytes = (byte[]) bodyMap.get("bytes");
            HashMap response = new HashMap();
            response.put("raw", new String(bytes, charset));
            response.put("bytes", bytes);
            response.put("bodyLength", bodyMap.get("bodyLength"));
            response.put("truncated", bodyMap.get("truncated"));
            return response;
        } finally {
            closeQuietly(os);
            closeQuietly(is);
            closeQuietly(socket);
        }
    }

    private static boolean evalJs(List responses, String script) throws Exception {
        ScriptEngineManager manager = new ScriptEngineManager();
        String[] names = new String[]{"js", "nashorn", "graal.js", "JavaScript", "ecmascript"};
        ScriptEngine engine = null;
        for (int i = 0; i < names.length; i++) {
            engine = manager.getEngineByName(names[i]);
            if (engine != null) break;
        }
        if (engine == null) {
            throw new IllegalStateException("No JavaScript engine available (Java "
                    + System.getProperty("java.version", "unknown") + ")");
        }
        engine.put("resp", responses);
        if (responses.size() == 1 && responses.get(0) instanceof Map) {
            Map first = (Map) responses.get(0);
            for (Iterator it = first.keySet().iterator(); it.hasNext(); ) {
                Object key = it.next();
                engine.put(String.valueOf(key), first.get(key));
            }
        }
        Object value = engine.eval(script);
        if (value instanceof Boolean) return ((Boolean) value).booleanValue();
        if (value instanceof Number) return ((Number) value).intValue() != 0;
        return "true".equalsIgnoreCase(String.valueOf(value));
    }

    private static SSLSocketFactory buildTrustAllFactory(InvocationHandler handler) throws Exception {
        SSLSocketFactory current = trustAllFactory;
        if (current != null) return current;
        synchronized (ReconScanComponent.class) {
            if (trustAllFactory == null) {
                ClassLoader cl = Thread.currentThread().getContextClassLoader();
                TrustManager manager = (TrustManager) Proxy.newProxyInstance(
                        cl, new Class[]{X509TrustManager.class}, handler);
                SSLContext context = SSLContext.getInstance("TLS");
                context.init(null, new TrustManager[]{manager}, new SecureRandom());
                trustAllFactory = context.getSocketFactory();
            }
            return trustAllFactory;
        }
    }

    private static HashMap readBody(InputStream in, String charset, int maxBytes) throws Exception {
        HashMap value = readBytes(in, maxBytes, false);
        value.put("body", new String((byte[]) value.get("bytes"), charset));
        value.remove("bytes");
        return value;
    }

    private static HashMap readBytes(InputStream in, int maxBytes, boolean tcpMode) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        boolean truncated = false;
        if (in != null) {
            byte[] buffer = new byte[4096];
            while (true) {
                int len;
                try {
                    len = in.read(buffer);
                } catch (SocketTimeoutException e) {
                    if (tcpMode && out.size() > 0) break;
                    throw e;
                }
                if (len < 0) break;
                if (len == 0) continue;
                int allowed = maxBytes - out.size();
                if (allowed <= 0) {
                    truncated = true;
                    break;
                }
                if (len > allowed) {
                    out.write(buffer, 0, allowed);
                    truncated = true;
                    break;
                }
                out.write(buffer, 0, len);
            }
        }
        byte[] bytes = out.toByteArray();
        HashMap result = new HashMap();
        result.put("bytes", bytes);
        result.put("bodyLength", Integer.valueOf(bytes.length));
        result.put("truncated", Boolean.valueOf(truncated));
        return result;
    }

    private static String buildUrl(String baseUrl, String uri) {
        String lower = uri.toLowerCase(Locale.ENGLISH);
        if (lower.startsWith("http://") || lower.startsWith("https://")) return uri;
        if (baseUrl.endsWith("/") && uri.startsWith("/")) return baseUrl + uri.substring(1);
        if (!baseUrl.endsWith("/") && !uri.startsWith("/")) return baseUrl + "/" + uri;
        return baseUrl + uri;
    }

    private static int timeoutVal(Object value) {
        int timeout = intVal(value, DEFAULT_TIMEOUT);
        if (timeout <= 0) return DEFAULT_TIMEOUT;
        return timeout > MAX_TIMEOUT ? MAX_TIMEOUT : timeout;
    }

    private static int maxBodyBytesVal(Object value) {
        int max = intVal(value, DEFAULT_MAX_BODY_BYTES);
        if (max <= 0) return DEFAULT_MAX_BODY_BYTES;
        return max > MAX_BODY_BYTES ? MAX_BODY_BYTES : max;
    }

    private static String charsetVal(Object value) {
        String charset = stringVal(value).trim();
        return charset.length() == 0 ? "UTF-8" : charset;
    }

    private static void closeQuietly(Object closeable) {
        if (closeable == null) return;
        try {
            if (closeable instanceof InputStream) ((InputStream) closeable).close();
            else if (closeable instanceof OutputStream) ((OutputStream) closeable).close();
            else if (closeable instanceof Socket) ((Socket) closeable).close();
        } catch (Exception ignored) {}
    }

    // ==================== 方法调度 ====================

    private void invoke() throws Exception {
        String method = stringVal(params.get("methodName"));
        if ("startScan".equals(method)) {
            String id = startScan(params);
            results.put("taskId", id);
            results.put("code", Integer.valueOf(200));
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
            throw new IllegalArgumentException("Unknown recon-scan method: " + method);
        }
    }

    // ==================== 扫描生命周期 ====================

    private String startScan(HashMap p) {
        cleanupStoppedTasks();
        if (tasks.size() >= MAX_TASKS) {
            throw new IllegalStateException("too many recon tasks, max=" + MAX_TASKS);
        }

        List targets = requireList(p.get("targets"), "targets");
        List rules   = requireList(p.get("rules"),   "rules");

        if (targets.isEmpty()) throw new IllegalArgumentException("targets cannot be empty");
        if (rules.isEmpty())   throw new IllegalArgumentException("rules cannot be empty");
        if (targets.size() > MAX_TARGETS) {
            throw new IllegalArgumentException("too many targets, max=" + MAX_TARGETS);
        }
        if (rules.size() > MAX_RULES) {
            throw new IllegalArgumentException("too many rules, max=" + MAX_RULES);
        }

        int threads = intVal(p.get("threads"), DEFAULT_THREADS);
        if (threads < 1)           threads = 1;
        if (threads > MAX_THREADS) threads = MAX_THREADS;

        // 构建工作项：(target × rule) 按协议匹配过滤
        List workItems = new ArrayList();
        for (int ti = 0; ti < targets.size(); ti++) {
            Object tObj = targets.get(ti);
            if (!(tObj instanceof Map)) continue;
            Map t = (Map) tObj;
            String tProto = stringVal(t.get("protocol")).toLowerCase();

            for (int ri = 0; ri < rules.size(); ri++) {
                Object rObj = rules.get(ri);
                if (!(rObj instanceof Map)) continue;
                Map r = (Map) rObj;
                String rProto = stringVal(r.get("protocol")).toLowerCase();

                // 只有协议匹配的组合才执行
                if (!tProto.equals(rProto)) continue;
                if (workItems.size() >= MAX_WORK_ITEMS) {
                    throw new IllegalArgumentException("too many work items, max=" + MAX_WORK_ITEMS);
                }

                // 工作项：{target, rule}
                HashMap item = new HashMap();
                item.put("target", t);
                item.put("rule",   r);
                workItems.add(item);
            }
        }

        if (workItems.isEmpty()) {
            throw new IllegalArgumentException(
                "No matching (target, rule) pairs — check target.protocol vs rule.protocol");
        }

        if (threads > workItems.size()) {
            threads = workItems.size();
        }

        String id = UUID.randomUUID().toString();
        threadSeed = stringVal(p.get("hostId")) + "|" + id;
        HashMap task = new HashMap();
        task.put("taskId",     id);
        task.put("status",     STATE_RUNNING);
        task.put("total",      Integer.valueOf(workItems.size()));
        task.put("targetCount", Integer.valueOf(targets.size()));
        task.put("ruleCount",   Integer.valueOf(rules.size()));
        task.put("results",    new ConcurrentHashMap());  // {targetKey → ConcurrentHashMap{ruleId → Boolean}}
        task.put("errors",     new ConcurrentHashMap());  // {targetKey|ruleId → List}
        task.put("completed",  new AtomicInteger(0));
        task.put("createdAt",  Long.valueOf(System.currentTimeMillis()));

        Object lock = new Object();
        tasks.put(id, task);
        taskLocks.put(id, lock);

        ExecutorService pool = null;
        try {
            pool = Executors.newFixedThreadPool(threads, this);
            task.put("executor", pool);
            for (int i = 0; i < workItems.size(); i++) {
                HashMap item = (HashMap) workItems.get(i);
                Map t = (Map) item.get("target");
                Map r = (Map) item.get("rule");
                pool.execute(new ReconScanComponent(id, t, r));
            }
        } catch (RuntimeException e) {
            if (pool != null) pool.shutdownNow();
            tasks.remove(id);
            taskLocks.remove(id);
            throw e;
        }
        pool.shutdown();

        return id;
    }

    public Thread newThread(Runnable task) {
        Thread thread = new Thread(task, workerThreadName(threadSeed));
        thread.setDaemon(true);
        return thread;
    }

    private static String workerThreadName(String seed) {
        return "worker-" + Integer.toHexString(String.valueOf(seed).hashCode()) + "-"
                + THREAD_SEQUENCE.incrementAndGet();
    }

    // ==================== 暂停/恢复/停止 ====================

    private boolean waitIfPaused(HashMap task) {
        Object lock = taskLocks.get(taskId);
        if (lock == null) return false;

        synchronized (lock) {
            while (STATE_PAUSED.equals(task.get("status"))) {
                if (Thread.currentThread().isInterrupted()) return false;
                try {
                    lock.wait(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            return !STATE_STOPPED.equals(task.get("status"))
                    && !Thread.currentThread().isInterrupted();
        }
    }

    private void pause(Object id) {
        HashMap task = requireTask(id);
        Object  lock = taskLocks.get(id);
        if (lock == null) throw new IllegalStateException("Recon task lock not found: " + id);
        synchronized (lock) {
            if (STATE_STOPPED.equals(task.get("status")))
                throw new IllegalStateException("Recon task already stopped: " + id);
            task.put("status", STATE_PAUSED);
        }
    }

    private void resume(Object id) {
        HashMap task = requireTask(id);
        Object  lock = taskLocks.get(id);
        if (lock == null) throw new IllegalStateException("Recon task lock not found: " + id);
        synchronized (lock) {
            if (STATE_STOPPED.equals(task.get("status")))
                throw new IllegalStateException("Recon task already stopped: " + id);
            task.put("status", STATE_RUNNING);
            lock.notifyAll();
        }
    }

    private void stop(Object id) {
        HashMap task = requireTask(id);
        finishTask(task, true);

        Object executor = task.get("executor");
        if (executor instanceof ExecutorService) {
            ((ExecutorService) executor).shutdownNow();
        }
        task.remove("executor");
    }

    private void markCompleted(HashMap task) {
        AtomicInteger completed = (AtomicInteger) task.get("completed");
        int done  = completed != null ? completed.incrementAndGet() : 0;
        int total = intVal(task.get("total"), 0);
        if (total > 0 && done >= total) {
            finishTask(task, false);
            task.remove("executor");
        }
    }

    private void finishTask(HashMap task, boolean refreshFinishedAt) {
        Object monitor = taskLocks.get(task.get("taskId"));
        if (monitor == null) monitor = task;
        synchronized (monitor) {
            task.put("status", STATE_STOPPED);
            if (refreshFinishedAt || task.get("finishedAt") == null) {
                task.put("finishedAt", Long.valueOf(System.currentTimeMillis()));
            }
            monitor.notifyAll();
        }
    }

    // ==================== 查询 ====================

    private void queryResult(Object id) {
        cleanupStoppedTasks();

        HashMap task = (HashMap) tasks.get(id);
        if (task == null) {
            results.put("code", Integer.valueOf(404));
            results.put("msg", "Recon task not found: " + id);
            return;
        }

        AtomicInteger completed = (AtomicInteger) task.get("completed");

        // 从二维 results 构建 matched：{targetKey → [ruleId,...]}（值为 true 的规则）
        ConcurrentHashMap allResults = (ConcurrentHashMap) task.get("results");
        HashMap matched = new HashMap();
        for (Iterator it = ((Map) allResults).keySet().iterator(); it.hasNext(); ) {
            Object targetKey = it.next();
            Object targetVal = allResults.get(targetKey);
            if (!(targetVal instanceof Map)) continue;
            Map targetMap = (Map) targetVal;
            List hitRules = new ArrayList();
            for (Iterator it2 = targetMap.keySet().iterator(); it2.hasNext(); ) {
                Object ruleId  = it2.next();
                Object hitVal  = targetMap.get(ruleId);
                if (Boolean.TRUE.equals(hitVal)) {
                    hitRules.add(ruleId);
                }
            }
            matched.put(targetKey, hitRules);
        }

        HashMap simple = new HashMap();
        simple.put("taskId",      task.get("taskId"));
        simple.put("status",      task.get("status"));
        simple.put("total",       task.get("total"));
        simple.put("targetCount", task.get("targetCount"));
        simple.put("ruleCount",   task.get("ruleCount"));
        simple.put("completed",   Integer.valueOf(completed != null ? completed.get() : 0));
        simple.put("results",     allResults);
        simple.put("matched",     matched);
        simple.put("errors",      task.get("errors"));
        simple.put("createdAt",   task.get("createdAt"));
        simple.put("finishedAt",  task.get("finishedAt"));

        results.put("result", simple);
        results.put("code", Integer.valueOf(200));
    }

    // ==================== 辅助 ====================

    /** 获取或创建某目标的结果子 map */
    private static ConcurrentHashMap getOrCreateTargetMap(ConcurrentHashMap allResults, String targetKey) {
        ConcurrentHashMap existing = (ConcurrentHashMap) allResults.get(targetKey);
        if (existing != null) return existing;
        ConcurrentHashMap newMap = new ConcurrentHashMap();
        ConcurrentHashMap prev   = (ConcurrentHashMap) allResults.putIfAbsent(targetKey, newMap);
        return prev != null ? prev : newMap;
    }

    private static String targetKey(Map t) {
        if (isTcpTarget(t)) {
            return stringVal(t.get("host")) + ":" + stringVal(t.get("port"));
        }
        return stringVal(t.get("baseUrl"));
    }

    private static boolean isTcpTarget(Map t) {
        return "tcp".equalsIgnoreCase(stringVal(t.get("protocol")));
    }

    private void cleanupStoppedTasks() {
        long now = System.currentTimeMillis();
        for (Iterator it = ((Map) tasks).keySet().iterator(); it.hasNext(); ) {
            Object id   = it.next();
            Map    task = (Map) tasks.get(id);
            if (task == null || !STATE_STOPPED.equals(task.get("status"))) continue;
            Object finishedAt = task.get("finishedAt");
            if (finishedAt instanceof Number) {
                long t = ((Number) finishedAt).longValue();
                if (t > 0L && now - t > STOPPED_TASK_TTL_MILLIS) {
                    tasks.remove(id);
                    taskLocks.remove(id);
                }
            }
        }
    }

    private HashMap requireTask(Object id) {
        HashMap task = (HashMap) tasks.get(id);
        if (task == null) throw new IllegalArgumentException("Recon task not found: " + id);
        return task;
    }

    private List requireList(Object value, String name) {
        if (!(value instanceof List))
            throw new IllegalArgumentException(name + " must be a list");
        return (List) value;
    }

    private static int intVal(Object value, int def) {
        if (value instanceof Number) return ((Number) value).intValue();
        if (value != null) {
            try { return Integer.parseInt(String.valueOf(value)); } catch (Exception ignored) {}
        }
        return def;
    }

    private static String stringVal(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
