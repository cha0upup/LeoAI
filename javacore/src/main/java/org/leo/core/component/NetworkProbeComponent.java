package org.leo.core.component;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * 统一节点侧网络探测原子运行时。
 *
 * <p>该类必须保持单文件、单 class、Java 6 API 兼容；它会被独立编译后
 * 作为 payload 下发到 Puppet JVM。上层只提交声明式 plan，组件返回统一
 * observation，不执行脚本或加载其他组件。</p>
 */
public class NetworkProbeComponent implements Runnable, ThreadFactory,
        java.lang.reflect.InvocationHandler {

    private static final int MAX_TASKS = 32;
    private static final int MAX_TARGETS = 128;
    private static final int MAX_THREADS = 64;
    private static final int MAX_STAGES = 8;
    private static final int MAX_TIMEOUT_MS = 300000;
    private static final int MAX_READ_BYTES = 8192;
    private static final int MAX_EVIDENCE_CHARS = 4096;
    private static final int MAX_HEADERS = 32;
    private static final int MAX_REQUEST_CHARS = 8192;
    private static final long TASK_TTL_MS = 30L * 60L * 1000L;
    private static final AtomicInteger THREAD_SEQUENCE = new AtomicInteger();
    private static final Map TASKS = new ConcurrentHashMap();
    private static final Map TASK_LOCKS = new ConcurrentHashMap();
    private static volatile SSLSocketFactory TRUST_ALL_FACTORY;

    private HashMap params;
    private HashMap results;
    private String taskId;
    private Map target;
    private Map plan;
    private boolean workerMode;
    private String threadSeed;

    public NetworkProbeComponent() {
    }

    private NetworkProbeComponent(String taskId, Map target, Map plan) {
        this.taskId = taskId;
        this.target = target;
        this.plan = plan;
        this.workerMode = true;
    }

    public void run() {
        if (workerMode) {
            runWorker();
            return;
        }

        java.lang.reflect.InvocationHandler handler =
                (java.lang.reflect.InvocationHandler) Thread.currentThread().getContextClassLoader();
        try {
            params = (HashMap) handler.invoke(null, null, null);
            results = new HashMap();
            invoke();
        } catch (Throwable error) {
            if (results == null) results = new HashMap();
            results.put("code", Integer.valueOf(500));
            results.put("msg", messageOf(error));
        }
        try {
            handler.invoke(null, null, new Object[]{results});
        } catch (Throwable ignored) {
        }
    }

    public void invoke() throws Exception {
        String method = stringValue(params.get("methodName"));
        if ("cleanup".equals(method)) {
            results.put("removed", Integer.valueOf(cleanupExpiredTasks()));
            results.put("code", Integer.valueOf(200));
        } else {
            cleanupExpiredTasks();
            invokeNonCleanup(method);
        }
    }

    private void invokeNonCleanup(String method) throws Exception {
        if ("capabilities".equals(method)) {
            writeCapabilities();
        } else if ("startTask".equals(method)) {
            results.put("taskId", startTask(params));
            results.put("code", Integer.valueOf(200));
        } else if ("queryTask".equals(method)) {
            queryTask(stringValue(params.get("taskId")));
        } else if ("pauseTask".equals(method)) {
            updateTaskState(stringValue(params.get("taskId")), "PAUSED");
        } else if ("resumeTask".equals(method)) {
            updateTaskState(stringValue(params.get("taskId")), "RUNNING");
        } else if ("stopTask".equals(method)) {
            stopTask(stringValue(params.get("taskId")));
        } else {
            throw new IllegalArgumentException("Unknown network probe method: " + method);
        }
    }

    private void writeCapabilities() {
        results.put("code", Integer.valueOf(200));
        results.put("resultVersion", Integer.valueOf(1));
        results.put("component", "NetworkProbeComponent");
        results.put("transports", new ArrayList(Collections.singletonList("tcp")));
        ArrayList stages = new ArrayList();
        stages.add("tcp-connect");
        stages.add("tcp-exchange");
        stages.add("http-head");
        stages.add("http-request");
        stages.add("tls-handshake");
        results.put("stages", stages);
        results.put("maxTargets", Integer.valueOf(MAX_TARGETS));
        results.put("maxThreads", Integer.valueOf(MAX_THREADS));
        results.put("maxReadBytes", Integer.valueOf(MAX_READ_BYTES));
    }

    private String startTask(Map input) {
        if (TASKS.size() >= MAX_TASKS) {
            throw new IllegalStateException("too many network probe tasks, max=" + MAX_TASKS);
        }
        Map sourcePlan = asMap(input.get("plan"));
        if (sourcePlan == null) sourcePlan = input;
        List rawTargets = asList(sourcePlan.get("targets"));
        if (rawTargets == null || rawTargets.isEmpty()) {
            throw new IllegalArgumentException("plan.targets cannot be empty");
        }
        if (rawTargets.size() > MAX_TARGETS) {
            throw new IllegalArgumentException("too many targets, max=" + MAX_TARGETS);
        }

        Map limits = asMap(sourcePlan.get("limits"));
        int timeout = boundedInt(limits == null ? null : limits.get("timeout"), 3000, 100, MAX_TIMEOUT_MS);
        int maxRead = boundedInt(limits == null ? null : limits.get("maxReadBytes"), MAX_READ_BYTES, 256, MAX_READ_BYTES);
        int threads = boundedInt(limits == null ? null : limits.get("threads"), 8, 1, MAX_THREADS);
        if (threads > rawTargets.size()) threads = rawTargets.size();

        List stages = normalizeStages(sourcePlan.get("stages"));
        if (stages.isEmpty()) stages.add("tcp-connect");
        Map normalizedPlan = new HashMap();
        normalizedPlan.put("stages", stages);
        normalizedPlan.put("timeout", Integer.valueOf(timeout));
        normalizedPlan.put("maxReadBytes", Integer.valueOf(maxRead));
        normalizedPlan.put("threads", Integer.valueOf(threads));

        ArrayList normalizedTargets = new ArrayList();
        for (int i = 0; i < rawTargets.size(); i++) {
            normalizedTargets.add(normalizeTarget(rawTargets.get(i), i));
        }

        String id = UUID.randomUUID().toString();
        HashMap task = new HashMap();
        task.put("taskId", id);
        task.put("resultVersion", Integer.valueOf(1));
        task.put("scanKind", "network-probe");
        task.put("status", "RUNNING");
        task.put("total", Integer.valueOf(normalizedTargets.size()));
        task.put("completed", new AtomicInteger(0));
        task.put("targets", normalizedTargets);
        task.put("plan", normalizedPlan);
        task.put("observations", Collections.synchronizedList(new ArrayList()));
        task.put("errors", Collections.synchronizedList(new ArrayList()));
        task.put("createdAt", Long.valueOf(System.currentTimeMillis()));

        Object lock = new Object();
        TASKS.put(id, task);
        TASK_LOCKS.put(id, lock);
        threadSeed = stringValue(input.get("hostId")) + "|" + id;
        ExecutorService executor = null;
        try {
            executor = Executors.newFixedThreadPool(threads, this);
            task.put("executor", executor);
            for (int i = 0; i < normalizedTargets.size(); i++) {
                executor.execute(new NetworkProbeComponent(id,
                        (Map) normalizedTargets.get(i), normalizedPlan));
            }
        } catch (RuntimeException error) {
            if (executor != null) executor.shutdownNow();
            TASKS.remove(id);
            TASK_LOCKS.remove(id);
            throw error;
        }
        executor.shutdown();
        return id;
    }

    private Map normalizeTarget(Object value, int index) {
        Map source = asMap(value);
        if (source == null) throw new IllegalArgumentException("targets[" + index + "] must be an object");
        HashMap target = new HashMap();
        String protocol = stringValue(source.get("protocol")).toLowerCase(Locale.ENGLISH);
        String baseUrl = stringValue(source.get("baseUrl"));
        String host = stringValue(source.get("host"));
        int port = boundedInt(source.get("port"), -1, -1, 65535);
        URL parsedUrl = null;
        if (baseUrl.length() > 0) {
            try {
                parsedUrl = new URL(baseUrl);
                if (!("http".equalsIgnoreCase(parsedUrl.getProtocol())
                        || "https".equalsIgnoreCase(parsedUrl.getProtocol()))) {
                    throw new IllegalArgumentException("targets[" + index + "].baseUrl must use http or https");
                }
                if (parsedUrl.getUserInfo() != null) {
                    throw new IllegalArgumentException("targets[" + index + "].baseUrl cannot contain user info");
                }
                if (host.length() == 0) host = parsedUrl.getHost();
                if (port <= 0) port = parsedUrl.getPort() > 0 ? parsedUrl.getPort()
                        : ("https".equalsIgnoreCase(parsedUrl.getProtocol()) ? 443 : 80);
            } catch (Exception error) {
                if (error instanceof IllegalArgumentException) throw (IllegalArgumentException) error;
                throw new IllegalArgumentException("targets[" + index + "].baseUrl is invalid");
            }
            if (protocol.length() == 0) protocol = "https".equalsIgnoreCase(parsedUrl.getProtocol()) ? "https" : "http";
        }
        if (protocol.length() == 0) protocol = "tcp";
        if (!("tcp".equals(protocol) || "http".equals(protocol) || "https".equals(protocol))) {
            throw new IllegalArgumentException("targets[" + index + "].protocol is unsupported");
        }
        if (host.length() == 0) throw new IllegalArgumentException("targets[" + index + "] requires host or baseUrl");
        validateHost(host, index);
        if (port <= 0) {
            if ("https".equals(protocol)) port = 443;
            else if ("http".equals(protocol)) port = 80;
        }
        if (port <= 0 || port > 65535) throw new IllegalArgumentException("targets[" + index + "] port is invalid");
        target.put("host", host);
        target.put("port", Integer.valueOf(port));
        target.put("protocol", protocol);
        if (baseUrl.length() > 0) target.put("baseUrl", baseUrl);
        copyTargetMetadata(source, target, index);
        if (source.get("request") != null) {
            String request = stringValue(source.get("request"));
            if (request.length() > MAX_REQUEST_CHARS || request.indexOf('\0') >= 0) {
                throw new IllegalArgumentException("targets[" + index + "].request is invalid or too long");
            }
            target.put("request", request);
        }
        if (source.get("headers") != null) {
            if (!(source.get("headers") instanceof Map)) {
                throw new IllegalArgumentException("targets[" + index + "].headers must be an object");
            }
            Map sourceHeaders = (Map) source.get("headers");
            if (sourceHeaders.size() > MAX_HEADERS) {
                throw new IllegalArgumentException("targets[" + index + "].headers cannot exceed " + MAX_HEADERS);
            }
            HashMap headers = new HashMap();
            Iterator headerIterator = sourceHeaders.entrySet().iterator();
            while (headerIterator.hasNext()) {
                Map.Entry entry = (Map.Entry) headerIterator.next();
                String name = stringValue(entry.getKey());
                String headerValue = stringValue(entry.getValue());
                if (name.length() == 0 || name.indexOf('\r') >= 0 || name.indexOf('\n') >= 0
                        || headerValue.indexOf('\r') >= 0 || headerValue.indexOf('\n') >= 0) {
                    throw new IllegalArgumentException("targets[" + index + "].headers contains invalid characters");
                }
                headers.put(name, headerValue);
            }
            target.put("headers", headers);
        }
        if (source.get("httpRequest") != null) {
            target.put("httpRequest", normalizeHttpRequest(source.get("httpRequest"), index));
        }
        return target;
    }

    private void copyTargetMetadata(Map source, Map target, int index) {
        String targetId = stringValue(source.get("targetId"));
        String probeId = stringValue(source.get("probeId"));
        String ruleId = stringValue(source.get("ruleId"));
        int requestIndex = boundedInt(source.get("requestIndex"), -1, -1, 255);
        if (targetId.length() > 128 || probeId.length() > 128 || ruleId.length() > 128) {
            throw new IllegalArgumentException("targets[" + index + "] metadata is too long");
        }
        if (targetId.length() > 0) target.put("targetId", targetId);
        if (probeId.length() > 0) target.put("probeId", probeId);
        if (ruleId.length() > 0) target.put("ruleId", ruleId);
        if (requestIndex >= 0) target.put("requestIndex", Integer.valueOf(requestIndex));
        String stage = stringValue(source.get("stage")).toLowerCase(Locale.ENGLISH);
        if (stage.length() > 0) {
            if (!("tcp-connect".equals(stage) || "tcp-exchange".equals(stage)
                    || "http-head".equals(stage) || "http-request".equals(stage)
                    || "tls-handshake".equals(stage))) {
                throw new IllegalArgumentException("targets[" + index + "].stage is unsupported");
            }
            target.put("stage", stage);
        }
        if (source.get("timeout") != null) {
            target.put("timeout", Integer.valueOf(boundedInt(source.get("timeout"), 3000, 100, MAX_TIMEOUT_MS)));
        }
        if (source.get("maxReadBytes") != null) {
            target.put("maxReadBytes", Integer.valueOf(boundedInt(source.get("maxReadBytes"),
                    MAX_READ_BYTES, 256, MAX_READ_BYTES)));
        }
    }

    private Map normalizeHttpRequest(Object value, int index) {
        Map source = asMap(value);
        if (source == null) throw new IllegalArgumentException("targets[" + index + "].httpRequest must be an object");
        HashMap request = new HashMap();
        String method = stringValue(source.get("method")).toUpperCase(Locale.ENGLISH);
        if (method.length() == 0) method = "GET";
        if (!("GET".equals(method) || "HEAD".equals(method) || "POST".equals(method)
                || "PUT".equals(method) || "PATCH".equals(method) || "DELETE".equals(method)
                || "OPTIONS".equals(method))) {
            throw new IllegalArgumentException("targets[" + index + "].httpRequest.method is unsupported");
        }
        String path = stringValue(source.get("path"));
        if (path.length() == 0) path = stringValue(source.get("uri"));
        if (path.length() == 0) path = "/";
        if (path.length() > MAX_REQUEST_CHARS || path.indexOf('\r') >= 0 || path.indexOf('\n') >= 0) {
            throw new IllegalArgumentException("targets[" + index + "].httpRequest.path is invalid or too long");
        }
        request.put("method", method);
        request.put("path", path);
        String charset = stringValue(source.get("charset"));
        if (charset.length() == 0) charset = "UTF-8";
        request.put("charset", charset);
        if (source.get("body") != null) {
            String body = String.valueOf(source.get("body"));
            if (body.length() > MAX_REQUEST_CHARS || body.indexOf('\0') >= 0) {
                throw new IllegalArgumentException("targets[" + index + "].httpRequest.body is invalid or too long");
            }
            request.put("body", body);
        }
        Object headersValue = source.get("headers");
        if (headersValue != null) {
            if (!(headersValue instanceof Map)) {
                throw new IllegalArgumentException("targets[" + index + "].httpRequest.headers must be an object");
            }
            Map sourceHeaders = (Map) headersValue;
            if (sourceHeaders.size() > MAX_HEADERS) {
                throw new IllegalArgumentException("targets[" + index + "].httpRequest.headers cannot exceed " + MAX_HEADERS);
            }
            HashMap headers = new HashMap();
            Iterator iterator = sourceHeaders.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry entry = (Map.Entry) iterator.next();
                String name = stringValue(entry.getKey());
                String headerValue = stringValue(entry.getValue());
                if (name.length() == 0 || name.indexOf('\r') >= 0 || name.indexOf('\n') >= 0
                        || headerValue.indexOf('\r') >= 0 || headerValue.indexOf('\n') >= 0) {
                    throw new IllegalArgumentException("targets[" + index + "].httpRequest.headers contains invalid characters");
                }
                headers.put(name, headerValue);
            }
            request.put("headers", headers);
        }
        return request;
    }

    private List normalizeStages(Object value) {
        ArrayList stages = new ArrayList();
        Set seen = new HashSet();
        List raw = asList(value);
        if (raw == null) return stages;
        if (raw.size() > MAX_STAGES) throw new IllegalArgumentException("plan.stages cannot exceed " + MAX_STAGES);
        for (int i = 0; i < raw.size(); i++) {
            String stage = stringValue(raw.get(i)).toLowerCase(Locale.ENGLISH);
            if (!("tcp-connect".equals(stage) || "tcp-exchange".equals(stage)
                    || "http-head".equals(stage) || "http-request".equals(stage)
                    || "tls-handshake".equals(stage))) {
                throw new IllegalArgumentException("unsupported probe stage: " + stage);
            }
            if (seen.add(stage)) stages.add(stage);
        }
        return stages;
    }

    private void runWorker() {
        Map task = (Map) TASKS.get(taskId);
        if (task == null) return;
        Object lock = TASK_LOCKS.get(taskId);
        try {
            if (!waitIfRunning(task, lock)) return;
            probeTarget(task, target, plan);
        } catch (Throwable error) {
            addError(task, targetKey(target), "WORKER", errorCode(error), messageOf(error));
        } finally {
            AtomicInteger completed = (AtomicInteger) task.get("completed");
            int count = completed == null ? 0 : completed.incrementAndGet();
            if (count >= intValue(task.get("total"), 0)) finishTask(task, false);
        }
    }

    private void probeTarget(Map task, Map target, Map plan) {
        List stages = (List) plan.get("stages");
        String targetStage = stringValue(target.get("stage"));
        if (targetStage.length() > 0) stages = new ArrayList(Collections.singletonList(targetStage));
        int timeout = intValue(target.get("timeout"), intValue(plan.get("timeout"), 3000));
        int maxRead = intValue(target.get("maxReadBytes"), intValue(plan.get("maxReadBytes"), MAX_READ_BYTES));
        boolean connected = false;
        boolean connectAttempted = false;
        for (int i = 0; i < stages.size(); i++) {
            if (!waitIfRunning(task, TASK_LOCKS.get(taskId))) return;
            String stage = String.valueOf(stages.get(i));
            if ("tcp-connect".equals(stage)) {
                connectAttempted = true;
                Map observation = probeTcpConnect(target, timeout);
                connected = "open".equals(observation.get("state"));
                addObservation(task, observation);
            } else if ("tcp-exchange".equals(stage)) {
                if (!connectAttempted || connected) addObservation(task, probeTcpExchange(target, timeout, maxRead));
            } else if ("tls-handshake".equals(stage)) {
                addObservation(task, probeTls(target, timeout));
            } else if ("http-head".equals(stage)) {
                addObservation(task, probeHttp(target, timeout, maxRead));
            } else if ("http-request".equals(stage)) {
                addObservation(task, probeHttpRequest(target, timeout, maxRead));
            }
        }
    }

    private Map probeTcpConnect(Map target, int timeout) {
        long started = System.currentTimeMillis();
        Socket socket = null;
        try {
            socket = new Socket();
            socket.connect(new InetSocketAddress(String.valueOf(target.get("host")), intValue(target.get("port"), -1)), timeout);
            return baseObservation(target, "tcp-connect", "open", started, null, null, null);
        } catch (Throwable error) {
            return baseObservation(target, "tcp-connect", "closed", started, errorCode(error), messageOf(error), null);
        } finally {
            closeQuietly(socket);
        }
    }

    private Map probeTcpExchange(Map target, int timeout, int maxRead) {
        long started = System.currentTimeMillis();
        Socket socket = null;
        InputStream input = null;
        OutputStream output = null;
        try {
            socket = new Socket();
            socket.connect(new InetSocketAddress(String.valueOf(target.get("host")), intValue(target.get("port"), -1)), timeout);
            socket.setSoTimeout(timeout);
            String request = rawString(target.get("request"));
            if (request.length() > 0) {
                output = socket.getOutputStream();
                output.write(request.getBytes("ISO-8859-1"));
                output.flush();
            }
            input = socket.getInputStream();
            byte[] bytes = readBytes(input, maxRead, true);
            HashMap evidence = new HashMap();
            evidence.put("bytes", Integer.valueOf(bytes.length));
            if (bytes.length > 0) evidence.put("banner", sanitize(new String(bytes, "ISO-8859-1")));
            return baseObservation(target, "tcp-exchange", "open", started, null, null, evidence);
        } catch (Throwable error) {
            return baseObservation(target, "tcp-exchange", "error", started, errorCode(error), messageOf(error), null);
        } finally {
            closeQuietly(output);
            closeQuietly(input);
            closeQuietly(socket);
        }
    }

    private Map probeHttp(Map target, int timeout, int maxRead) {
        long started = System.currentTimeMillis();
        HttpURLConnection connection = null;
        try {
            String baseUrl = stringValue(target.get("baseUrl"));
            if (baseUrl.length() == 0) {
                baseUrl = ("https".equalsIgnoreCase(stringValue(target.get("protocol"))) ? "https://" : "http://")
                        + target.get("host") + ":" + target.get("port") + "/";
            }
            connection = (HttpURLConnection) new URL(baseUrl).openConnection();
            connection.setConnectTimeout(timeout);
            connection.setReadTimeout(timeout);
            connection.setUseCaches(false);
            connection.setInstanceFollowRedirects(false);
            if (connection instanceof HttpsURLConnection) {
                HttpsURLConnection https = (HttpsURLConnection) connection;
                https.setSSLSocketFactory(trustAllFactory());
                https.setHostnameVerifier((HostnameVerifier) java.lang.reflect.Proxy.newProxyInstance(
                        Thread.currentThread().getContextClassLoader(), new Class[]{HostnameVerifier.class}, this));
            }
            connection.setRequestMethod("HEAD");
            connection.setRequestProperty("User-Agent", "LeoAi-NetworkProbe/1.0");
            Map headers = (Map) target.get("headers");
            if (headers != null) {
                Iterator iterator = headers.keySet().iterator();
                while (iterator.hasNext()) {
                    Object key = iterator.next();
                    Object value = headers.get(key);
                    if (key != null && value != null) connection.setRequestProperty(String.valueOf(key), String.valueOf(value));
                }
            }
            int status = connection.getResponseCode();
            HashMap evidence = new HashMap();
            evidence.put("statusCode", Integer.valueOf(status));
            addHeader(evidence, "server", connection.getHeaderField("Server"), maxRead);
            addHeader(evidence, "location", connection.getHeaderField("Location"), maxRead);
            addHeader(evidence, "contentType", connection.getHeaderField("Content-Type"), maxRead);
            return baseObservation(target, "http-head", "open", started, null, null, evidence);
        } catch (Throwable error) {
            return baseObservation(target, "http-head", "error", started, errorCode(error), messageOf(error), null);
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private Map probeHttpRequest(Map target, int timeout, int maxRead) {
        long started = System.currentTimeMillis();
        HttpURLConnection connection = null;
        InputStream input = null;
        OutputStream output = null;
        try {
            Map request = asMap(target.get("httpRequest"));
            if (request == null) throw new IllegalArgumentException("httpRequest is required");
            String baseUrl = stringValue(target.get("baseUrl"));
            if (baseUrl.length() == 0) {
                baseUrl = ("https".equalsIgnoreCase(stringValue(target.get("protocol"))) ? "https://" : "http://")
                        + target.get("host") + ":" + target.get("port") + "/";
            }
            String path = stringValue(request.get("path"));
            String url = buildProbeUrl(baseUrl, path);
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(timeout);
            connection.setReadTimeout(timeout);
            connection.setUseCaches(false);
            connection.setInstanceFollowRedirects(false);
            if (connection instanceof HttpsURLConnection) {
                HttpsURLConnection https = (HttpsURLConnection) connection;
                https.setSSLSocketFactory(trustAllFactory());
                https.setHostnameVerifier((HostnameVerifier) java.lang.reflect.Proxy.newProxyInstance(
                        Thread.currentThread().getContextClassLoader(), new Class[]{HostnameVerifier.class}, this));
            }
            String method = stringValue(request.get("method"));
            if (method.length() == 0) method = "GET";
            connection.setRequestMethod(method);
            connection.setRequestProperty("User-Agent", "LeoAi-NetworkProbe/1.0");
            Map headers = asMap(request.get("headers"));
            if (headers != null) {
                Iterator iterator = headers.keySet().iterator();
                while (iterator.hasNext()) {
                    Object key = iterator.next();
                    Object value = headers.get(key);
                    if (key != null && value != null) connection.setRequestProperty(String.valueOf(key), String.valueOf(value));
                }
            }
            String body = rawString(request.get("body"));
            if (body.length() > 0 && ("POST".equals(method) || "PUT".equals(method) || "PATCH".equals(method))) {
                connection.setDoOutput(true);
                String charset = stringValue(request.get("charset"));
                if (charset.length() == 0) charset = "UTF-8";
                byte[] bytes = body.getBytes(charset);
                connection.setFixedLengthStreamingMode(bytes.length);
                output = connection.getOutputStream();
                output.write(bytes);
                output.flush();
            }
            int status = connection.getResponseCode();
            input = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            byte[] bytes = input == null ? new byte[0] : readBytes(input, maxRead, false);
            String charset = stringValue(request.get("charset"));
            if (charset.length() == 0) charset = "UTF-8";
            HashMap evidence = new HashMap();
            evidence.put("statusCode", Integer.valueOf(status));
            evidence.put("body", sanitize(new String(bytes, charset)));
            evidence.put("bodyLength", Integer.valueOf(bytes.length));
            evidence.put("truncated", Boolean.valueOf(bytes.length >= maxRead));
            Map responseHeaders = new HashMap();
            StringBuilder headerText = new StringBuilder();
            Map fields = connection.getHeaderFields();
            if (fields != null) {
                Iterator iterator = fields.entrySet().iterator();
                while (iterator.hasNext()) {
                    Map.Entry entry = (Map.Entry) iterator.next();
                    String name = stringValue(entry.getKey());
                    Object value = entry.getValue();
                    if (name.length() == 0 || value == null) continue;
                    String safe = sanitize(String.valueOf(value));
                    responseHeaders.put(name, safe);
                    if (headerText.length() > 0) headerText.append("\\n");
                    headerText.append(name).append(": ").append(safe);
                }
            }
            evidence.put("headers", headerText.toString());
            evidence.put("responseHeaders", responseHeaders);
            return baseObservation(target, "http-request", "open", started, null, null, evidence);
        } catch (Throwable error) {
            return baseObservation(target, "http-request", "error", started, errorCode(error), messageOf(error), null);
        } finally {
            closeQuietly(output);
            closeQuietly(input);
            if (connection != null) connection.disconnect();
        }
    }

    private static String buildProbeUrl(String baseUrl, String path) {
        String normalized = path == null || path.length() == 0 ? "/" : path;
        String lower = normalized.toLowerCase(Locale.ENGLISH);
        if (lower.startsWith("http://") || lower.startsWith("https://")) return normalized;
        if (baseUrl.endsWith("/") && normalized.startsWith("/")) return baseUrl + normalized.substring(1);
        if (!baseUrl.endsWith("/") && !normalized.startsWith("/")) return baseUrl + "/" + normalized;
        return baseUrl + normalized;
    }

    private Map probeTls(Map target, int timeout) {
        long started = System.currentTimeMillis();
        SSLSocket socket = null;
        try {
            SSLSocketFactory factory = trustAllFactory();
            socket = (SSLSocket) factory.createSocket();
            socket.connect(new InetSocketAddress(String.valueOf(target.get("host")), intValue(target.get("port"), -1)), timeout);
            socket.setSoTimeout(timeout);
            socket.startHandshake();
            SSLSession session = socket.getSession();
            HashMap evidence = new HashMap();
            evidence.put("protocol", session.getProtocol());
            evidence.put("cipherSuite", session.getCipherSuite());
            try {
                Certificate[] certificates = session.getPeerCertificates();
                evidence.put("certificateCount", Integer.valueOf(certificates.length));
                if (certificates.length > 0 && certificates[0] instanceof X509Certificate) {
                    X509Certificate certificate = (X509Certificate) certificates[0];
                    evidence.put("subject", sanitize(String.valueOf(certificate.getSubjectDN().getName())));
                    evidence.put("issuer", sanitize(String.valueOf(certificate.getIssuerDN().getName())));
                    evidence.put("notAfter", Long.valueOf(certificate.getNotAfter().getTime()));
                }
            } catch (SSLPeerUnverifiedException ignored) {
            }
            return baseObservation(target, "tls-handshake", "open", started, null, null, evidence);
        } catch (Throwable error) {
            return baseObservation(target, "tls-handshake", "error", started, errorCode(error), messageOf(error), null);
        } finally {
            closeQuietly(socket);
        }
    }

    private Map baseObservation(Map target, String stage, String state, long started,
                                String errorCode, String errorMessage, Map evidence) {
        HashMap observation = new HashMap();
        observation.put("target", targetKey(target));
        copyObservationMetadata(target, observation);
        observation.put("host", target.get("host"));
        observation.put("port", target.get("port"));
        observation.put("protocol", target.get("protocol"));
        observation.put("transport", "tcp");
        observation.put("stage", stage);
        observation.put("state", state);
        observation.put("latencyMs", Long.valueOf(Math.max(0L, System.currentTimeMillis() - started)));
        if (errorCode != null) observation.put("errorCode", errorCode);
        if (errorMessage != null && errorMessage.length() > 0) observation.put("error", sanitize(errorMessage));
        if (evidence != null && !evidence.isEmpty()) observation.put("evidence", evidence);
        return observation;
    }

    private static void copyObservationMetadata(Map target, Map observation) {
        String targetId = stringValue(target.get("targetId"));
        String probeId = stringValue(target.get("probeId"));
        String ruleId = stringValue(target.get("ruleId"));
        if (targetId.length() > 0) observation.put("targetId", targetId);
        if (probeId.length() > 0) observation.put("probeId", probeId);
        if (ruleId.length() > 0) observation.put("ruleId", ruleId);
        if (target.get("requestIndex") != null) observation.put("requestIndex", target.get("requestIndex"));
    }

    private void addObservation(Map task, Map observation) {
        Object list = task.get("observations");
        if (list instanceof List) {
            synchronized (list) { ((List) list).add(observation); }
        }
        Object evidence = observation.get("evidence");
        if (evidence instanceof Map) {
            // evidence is already bounded by each probe; retaining this branch keeps the response shape explicit.
        }
    }

    private void addError(Map task, String target, String stage, String code, String message) {
        HashMap error = new HashMap();
        error.put("target", target);
        error.put("stage", stage);
        error.put("errorCode", code);
        error.put("error", sanitize(message));
        Object list = task.get("errors");
        if (list instanceof List) {
            synchronized (list) { ((List) list).add(error); }
        }
    }

    private void queryTask(String id) {
        Map task = (Map) TASKS.get(id);
        if (task == null) {
            results.put("code", Integer.valueOf(404));
            results.put("msg", "network probe task not found: " + id);
            return;
        }
        HashMap snapshot = new HashMap();
        snapshot.put("taskId", task.get("taskId"));
        snapshot.put("resultVersion", task.get("resultVersion"));
        snapshot.put("scanKind", task.get("scanKind"));
        snapshot.put("status", task.get("status"));
        int total = intValue(task.get("total"), 0);
        AtomicInteger completed = (AtomicInteger) task.get("completed");
        int count = completed == null ? 0 : completed.get();
        snapshot.put("total", Integer.valueOf(total));
        snapshot.put("completed", Integer.valueOf(count));
        snapshot.put("progress", Integer.valueOf(total == 0 ? 0 : Math.min(100, count * 100 / total)));
        snapshot.put("targets", copyTargetSummaries(task.get("targets")));
        snapshot.put("plan", task.get("plan"));
        snapshot.put("observations", copyList(task.get("observations")));
        snapshot.put("errors", copyList(task.get("errors")));
        snapshot.put("createdAt", task.get("createdAt"));
        snapshot.put("finishedAt", task.get("finishedAt"));
        results.put("code", Integer.valueOf(200));
        results.put("result", snapshot);
    }

    private void updateTaskState(String id, String state) {
        Map task = (Map) TASKS.get(id);
        if (task == null) throw new IllegalArgumentException("network probe task not found: " + id);
        Object lock = TASK_LOCKS.get(id);
        synchronized (lock == null ? task : lock) {
            if ("STOPPED".equals(task.get("status"))) throw new IllegalStateException("network probe task already stopped");
            task.put("status", state);
            if ("RUNNING".equals(state)) (lock == null ? task : lock).notifyAll();
        }
        results.put("code", Integer.valueOf(200));
    }

    private void stopTask(String id) {
        Map task = (Map) TASKS.get(id);
        if (task == null) throw new IllegalArgumentException("network probe task not found: " + id);
        finishTask(task, true);
        Object executor = task.get("executor");
        if (executor instanceof ExecutorService) ((ExecutorService) executor).shutdownNow();
        task.remove("executor");
        results.put("code", Integer.valueOf(200));
    }

    private boolean waitIfRunning(Map task, Object lock) {
        Object monitor = lock == null ? task : lock;
        synchronized (monitor) {
            while ("PAUSED".equals(task.get("status"))) {
                try { monitor.wait(1000L); }
                catch (InterruptedException error) { Thread.currentThread().interrupt(); return false; }
            }
            return !"STOPPED".equals(task.get("status")) && !Thread.currentThread().isInterrupted();
        }
    }

    private void finishTask(Map task, boolean force) {
        Object lock = TASK_LOCKS.get(task.get("taskId"));
        Object monitor = lock == null ? task : lock;
        synchronized (monitor) {
            task.put("status", "STOPPED");
            if (force || task.get("finishedAt") == null) task.put("finishedAt", Long.valueOf(System.currentTimeMillis()));
            monitor.notifyAll();
        }
    }

    private int cleanupExpiredTasks() {
        long now = System.currentTimeMillis();
        int removed = 0;
        Iterator iterator = TASKS.keySet().iterator();
        while (iterator.hasNext()) {
            Object id = iterator.next();
            Map task = (Map) TASKS.get(id);
            if (task == null || !"STOPPED".equals(task.get("status"))) continue;
            long finished = longValue(task.get("finishedAt"), 0L);
            if (finished > 0L && now - finished > TASK_TTL_MS) {
                TASKS.remove(id);
                TASK_LOCKS.remove(id);
                removed++;
            }
        }
        return removed;
    }

    private static void addHeader(Map evidence, String key, String value, int maxRead) {
        if (value == null || value.length() == 0) return;
        String safe = sanitize(value);
        if (safe.length() > maxRead) safe = safe.substring(0, maxRead);
        evidence.put(key, safe);
    }

    private static byte[] readBytes(InputStream input, int maxBytes, boolean tcpMode) throws Exception {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[1024];
        while (buffer.size() < maxBytes) {
            int count;
            try { count = input.read(chunk, 0, Math.min(chunk.length, maxBytes - buffer.size())); }
            catch (SocketTimeoutException error) { if (tcpMode && buffer.size() > 0) break; throw error; }
            if (count < 0) break;
            if (count == 0) continue;
            buffer.write(chunk, 0, count);
        }
        return buffer.toByteArray();
    }

    private static String targetKey(Map target) {
        return String.valueOf(target.get("host")) + ":" + String.valueOf(target.get("port"));
    }

    private static String errorCode(Throwable error) {
        if (error instanceof SocketTimeoutException) return "TIMEOUT";
        String name = error == null ? "ERROR" : error.getClass().getName();
        if (name.indexOf("UnknownHost") >= 0) return "DNS";
        if (name.indexOf("ConnectException") >= 0) return "REFUSED";
        return "IO";
    }

    private static String messageOf(Throwable error) {
        if (error == null) return "unknown error";
        String message = error.getMessage();
        return message == null || message.length() == 0 ? error.getClass().getSimpleName() : message;
    }

    private static String sanitize(String value) {
        if (value == null) return "";
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < value.length() && result.length() < MAX_EVIDENCE_CHARS; i++) {
            char ch = value.charAt(i);
            if (ch == '\r' || ch == '\n' || ch == '\t' || (ch >= 32 && ch != 127)) result.append(ch);
        }
        return result.toString().trim();
    }

    private static int boundedInt(Object value, int fallback, int min, int max) {
        int result = fallback;
        if (value instanceof Number) result = ((Number) value).intValue();
        else if (value != null) {
            try { result = Integer.parseInt(String.valueOf(value).trim()); }
            catch (RuntimeException ignored) { result = fallback; }
        }
        if (result < min) return min;
        if (result > max) return max;
        return result;
    }

    private static int intValue(Object value, int fallback) {
        return value instanceof Number ? ((Number) value).intValue() : boundedInt(value, fallback, Integer.MIN_VALUE, Integer.MAX_VALUE);
    }

    private static long longValue(Object value, long fallback) {
        if (value instanceof Number) return ((Number) value).longValue();
        try { return value == null ? fallback : Long.parseLong(String.valueOf(value)); }
        catch (RuntimeException error) { return fallback; }
    }

    private static String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static String rawString(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static Map asMap(Object value) {
        return value instanceof Map ? (Map) value : null;
    }

    private static List asList(Object value) {
        return value instanceof List ? (List) value : null;
    }

    private static List copyList(Object value) {
        if (!(value instanceof List)) return new ArrayList();
        synchronized (value) { return new ArrayList((List) value); }
    }

    private static List copyTargetSummaries(Object value) {
        ArrayList summaries = new ArrayList();
        if (!(value instanceof List)) return summaries;
        synchronized (value) {
            Iterator iterator = ((List) value).iterator();
            while (iterator.hasNext()) {
                Object item = iterator.next();
                if (!(item instanceof Map)) continue;
                Map target = (Map) item;
                HashMap summary = new HashMap();
                summary.put("target", targetKey(target));
                summary.put("host", target.get("host"));
                summary.put("port", target.get("port"));
                summary.put("protocol", target.get("protocol"));
                if (target.get("targetId") != null) summary.put("targetId", target.get("targetId"));
                if (target.get("probeId") != null) summary.put("probeId", target.get("probeId"));
                if (target.get("ruleId") != null) summary.put("ruleId", target.get("ruleId"));
                if (target.get("requestIndex") != null) summary.put("requestIndex", target.get("requestIndex"));
                if (target.get("stage") != null) summary.put("stage", target.get("stage"));
                summaries.add(summary);
            }
        }
        return summaries;
    }

    private static void validateHost(String host, int index) {
        if (host.length() > 253 || host.indexOf('\r') >= 0 || host.indexOf('\n') >= 0) {
            throw new IllegalArgumentException("targets[" + index + "] host is invalid");
        }
        for (int i = 0; i < host.length(); i++) {
            if (Character.isWhitespace(host.charAt(i))) {
                throw new IllegalArgumentException("targets[" + index + "] host contains whitespace");
            }
        }
    }

    private static void closeQuietly(Object value) {
        try {
            if (value instanceof InputStream) ((InputStream) value).close();
            else if (value instanceof OutputStream) ((OutputStream) value).close();
            else if (value instanceof Socket) ((Socket) value).close();
        } catch (Exception ignored) {
        }
    }

    private static SSLSocketFactory trustAllFactory() throws Exception {
        SSLSocketFactory factory = TRUST_ALL_FACTORY;
        if (factory != null) return factory;
        synchronized (NetworkProbeComponent.class) {
            if (TRUST_ALL_FACTORY == null) {
                final SSLContext context = SSLContext.getInstance("TLS");
                NetworkProbeComponent handler = new NetworkProbeComponent();
                TrustManager trustManager = (TrustManager) java.lang.reflect.Proxy.newProxyInstance(
                        Thread.currentThread().getContextClassLoader(), new Class[]{X509TrustManager.class}, handler);
                context.init(null, new TrustManager[]{trustManager}, new SecureRandom());
                TRUST_ALL_FACTORY = context.getSocketFactory();
            }
            return TRUST_ALL_FACTORY;
        }
    }

    public Thread newThread(Runnable task) {
        Thread thread = new Thread(task, "worker-" + Integer.toHexString(String.valueOf(threadSeed).hashCode())
                + "-" + THREAD_SEQUENCE.incrementAndGet());
        thread.setDaemon(true);
        return thread;
    }

    public Object invoke(Object proxy, java.lang.reflect.Method method, Object[] args) throws Throwable {
        String name = method.getName();
        if ("checkClientTrusted".equals(name) || "checkServerTrusted".equals(name)) return null;
        if ("getAcceptedIssuers".equals(name)) return new X509Certificate[0];
        if ("verify".equals(name)) return Boolean.TRUE;
        return null;
    }
}
