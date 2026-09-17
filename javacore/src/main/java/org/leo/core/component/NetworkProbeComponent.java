package org.leo.core.component;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.Charset;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicInteger;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
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
    private static final int MAX_THREADS = 256;
    private static final int WORK_QUEUE_CAPACITY = MAX_TASKS * MAX_THREADS;
    private static final int MAX_READ_BYTES = 8192;
    private static final int MAX_EVIDENCE_CHARS = 4096;
    private static final int MAX_TITLE_CHARS = 512;
    private static final Pattern TITLE_PATTERN = Pattern.compile(
            "<title\\b[^>]*>(.*?)</title\\s*>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final long TASK_TTL_MS = 30L * 60L * 1000L;
    private static final AtomicInteger THREAD_SEQUENCE = new AtomicInteger();
    private static final Map TASKS = new ConcurrentHashMap();
    private static final ExecutorService WORK_EXECUTOR = new ThreadPoolExecutor(
            MAX_THREADS, MAX_THREADS, 0L, java.util.concurrent.TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue(WORK_QUEUE_CAPACITY), new NetworkProbeComponent(),
            new ThreadPoolExecutor.AbortPolicy());
    private static volatile SSLSocketFactory TRUST_ALL_FACTORY;

    private HashMap params;
    private HashMap results;
    private String taskId;

    public NetworkProbeComponent() {
    }

    private NetworkProbeComponent(String taskId) {
        this.taskId = taskId;
    }

    public void run() {
        if (taskId != null) {
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
        if ("startTask".equals(method)) {
            results.put("taskId", startTask(params));
            results.put("code", Integer.valueOf(200));
        } else if ("queryTask".equals(method)) {
            queryTask(params);
        } else if ("ackTask".equals(method)) {
            ackTask(stringValue(params.get("taskId")), longValue(params.get("cursor"), 0L));
        } else if ("pauseTask".equals(method)) {
            updateTaskState(stringValue(params.get("taskId")), "PAUSED");
        } else if ("resumeTask".equals(method)) {
            updateTaskState(stringValue(params.get("taskId")), "RUNNING");
        } else if ("stopTask".equals(method)) {
            stopTask(stringValue(params.get("taskId")));
        } else if ("releaseTask".equals(method)) {
            releaseTask(stringValue(params.get("taskId")));
        } else {
            throw new IllegalArgumentException("Unknown network probe method: " + method);
        }
    }

    private String startTask(Map input) {
        Map sourcePlan = asMap(input.get("plan"));
        if (sourcePlan == null) sourcePlan = input;
        List rawTargets = asList(sourcePlan.get("targets"));
        if (rawTargets == null) throw new IllegalArgumentException("targets must be a list");

        Map limits = asMap(sourcePlan.get("limits"));
        int timeout = boundedInt(limits == null ? null : limits.get("timeout"), 3000, 100, 300000);
        int maxRead = boundedInt(limits == null ? null : limits.get("maxReadBytes"), MAX_READ_BYTES, 256, MAX_READ_BYTES);
        int threads = boundedInt(limits == null ? null : limits.get("threads"), MAX_THREADS, 1, MAX_THREADS);

        List stages = copyStages(sourcePlan.get("stages"));
        if (stages.isEmpty()) stages.add("tcp-connect");
        Map normalizedPlan = new HashMap();
        normalizedPlan.put("stages", stages);
        normalizedPlan.put("timeout", Integer.valueOf(timeout));
        normalizedPlan.put("maxReadBytes", Integer.valueOf(maxRead));

        ArrayList normalizedTargets = new ArrayList();
        for (int i = 0; i < rawTargets.size(); i++) {
            normalizedTargets.add(normalizeTarget(rawTargets.get(i)));
        }

        String id = UUID.randomUUID().toString();
        HashMap task = new HashMap();
        task.put("taskId", id);
        task.put("scanKind", "network-probe");
        task.put("status", "RUNNING");
        task.put("outcome", "RUNNING");
        task.put("total", Integer.valueOf(normalizedTargets.size()));
        task.put("completed", Integer.valueOf(0));
        task.put("targets", normalizedTargets);
        task.put("plan", normalizedPlan);
        task.put("observations", new ArrayList());
        task.put("errors", new ArrayList());
        task.put("observationOffset", Integer.valueOf(0));
        task.put("createdAt", Long.valueOf(System.currentTimeMillis()));
        task.put("nextIndex", Integer.valueOf(0));
        synchronized (TASKS) {
            if (TASKS.size() >= MAX_TASKS) {
                throw new IllegalStateException("too many network probe tasks, max=" + MAX_TASKS);
            }
            TASKS.put(id, task);
        }
        if (normalizedTargets.isEmpty()) finishTask(task, false);
        try {
            int workerCount = Math.min(threads, normalizedTargets.size());
            for (int i = 0; i < workerCount; i++) {
                WORK_EXECUTOR.execute(new NetworkProbeComponent(id));
            }
        } catch (RuntimeException error) {
            finishTask(task, true);
            TASKS.remove(id);
            throw error;
        }
        return id;
    }

    private Map normalizeTarget(Object value) {
        Map source = asMap(value);
        if (source == null) throw new IllegalArgumentException("target must be a map");
        HashMap target = new HashMap();
        String protocol = stringValue(source.get("protocol")).toLowerCase(Locale.ENGLISH);
        String baseUrl = stringValue(source.get("baseUrl"));
        String host = stringValue(source.get("host"));
        int port = intValue(source.get("port"), -1);
        URL parsedUrl = null;
        if (baseUrl.length() > 0) {
            try {
                parsedUrl = new URL(baseUrl);
                if (host.length() == 0) host = parsedUrl.getHost();
                if (port <= 0) port = parsedUrl.getPort() > 0 ? parsedUrl.getPort()
                        : ("https".equalsIgnoreCase(parsedUrl.getProtocol()) ? 443 : 80);
            } catch (Exception error) {
                throw new IllegalArgumentException("invalid baseUrl: " + baseUrl);
            }
            if (protocol.length() == 0 && parsedUrl != null) {
                protocol = "https".equalsIgnoreCase(parsedUrl.getProtocol()) ? "https" : "http";
            }
        }
        if (protocol.length() == 0) protocol = "tcp";
        if (port <= 0) {
            if ("https".equals(protocol)) port = 443;
            else if ("http".equals(protocol)) port = 80;
        }
        if (host.length() == 0 || port < 1 || port > 65535) {
            throw new IllegalArgumentException("target requires a host and valid port");
        }
        target.put("host", host);
        target.put("port", Integer.valueOf(port));
        target.put("protocol", protocol);
        if (baseUrl.length() > 0) target.put("baseUrl", baseUrl);
        copyTargetMetadata(source, target);
        if (source.get("request") != null) {
            String request = rawString(source.get("request"));
            target.put("request", request);
        }
        if (source.get("headers") != null) target.put("headers", copyHeaders(source.get("headers")));
        if (source.get("httpRequest") != null) {
            target.put("httpRequest", normalizeHttpRequest(source.get("httpRequest")));
        }
        return target;
    }

    private void copyTargetMetadata(Map source, Map target) {
        String targetId = stringValue(source.get("targetId"));
        String probeId = stringValue(source.get("probeId"));
        String ruleId = stringValue(source.get("ruleId"));
        int requestIndex = intValue(source.get("requestIndex"), -1);
        if (targetId.length() > 0) target.put("targetId", targetId);
        if (probeId.length() > 0) target.put("probeId", probeId);
        if (ruleId.length() > 0) target.put("ruleId", ruleId);
        if (requestIndex >= 0) target.put("requestIndex", Integer.valueOf(requestIndex));
        String stage = stringValue(source.get("stage")).toLowerCase(Locale.ENGLISH);
        if (stage.length() > 0) {
            target.put("stage", stage);
        }
        if (source.get("timeout") != null) {
            target.put("timeout", Integer.valueOf(boundedInt(source.get("timeout"), 3000, 100, 300000)));
        }
        if (source.get("maxReadBytes") != null) {
            target.put("maxReadBytes", Integer.valueOf(boundedInt(source.get("maxReadBytes"), MAX_READ_BYTES, 256, MAX_READ_BYTES)));
        }
    }

    private Map normalizeHttpRequest(Object value) {
        Map source = asMap(value);
        if (source == null) throw new IllegalArgumentException("httpRequest must be a map");
        HashMap request = new HashMap();
        String method = stringValue(source.get("method")).toUpperCase(Locale.ENGLISH);
        if (method.length() == 0) method = "GET";
        String path = stringValue(source.get("path"));
        if (path.length() == 0) path = stringValue(source.get("uri"));
        if (path.length() == 0) path = "/";
        request.put("method", method);
        request.put("path", path);
        String charset = stringValue(source.get("charset"));
        if (charset.length() == 0) charset = "UTF-8";
        request.put("charset", charset);
        if (source.get("body") != null) {
            String body = String.valueOf(source.get("body"));
            request.put("body", body);
        }
        if (source.get("headers") != null) request.put("headers", copyHeaders(source.get("headers")));
        return request;
    }

    private Map copyHeaders(Object value) {
        Map source = asMap(value);
        if (source == null) throw new IllegalArgumentException("headers must be a map");
        HashMap headers = new HashMap();
        Iterator iterator = source.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry entry = (Map.Entry) iterator.next();
            headers.put(stringValue(entry.getKey()), stringValue(entry.getValue()));
        }
        return headers;
    }

    private List copyStages(Object value) {
        ArrayList stages = new ArrayList();
        List raw = asList(value);
        if (raw == null) return stages;
        for (int i = 0; i < raw.size(); i++) {
            String stage = stringValue(raw.get(i)).toLowerCase(Locale.ENGLISH);
            stages.add(stage);
        }
        return stages;
    }

    // The task itself is the monitor for its state, counters and result buffer.
    // Each submitted worker claims targets from that task; no second permit pool is needed.
    private void runWorker() {
        Map task = (Map) TASKS.get(taskId);
        if (task == null) return;
        List targets = (List) task.get("targets");
        Map plan = (Map) task.get("plan");
        while (true) {
            Map currentTarget;
            synchronized (task) {
                if (!waitIfRunning(task)) return;
                int index = intValue(task.get("nextIndex"), 0);
                if (index >= targets.size()) return;
                task.put("nextIndex", Integer.valueOf(index + 1));
                currentTarget = (Map) targets.get(index);
            }
            try {
                probeTarget(task, currentTarget, plan);
            } catch (Exception error) {
                addError(task, targetKey(currentTarget), "WORKER", errorCode(error), messageOf(error));
            } finally {
                synchronized (task) {
                    if (!"STOPPED".equals(task.get("status"))) {
                        int count = intValue(task.get("completed"), 0) + 1;
                        task.put("completed", Integer.valueOf(count));
                        if (count >= intValue(task.get("total"), 0)) finishTask(task, false);
                    }
                }
            }
        }
    }

    private void probeTarget(Map task, Map target, Map plan) {
        List stages = (List) plan.get("stages");
        String targetStage = stringValue(target.get("stage"));
        if (targetStage.length() > 0) stages = Collections.singletonList(targetStage);
        int timeout = intValue(target.get("timeout"), intValue(plan.get("timeout"), 3000));
        int maxRead = intValue(target.get("maxReadBytes"), intValue(plan.get("maxReadBytes"), MAX_READ_BYTES));
        boolean connected = false;
        boolean connectAttempted = false;
        for (int i = 0; i < stages.size(); i++) {
            if (!waitIfRunning(task)) return;
            String stage = String.valueOf(stages.get(i));
            if ("tcp-connect".equals(stage)) {
                connectAttempted = true;
                Map observation = probeTcp(target, stage, timeout, maxRead);
                connected = "open".equals(observation.get("state"));
                addObservation(task, observation);
            } else if ("tcp-exchange".equals(stage)) {
                if (!connectAttempted || connected) addObservation(task, probeTcp(target, stage, timeout, maxRead));
            } else if ("http-head".equals(stage) || "http-request".equals(stage)) {
                addObservation(task, probeHttp(target, stage, timeout, maxRead));
            } else {
                addObservation(task, baseObservation(target, stage, "error", System.currentTimeMillis(),
                        "UNSUPPORTED", "unsupported probe stage", null));
            }
        }
    }

    private Map probeTcp(Map target, String stage, int timeout, int maxRead) {
        long started = System.currentTimeMillis();
        boolean exchange = "tcp-exchange".equals(stage);
        Socket socket = null;
        try {
            socket = new Socket(Proxy.NO_PROXY);
            socket.connect(new InetSocketAddress(String.valueOf(target.get("host")), intValue(target.get("port"), -1)), timeout);
            if (!exchange) return baseObservation(target, stage, "open", started, null, null, null);
            socket.setSoTimeout(timeout);
            String request = rawString(target.get("request"));
            if (request.length() > 0) {
                OutputStream output = socket.getOutputStream();
                output.write(request.getBytes("ISO-8859-1"));
                output.flush();
            }
            byte[] bytes = readBytes(socket.getInputStream(), maxRead, true);
            HashMap evidence = new HashMap();
            evidence.put("bytes", Integer.valueOf(bytes.length));
            if (bytes.length > 0) evidence.put("banner", sanitize(new String(bytes, "ISO-8859-1")));
            return baseObservation(target, stage, "open", started, null, null, evidence);
        } catch (Throwable error) {
            return baseObservation(target, stage, exchange ? "error" : "closed", started,
                    errorCode(error), messageOf(error), null);
        } finally {
            // Closing a socket also closes both of its streams.
            closeQuietly(socket);
        }
    }

    private Map probeHttp(Map target, String stage, int timeout, int maxRead) {
        long started = System.currentTimeMillis();
        HttpURLConnection connection = null;
        InputStream input = null;
        OutputStream output = null;
        try {
            Map request = null;
            if ("http-request".equals(stage)) {
                request = asMap(target.get("httpRequest"));
                if (request == null) throw new IllegalArgumentException("httpRequest is required");
            }
            String baseUrl = stringValue(target.get("baseUrl"));
            if (baseUrl.length() == 0) {
                baseUrl = ("https".equalsIgnoreCase(stringValue(target.get("protocol"))) ? "https://" : "http://")
                        + hostForUrl(stringValue(target.get("host"))) + ":" + target.get("port") + "/";
            }
            String url = request == null ? baseUrl : buildProbeUrl(baseUrl, stringValue(request.get("path")));
            connection = openHttpConnection(url, timeout);
            // http-head retains its wire name; GET supplies the page title.
            String method = request == null ? "GET" : stringValue(request.get("method"));
            if (method.length() == 0) method = "GET";
            connection.setRequestMethod(method);
            Map headers = asMap((request == null ? target : request).get("headers"));
            if (headers != null) {
                Iterator iterator = headers.keySet().iterator();
                while (iterator.hasNext()) {
                    Object key = iterator.next();
                    Object value = headers.get(key);
                    if (key != null && value != null) connection.setRequestProperty(String.valueOf(key), String.valueOf(value));
                }
            }
            String charset = request == null ? "UTF-8" : stringValue(request.get("charset"));
            if (charset.length() == 0) charset = "UTF-8";
            String body = request == null ? "" : rawString(request.get("body"));
            if (body.length() > 0 && ("POST".equals(method) || "PUT".equals(method) || "PATCH".equals(method))) {
                connection.setDoOutput(true);
                byte[] bytes = body.getBytes(charset);
                connection.setFixedLengthStreamingMode(bytes.length);
                output = connection.getOutputStream();
                output.write(bytes);
                output.flush();
            }
            int status = connection.getResponseCode();
            input = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            byte[] bytes = input == null ? new byte[0] : readBytes(input, maxRead, false);
            Map evidence = request == null ? httpSummary(connection, bytes, maxRead)
                    : httpResponse(connection, bytes, charset, maxRead);
            evidence.put("statusCode", Integer.valueOf(status));
            evidence.put("bodyLength", Integer.valueOf(bytes.length));
            return baseObservation(target, stage, "open", started, null, null, evidence);
        } catch (Throwable error) {
            return baseObservation(target, stage, "error", started, errorCode(error), messageOf(error), null);
        } finally {
            closeQuietly(output);
            closeQuietly(input);
            if (connection != null) connection.disconnect();
        }
    }

    private Map httpSummary(HttpURLConnection connection, byte[] bytes, int maxRead) {
        HashMap evidence = new HashMap();
        int contentLength = connection.getContentLength();
        evidence.put("responseSize", Integer.valueOf(contentLength >= 0 ? contentLength : bytes.length));
        String contentType = connection.getHeaderField("Content-Type");
        addHeader(evidence, "server", connection.getHeaderField("Server"), maxRead);
        addHeader(evidence, "location", connection.getHeaderField("Location"), maxRead);
        addHeader(evidence, "contentType", contentType, maxRead);
        String title = extractTitle(decodeHttpBody(bytes, contentType));
        if (title.length() > 0) evidence.put("title", title);
        return evidence;
    }

    private Map httpResponse(HttpURLConnection connection, byte[] bytes, String charset, int maxRead)
            throws Exception {
        HashMap evidence = new HashMap();
        evidence.put("body", sanitize(new String(bytes, charset)));
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
        return evidence;
    }

    private static String decodeHttpBody(byte[] bytes, String contentType) {
        if (bytes == null || bytes.length == 0) return "";
        String charsetName = "UTF-8";
        String value = contentType == null ? "" : contentType;
        String lower = value.toLowerCase(Locale.ENGLISH);
        int marker = lower.indexOf("charset=");
        if (marker >= 0) {
            int start = marker + 8;
            int end = start;
            while (end < value.length()) {
                char ch = value.charAt(end);
                if (ch == ';' || ch == ' ' || ch == '\t' || ch == '\"' || ch == '\'') break;
                end++;
            }
            if (end > start) charsetName = value.substring(start, end).trim();
        }
        try {
            return new String(bytes, Charset.forName(charsetName));
        } catch (Exception ignored) {
            try { return new String(bytes, "UTF-8"); }
            catch (Exception impossible) { return ""; }
        }
    }

    private static String extractTitle(String body) {
        if (body == null || body.length() == 0) return "";
        Matcher matcher = TITLE_PATTERN.matcher(body);
        if (!matcher.find()) return "";
        String title = matcher.group(1).replaceAll("<[^>]*>", "")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replaceAll("\\s+", " ").trim();
        title = sanitize(title);
        return title.length() > MAX_TITLE_CHARS ? title.substring(0, MAX_TITLE_CHARS) : title;
    }

    private HttpURLConnection openHttpConnection(String url, int timeout) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection(Proxy.NO_PROXY);
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
        return connection;
    }

    private static String buildProbeUrl(String baseUrl, String path) {
        String normalized = path == null || path.length() == 0 ? "/" : path;
        String lower = normalized.toLowerCase(Locale.ENGLISH);
        if (lower.startsWith("http://") || lower.startsWith("https://")) return normalized;
        if (baseUrl.endsWith("/") && normalized.startsWith("/")) return baseUrl + normalized.substring(1);
        if (!baseUrl.endsWith("/") && !normalized.startsWith("/")) return baseUrl + "/" + normalized;
        return baseUrl + normalized;
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
        synchronized (task) {
            if ("STOPPED".equals(task.get("status"))) return;
            ((List) task.get("observations")).add(observation);
            if ("error".equals(observation.get("state"))) {
                addError(task, stringValue(observation.get("target")),
                        stringValue(observation.get("stage")), stringValue(observation.get("errorCode")),
                        stringValue(observation.get("error")));
            }
        }
    }

    private void addError(Map task, String target, String stage, String code, String message) {
        synchronized (task) {
            if ("STOPPED".equals(task.get("status"))) return;
            HashMap error = new HashMap();
            error.put("target", target);
            error.put("stage", stage);
            error.put("errorCode", code);
            error.put("error", sanitize(message));
            ((List) task.get("errors")).add(error);
        }
    }

    private Map findTask(String id) {
        Map task = (Map) TASKS.get(id);
        if (task == null) {
            results.put("code", Integer.valueOf(404));
            results.put("msg", "network probe task not found: " + id);
        }
        return task;
    }

    private void queryTask(Map input) {
        Map task = findTask(stringValue(input.get("taskId")));
        if (task == null) return;
        long cursor = Math.max(0L, longValue(input.get("cursor"), 0L));
        int maxItems = boundedInt(input.get("maxItems"), 128, 1, 512);
        int maxBytes = boundedInt(input.get("maxBytes"), 524288, 4096, 1048576);
        boolean includeEvidence = !Boolean.FALSE.equals(input.get("includeEvidence"));
        HashMap snapshot;
        synchronized (task) {
            snapshot = baseSnapshot(task);
            List observations = (List) task.get("observations");
            int base = intValue(task.get("observationOffset"), 0);
            int index = (int) Math.max(0L, Math.min(cursor - base, (long) observations.size()));
            ArrayList page = new ArrayList();
            int bytes = 0;
            while (index < observations.size() && page.size() < maxItems) {
                HashMap copy = new HashMap((Map) observations.get(index));
                if (!includeEvidence) copy.remove("evidence");
                int estimate = String.valueOf(copy).length();
                if (!page.isEmpty() && bytes + estimate > maxBytes) break;
                page.add(copy);
                bytes += estimate;
                index++;
            }
            snapshot.put("cursor", Long.valueOf(cursor));
            snapshot.put("nextCursor", Long.valueOf((long) base + index));
            snapshot.put("hasMore", Boolean.valueOf(index < observations.size()));
            snapshot.put("observations", page);
            snapshot.put("errors", copyListLimited(task.get("errors"), maxItems));
        }
        snapshot.put("incremental", Boolean.TRUE);
        results.put("code", Integer.valueOf(200));
        results.put("result", snapshot);
    }

    private HashMap baseSnapshot(Map task) {
        HashMap snapshot = new HashMap();
        snapshot.put("taskId", task.get("taskId"));
        snapshot.put("scanKind", task.get("scanKind"));
        snapshot.put("status", task.get("status"));
        snapshot.put("outcome", task.get("outcome"));
        int total = intValue(task.get("total"), 0);
        int count = intValue(task.get("completed"), 0);
        snapshot.put("total", Integer.valueOf(total));
        snapshot.put("completed", Integer.valueOf(count));
        snapshot.put("progress", Integer.valueOf(total == 0 ? 0 : (int) Math.min(100L, (long) count * 100L / total)));
        snapshot.put("createdAt", task.get("createdAt"));
        snapshot.put("finishedAt", task.get("finishedAt"));
        return snapshot;
    }

    private void ackTask(String id, long cursor) {
        Map task = findTask(id);
        if (task == null) return;
        synchronized (task) {
            List observations = (List) task.get("observations");
            int base = intValue(task.get("observationOffset"), 0);
            long bounded = Math.max((long) base, Math.min(cursor, (long) base + observations.size()));
            int remove = (int) (bounded - base);
            if (remove > 0) {
                observations.subList(0, remove).clear();
                task.put("observationOffset", Integer.valueOf((int) bounded));
            }
            results.put("cursor", Long.valueOf(bounded));
        }
        results.put("code", Integer.valueOf(200));
    }

    private void updateTaskState(String id, String state) {
        Map task = findTask(id);
        if (task == null) return;
        synchronized (task) {
            String expected = "PAUSED".equals(state) ? "RUNNING" : "PAUSED";
            if (!expected.equals(task.get("status"))) {
                results.put("code", Integer.valueOf(409));
                results.put("msg", "invalid task state");
                return;
            }
            task.put("status", state);
            task.notifyAll();
        }
        results.put("code", Integer.valueOf(200));
    }

    private void stopTask(String id) {
        Map task = findTask(id);
        if (task == null) return;
        finishTask(task, true);
        results.put("code", Integer.valueOf(200));
    }

    private void releaseTask(String id) {
        Map task = findTask(id);
        if (task == null) return;
        synchronized (task) {
            if (!"STOPPED".equals(task.get("status"))) {
                results.put("code", Integer.valueOf(409));
                results.put("msg", "network probe task is still active: " + id);
                return;
            }
            TASKS.remove(id);
        }
        results.put("code", Integer.valueOf(200));
    }

    private boolean waitIfRunning(Map task) {
        synchronized (task) {
            while ("PAUSED".equals(task.get("status"))) {
                try { task.wait(); }
                catch (InterruptedException error) { Thread.currentThread().interrupt(); return false; }
            }
            return !"STOPPED".equals(task.get("status")) && !Thread.currentThread().isInterrupted();
        }
    }

    private void finishTask(Map task, boolean cancelled) {
        synchronized (task) {
            if ("STOPPED".equals(task.get("status"))) return;
            task.put("status", "STOPPED");
            task.put("outcome", cancelled ? "CANCELLED" : "COMPLETED");
            task.put("finishedAt", Long.valueOf(System.currentTimeMillis()));
            task.notifyAll();
        }
    }

    private int cleanupExpiredTasks() {
        long now = System.currentTimeMillis();
        int removed = 0;
        Iterator iterator = TASKS.values().iterator();
        while (iterator.hasNext()) {
            Map task = (Map) iterator.next();
            synchronized (task) {
                long finished = longValue(task.get("finishedAt"), 0L);
                if ("STOPPED".equals(task.get("status")) && finished > 0L && now - finished > TASK_TTL_MS) {
                    if (TASKS.remove(task.get("taskId")) != null) removed++;
                }
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
        return Math.max(min, Math.min(max, intValue(value, fallback)));
    }

    private static int intValue(Object value, int fallback) {
        if (value instanceof Number) return ((Number) value).intValue();
        if (value != null) {
            try { return Integer.parseInt(String.valueOf(value).trim()); }
            catch (RuntimeException ignored) { }
        }
        return fallback;
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

    private static List copyListLimited(Object value, int limit) {
        if (!(value instanceof List)) return new ArrayList();
        List source = (List) value;
        int end = Math.min(source.size(), Math.max(0, limit));
        return new ArrayList(source.subList(0, end));
    }

    private static String hostForUrl(String host) {
        return host.indexOf(':') >= 0 && !host.startsWith("[") ? "[" + host + "]" : host;
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
        Thread thread = new Thread(task, "worker-" + Integer.toHexString(getClass().getName().hashCode())
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
