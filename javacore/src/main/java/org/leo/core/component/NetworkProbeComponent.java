package org.leo.core.component;

import java.io.ByteArrayOutputStream;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.InputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.EOFException;
import java.util.zip.GZIPInputStream;
import java.io.OutputStream;
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

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * 统一节点侧网络探测原子运行时。
 *
 * <p>该类必须保持单文件、单 class、Java 6 API 兼容；它会被独立编译后
 * 作为 payload 下发到 Agent JVM。上层只提交声明式 plan，组件返回统一
 * observation，不执行脚本或加载其他组件。</p>
 */
public class NetworkProbeComponent implements Runnable, ThreadFactory,
        java.lang.reflect.InvocationHandler {

    private static final int MAX_TASKS = 32;
    private static final int MAX_THREADS = 256;
    private static final int MAX_TARGETS = 4096;
    private static final int MAX_STAGES = 8;
    private static final long MAX_PLAN_CHARS = 2L * 1024L * 1024L;
    private static final int MAX_PENDING_OBSERVATIONS = 4096;
    private static final int MAX_ERRORS = 128;
    private static final long MAX_ERROR_BYTES = 256L * 1024L;
    private static final long MAX_RESULT_BYTES = 4L * 1024L * 1024L;
    private static final int RESULT_HIGH_ITEMS = 3072;
    private static final int RESULT_LOW_ITEMS = 2048;
    private static final long RESULT_HIGH_BYTES = 3L * 1024L * 1024L;
    private static final long RESULT_LOW_BYTES = 2L * 1024L * 1024L;
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
    private Map currentTarget, pendingObservation;
    private long[] pendingSize;
    private int stageIndex;
    private boolean queued, running, connected, connectAttempted;

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
        String method = stringValue(params.get("action"));
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
        if (sourcePlan == null) throw new IllegalArgumentException("plan must be a map");
        List rawTargets = asList(sourcePlan.get("targets"));
        if (rawTargets == null) throw new IllegalArgumentException("targets must be a list");
        if (rawTargets.size() > MAX_TARGETS) {
            throw new IllegalArgumentException("too many targets, max=" + MAX_TARGETS);
        }
        if (estimateChars(sourcePlan, 0) > MAX_PLAN_CHARS) {
            throw new IllegalArgumentException("network probe plan exceeds retention limit");
        }

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
        task.put("retainedBytes", Long.valueOf(0L));
        task.put("errorCount", Integer.valueOf(0));
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
            List workers = new ArrayList();
            for (int i = 0; i < workerCount; i++) workers.add(new NetworkProbeComponent(id));
            task.put("workers", workers);
            scheduleWorkers(task);
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
        if (raw.size() > MAX_STAGES) throw new IllegalArgumentException("too many stages, max=" + MAX_STAGES);
        for (int i = 0; i < raw.size(); i++) {
            String stage = stringValue(raw.get(i)).toLowerCase(Locale.ENGLISH);
            stages.add(stage);
        }
        return stages;
    }

    // One runnable turn performs at most one atomic probe. Paused/backpressured
    // workers keep their target/stage cursor but return the physical pool thread.
    private void runWorker() {
        Map task = (Map) TASKS.get(taskId);
        if (task == null) return;
        synchronized (task) {
            queued = false;
            if (!runnable(task)) return;
            running = true;
        }
        try {
            if (pendingObservation != null) {
                synchronized (task) {
                    if (!offerObservation(task, pendingObservation, pendingSize)) return;
                }
                pendingObservation = null; pendingSize = null; stageIndex++;
            } else {
                Map plan;
                synchronized (task) {
                    if (currentTarget == null) {
                        List targets = (List) task.get("targets");
                        int index = intValue(task.get("nextIndex"), 0);
                        if (targets == null || index >= targets.size()) return;
                        currentTarget = (Map) targets.get(index); task.put("nextIndex", Integer.valueOf(index + 1));
                        stageIndex = 0; connected = false; connectAttempted = false;
                    }
                    plan = (Map) task.get("plan");
                }
                List stages = targetStages(plan);
                String stage = String.valueOf(stages.get(stageIndex));
                int timeout = intValue(currentTarget.get("timeout"), intValue(plan.get("timeout"), 3000));
                int maxRead = intValue(currentTarget.get("maxReadBytes"), intValue(plan.get("maxReadBytes"), MAX_READ_BYTES));
                if ("tcp-connect".equals(stage)) {
                    connectAttempted = true; pendingObservation = probeTcp(task, currentTarget, stage, timeout, maxRead);
                    connected = "open".equals(pendingObservation.get("state"));
                } else if ("tcp-exchange".equals(stage)) {
                    if (!connectAttempted || connected) pendingObservation = probeTcp(task, currentTarget, stage, timeout, maxRead);
                } else if ("http-head".equals(stage) || "http-request".equals(stage)) {
                    pendingObservation = probeHttp(task, currentTarget, stage, timeout, maxRead);
                } else pendingObservation = baseObservation(currentTarget, stage, "error", System.currentTimeMillis(),
                        "UNSUPPORTED", "unsupported probe stage", null);
                if (pendingObservation != null) {
                    pendingSize = observationSize(pendingObservation);
                    synchronized (task) { if (!offerObservation(task, pendingObservation, pendingSize)) return; }
                    pendingObservation = null; pendingSize = null;
                }
                stageIndex++;
            }
            synchronized (task) {
                if (!"STOPPED".equals(task.get("status")) && stageIndex >= targetStages((Map) task.get("plan")).size()) {
                    currentTarget = null;
                    int completed = intValue(task.get("completed"), 0) + 1;
                    task.put("completed", Integer.valueOf(completed));
                    if (completed >= intValue(task.get("total"), 0)) finishTask(task, false);
                }
            }
        } catch (Exception error) {
            addError(task, currentTarget == null ? "" : targetKey(currentTarget), "WORKER", errorCode(error), messageOf(error));
            finishTask(task, true);
        } finally {
            synchronized (task) {
                running = false;
                if ("STOPPED".equals(task.get("status"))) { currentTarget = null; pendingObservation = null; pendingSize = null; }
                else scheduleWorker(task, this);
            }
        }
    }

    private List targetStages(Map plan) {
        String stage = stringValue(currentTarget.get("stage"));
        return stage.length() > 0 ? Collections.singletonList(stage) : (List) plan.get("stages");
    }

    private boolean runnable(Map task) {
        return "RUNNING".equals(task.get("status")) && !Boolean.TRUE.equals(task.get("backpressured"));
    }

    private void scheduleWorkers(Map task) {
        synchronized (task) {
            List workers = (List) task.get("workers");
            if (workers == null) return;
            for (Object worker : workers) scheduleWorker(task, (NetworkProbeComponent) worker);
        }
    }

    // Caller owns the task monitor. The executor never runs rejected work inline.
    private void scheduleWorker(Map task, NetworkProbeComponent worker) {
        if (!runnable(task) || worker.queued || worker.running) return;
        if (worker.currentTarget == null && intValue(task.get("nextIndex"), 0) >= intValue(task.get("total"), 0)) return;
        worker.queued = true;
        try { WORK_EXECUTOR.execute(worker); }
        catch (RuntimeException error) { worker.queued = false; finishTask(task, true); throw error; }
    }

    private Map probeTcp(Map task, Map target, String stage, int timeout, int maxRead) {
        long started = System.currentTimeMillis();
        boolean exchange = "tcp-exchange".equals(stage);
        Socket socket = null;
        try {
            socket = new Socket(Proxy.NO_PROXY);
            trackSocket(task, socket);
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
            releaseSocket(task, socket);
        }
    }

    private Map probeHttp(Map task, Map target, String stage, int timeout, int maxRead) {
        long started = System.currentTimeMillis();
        Socket socket = null;
        Socket transport = null;
        try {
            Map request = "http-request".equals(stage) ? asMap(target.get("httpRequest")) : null;
            if ("http-request".equals(stage) && request == null) throw new IllegalArgumentException("httpRequest is required");
            String base = stringValue(target.get("baseUrl"));
            if (base.length() == 0) base = ("https".equalsIgnoreCase(stringValue(target.get("protocol"))) ? "https" : "http") + "://"
                    + hostForUrl(stringValue(target.get("host"))) + ":" + target.get("port") + "/";
            URL url = new URL(request == null ? base : buildProbeUrl(base, stringValue(request.get("path"))));
            if (!"http".equals(url.getProtocol()) && !"https".equals(url.getProtocol()))
                throw new IllegalArgumentException("unsupported HTTP protocol");
            transport = new Socket(Proxy.NO_PROXY);
            trackSocket(task, transport);
            transport.connect(new InetSocketAddress(stringValue(target.get("host")), intValue(target.get("port"), -1)), timeout);
            socket = transport;
            socket.setSoTimeout(timeout);
            if ("https".equals(url.getProtocol())) {
                // Dial the planned IP, but keep the application hostname for TLS peer identity/SNI.
                socket = trustAllFactory().createSocket(transport, url.getHost(), intValue(target.get("port"), -1), true);
                trackSocket(task, socket);
                socket.setSoTimeout(timeout);
                ((javax.net.ssl.SSLSocket) socket).startHandshake();
            }
            String method = request == null ? "GET" : stringValue(request.get("method"));
            if (method.length() == 0) method = "GET";
            if (!method.matches("[A-Z]{1,16}")) throw new IllegalArgumentException("invalid HTTP method");
            String charset = request == null ? "UTF-8" : stringValue(request.get("charset"));
            if (charset.length() == 0) charset = "UTF-8";
            byte[] body = request == null ? new byte[0] : rawString(request.get("body")).getBytes(charset);
            String path = url.getFile().length() == 0 ? "/" : url.getFile();
            if (path.indexOf('\r') >= 0 || path.indexOf('\n') >= 0 || path.indexOf(' ') >= 0)
                throw new IllegalArgumentException("invalid HTTP path");
            Map headers = asMap((request == null ? target : request).get("headers"));
            StringBuilder wire = new StringBuilder(method).append(' ').append(path).append(" HTTP/1.1\r\n");
            boolean hostSet = false, encodingSet = false;
            if (headers != null) for (Object key : headers.keySet()) {
                String name = String.valueOf(key), value = String.valueOf(headers.get(key));
                if (!name.matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+") || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0)
                    throw new IllegalArgumentException("invalid HTTP header");
                if ("Connection".equalsIgnoreCase(name) || "Content-Length".equalsIgnoreCase(name)
                        || "Transfer-Encoding".equalsIgnoreCase(name)) continue;
                if ("Host".equalsIgnoreCase(name)) hostSet = true;
                if ("Accept-Encoding".equalsIgnoreCase(name)) encodingSet = true;
                wire.append(name).append(": ").append(value).append("\r\n");
            }
            if (!hostSet) wire.append("Host: ").append(hostForUrl(url.getHost())).append(':').append(target.get("port")).append("\r\n");
            if (!encodingSet) wire.append("Accept-Encoding: identity\r\n");
            wire.append("Connection: close\r\n");
            if (body.length > 0) wire.append("Content-Length: ").append(body.length).append("\r\n");
            wire.append("\r\n");
            if (wire.length() > MAX_READ_BYTES) throw new IllegalArgumentException("HTTP request headers exceed limit");
            OutputStream output = new BufferedOutputStream(socket.getOutputStream(), 2048);
            output.write(wire.toString().getBytes("ISO-8859-1"));
            output.write(body);
            output.flush();
            InputStream input = new BufferedInputStream(socket.getInputStream(), 2048);
            Map responseHeaders;
            String responseHeaderText;
            int status, interim = 0, headerBytes = 0;
            do {
                String head = readHttpHeaders(input);
                headerBytes += head.length();
                if (headerBytes > MAX_READ_BYTES) throw new IOException("HTTP response headers exceed limit");
                responseHeaderText = head.substring(0, head.length() - 4);
                String[] lines = head.split("\r\n");
                if (!lines[0].matches("HTTP/1\\.[01] [0-9]{3}( .*)?")) throw new IOException("invalid HTTP status line");
                status = Integer.parseInt(lines[0].substring(9, 12));
                responseHeaders = new HashMap();
                for (int i = 1; i < lines.length; i++) {
                    int colon = lines[i].indexOf(':');
                    if (colon <= 0) throw new IOException("invalid HTTP response header");
                    String name = lines[i].substring(0, colon).toLowerCase(Locale.ENGLISH);
                    if (!name.matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+")) throw new IOException("invalid HTTP response header name");
                    List values = (List) responseHeaders.get(name);
                    if (values == null) { values = new ArrayList(); responseHeaders.put(name, values); }
                    values.add(lines[i].substring(colon + 1).trim());
                }
                if (++interim > 8) throw new IOException("too many interim HTTP responses");
            } while (status >= 100 && status < 200 && status != 101);
            boolean hasBody = !"HEAD".equals(method) && status >= 200 && status != 204 && status != 304;
            Object[] prefix = hasBody ? readHttpBody(input, responseHeaders, maxRead)
                    : new Object[]{new byte[0], Boolean.FALSE};
            byte[] bytes = (byte[]) prefix[0];
            boolean truncated = Boolean.TRUE.equals(prefix[1]);
            if (hasBody && "gzip".equalsIgnoreCase(header(responseHeaders, "content-encoding"))) {
                GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(bytes));
                try { bytes = readPrefix(gzip, maxRead + 1, false, truncated); }
                finally { gzip.close(); }
                truncated |= bytes.length > maxRead;
                if (bytes.length > maxRead) bytes = java.util.Arrays.copyOf(bytes, maxRead);
            } else if (hasBody && header(responseHeaders, "content-encoding").length() > 0
                    && !"identity".equalsIgnoreCase(header(responseHeaders, "content-encoding"))) {
                bytes = new byte[0]; truncated = true;
            }
            HashMap evidence = new HashMap();
            evidence.put("statusCode", Integer.valueOf(status));
            evidence.put("bodyLength", Integer.valueOf(bytes.length));
            evidence.put("truncated", Boolean.valueOf(truncated));
            if (request == null) {
                long size = longValue(header(responseHeaders, "content-length"), bytes.length);
                evidence.put("responseSize", Long.valueOf(size));
                addHeader(evidence, "server", header(responseHeaders, "server"), maxRead);
                addHeader(evidence, "location", header(responseHeaders, "location"), maxRead);
                addHeader(evidence, "contentType", header(responseHeaders, "content-type"), maxRead);
                String title = extractTitle(decodeHttpBody(bytes, header(responseHeaders, "content-type")));
                if (title.length() > 0) evidence.put("title", title);
            } else {
                evidence.put("body", new String(bytes, charset));
                Map fields = new HashMap();
                for (Object key : responseHeaders.keySet()) {
                    StringBuilder value = new StringBuilder();
                    for (Object item : (List) responseHeaders.get(key)) {
                        if (value.length() > 0) value.append(", ");
                        value.append(item);
                    }
                    fields.put(key, value.toString());
                }
                evidence.put("headers", responseHeaderText);
                evidence.put("headersTruncated", Boolean.FALSE);
                evidence.put("responseHeaders", fields);
            }
            return baseObservation(target, stage, "open", started, null, null, evidence);
        } catch (Throwable error) {
            return baseObservation(target, stage, "error", started, errorCode(error), messageOf(error), null);
        } finally {
            // Close the transport first so TLS shutdown never waits on a stopped task's peer.
            releaseSocket(task, transport);
            if (socket != transport) releaseSocket(task, socket);
        }
    }

    private static String header(Map headers, String name) {
        List values = (List) headers.get(name);
        return values == null || values.isEmpty() ? "" : String.valueOf(values.get(0));
    }

    private static String readHttpHeaders(InputStream input) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int end = 0;
        while (buffer.size() < MAX_READ_BYTES) {
            int value = input.read();
            if (value < 0) throw new EOFException("incomplete HTTP response headers");
            buffer.write(value);
            end = (end << 8) | value;
            if (end == 0x0d0a0d0a) return new String(buffer.toByteArray(), "ISO-8859-1");
        }
        throw new IOException("HTTP response headers exceed limit");
    }

    private static byte[] readPrefix(InputStream input, int size, boolean exact) throws IOException {
        return readPrefix(input, size, exact, false);
    }

    private static byte[] readPrefix(InputStream input, int size, boolean exact, boolean truncatedInput) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[2048];
        while (output.size() < size) {
            int count;
            try { count = input.read(buffer, 0, Math.min(buffer.length, size - output.size())); }
            catch (EOFException error) {
                if (!truncatedInput) throw error;
                break;
            }
            if (count < 0) { if (exact) throw new EOFException("incomplete HTTP response body"); break; }
            if (count > 0) output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    private static Object[] readHttpBody(InputStream input, Map headers, int limit) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        String transfer = header(headers, "transfer-encoding");
        if (transfer.length() > 0 && !"chunked".equalsIgnoreCase(transfer))
            throw new IOException("unsupported HTTP transfer encoding");
        if ("chunked".equalsIgnoreCase(header(headers, "transfer-encoding"))) {
            while (true) {
                String line = readHttpLine(input);
                int extension = line.indexOf(';');
                String hex = extension < 0 ? line : line.substring(0, extension);
                if (!hex.matches("[0-9a-fA-F]{1,15}")) throw new IOException("invalid HTTP chunk size");
                long chunk = Long.parseLong(hex, 16);
                if (chunk == 0) {
                    int trailerBytes = 0;
                    while ((line = readHttpLine(input)).length() > 0) {
                        trailerBytes += line.length() + 2;
                        if (trailerBytes > MAX_READ_BYTES) throw new IOException("HTTP trailers exceed limit");
                    }
                    break;
                }
                int read = (int) Math.min(chunk, limit + 1 - output.size());
                output.write(readPrefix(input, read, true));
                if (output.size() > limit) break;
                if (input.read() != '\r' || input.read() != '\n') throw new IOException("invalid HTTP chunk terminator");
            }
        } else {
            String length = header(headers, "content-length");
            if (length.length() > 0 && !length.matches("[0-9]+")) throw new IOException("invalid HTTP content length");
            long size = length.length() == 0 ? -1L : longValue(length, -2L);
            if (size < -1L) throw new IOException("invalid HTTP content length");
            output.write(readPrefix(input, (int) (size < 0 ? limit + 1 : Math.min(size, limit + 1)), size >= 0));
        }
        byte[] bytes = output.toByteArray();
        boolean truncated = bytes.length > limit;
        if (truncated) bytes = java.util.Arrays.copyOf(bytes, limit);
        return new Object[]{bytes, Boolean.valueOf(truncated)};
    }

    private static String readHttpLine(InputStream input) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        while (line.size() < MAX_READ_BYTES) {
            int value = input.read();
            if (value < 0) throw new EOFException("incomplete HTTP chunk line");
            if (value == '\r') {
                if (input.read() != '\n') throw new IOException("invalid HTTP line ending");
                return new String(line.toByteArray(), "ISO-8859-1");
            }
            line.write(value);
        }
        throw new IOException("HTTP chunk line exceeds limit");
    }

    private static String decodeHttpBody(byte[] bytes, String contentType) {
        if (bytes == null || bytes.length == 0) return "";
        String charsetName = "UTF-8";
        String value = contentType == null ? "" : contentType;
        String lower = value.toLowerCase(Locale.ENGLISH);
        int marker = lower.indexOf("charset=");
        if (marker >= 0) {
            int start = marker + 8;
            while (start < value.length() && (value.charAt(start) == ' ' || value.charAt(start) == '\t'
                    || value.charAt(start) == '\"' || value.charAt(start) == '\'')) start++;
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

    private boolean offerObservation(Map task, Map observation, long[] size) {
        if ("STOPPED".equals(task.get("status"))) return false;
        if (size[0] > MAX_RESULT_BYTES - MAX_ERROR_BYTES) {
            task.put("truncated", Boolean.TRUE); task.put("truncateReason", "single result exceeds buffer capacity");
            finishTask(task, true); task.put("outcome", "FAILED"); return false;
        }
        List observations = (List) task.get("observations");
        if (Boolean.TRUE.equals(task.get("backpressured")) || observations.size() >= MAX_PENDING_OBSERVATIONS
                || longValue(task.get("retainedBytes"), 0L) + size[0] > MAX_RESULT_BYTES) {
            task.put("backpressured", Boolean.TRUE); return false;
        }
        observations.add(observation);
        List sizes = (List) task.get("observationSizes");
        if (sizes == null) { sizes = new ArrayList(); task.put("observationSizes", sizes); }
        sizes.add(size);
        task.put("retainedBytes", Long.valueOf(longValue(task.get("retainedBytes"), 0L) + size[0]));
        if ("error".equals(observation.get("state"))) addError(task, stringValue(observation.get("target")),
                stringValue(observation.get("stage")), stringValue(observation.get("errorCode")), stringValue(observation.get("error")));
        refreshPressure(task); return true;
    }

    private void addError(Map task, String target, String stage, String code, String message) {
        synchronized (task) {
            if ("STOPPED".equals(task.get("status"))) return;
            HashMap error = new HashMap();
            error.put("target", target);
            error.put("stage", stage);
            error.put("errorCode", code);
            error.put("error", sanitize(message));
            task.put("errorCount", Integer.valueOf(intValue(task.get("errorCount"), 0) + 1));
            List errors = (List) task.get("errors");
            long bytes = estimateSize(error, 0, true);
            long errorBytes = longValue(task.get("errorBytes"), 0L) + bytes;
            long retained = longValue(task.get("retainedBytes"), 0L) + bytes;
            if (errors.size() >= MAX_ERRORS || errorBytes > MAX_ERROR_BYTES || retained > MAX_RESULT_BYTES) {
                task.put("errorsTruncated", Boolean.TRUE);
                return;
            }
            errors.add(error);
            task.put("errorBytes", Long.valueOf(errorBytes));
            task.put("retainedBytes", Long.valueOf(retained));
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
        long cursor = integerParam(input, "cursor", 0L);
        long ackCursor = integerParam(input, "ackCursor", 0L);
        if (cursor < 0L || ackCursor < 0L || ackCursor > cursor) {
            throw new IllegalArgumentException("require 0 <= ackCursor <= cursor");
        }
        int maxItems = boundedInt(input.get("maxItems"), 128, 1, 512);
        int maxBytes = boundedInt(input.get("maxBytes"), 524288, 4096, 1048576);
        boolean includeEvidence = !Boolean.FALSE.equals(input.get("includeEvidence"));
        HashMap snapshot;
        ArrayList page = new ArrayList();
        synchronized (task) {
            long end = (long) intValue(task.get("observationOffset"), 0)
                    + ((List) task.get("observations")).size();
            if (ackCursor > end) throw new IllegalArgumentException("ackCursor exceeds available observations");
            acknowledge(task, ackCursor);
            snapshot = baseSnapshot(task);
            List observations = (List) task.get("observations");
            List sizes = (List) task.get("observationSizes");
            int base = intValue(task.get("observationOffset"), 0);
            int index = (int) Math.max(0L, Math.min(cursor - base, (long) observations.size()));
            long bytes = 0L;
            while (index < observations.size() && page.size() < maxItems) {
                long estimate = ((long[]) sizes.get(index))[includeEvidence ? 0 : 1];
                // 至少返回一项，保证单项超过预算时游标仍能推进。
                if (!page.isEmpty() && bytes + estimate > maxBytes) break;
                page.add(observations.get(index));
                bytes += estimate;
                index++;
            }
            snapshot.put("cursor", Long.valueOf(cursor));
            snapshot.put("ackCursor", task.get("observationOffset"));
            snapshot.put("nextCursor", Long.valueOf((long) base + index));
            snapshot.put("hasMore", Boolean.valueOf(index < observations.size()));
            List errors;
            if (Boolean.TRUE.equals(task.get("truncated"))) {
                errors = copyListLimited(task.get("errors"), maxItems - 1);
                HashMap limitError = new HashMap();
                limitError.put("target", "");
                limitError.put("stage", "RESULTS");
                limitError.put("errorCode", "RESULT_LIMIT");
                limitError.put("error", task.get("truncateReason"));
                errors.add(0, limitError);
            } else {
                errors = copyListLimited(task.get("errors"), maxItems);
            }
            snapshot.put("errors", errors);
        }
        // 已发布的 observation 不再修改；锁内仅选页，锁外复制响应。
        for (int i = 0; i < page.size(); i++) {
            HashMap copy = new HashMap((Map) page.get(i));
            if (!includeEvidence) copy.remove("evidence");
            page.set(i, copy);
        }
        scheduleWorkers(task);
        snapshot.put("observations", page);
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
        snapshot.put("errorCount", Integer.valueOf(intValue(task.get("errorCount"), 0)));
        snapshot.put("errorsTruncated", Boolean.valueOf(Boolean.TRUE.equals(task.get("errorsTruncated"))));
        snapshot.put("backpressured", Boolean.valueOf(Boolean.TRUE.equals(task.get("backpressured"))));
        snapshot.put("pendingItems", Integer.valueOf(((List) task.get("observations")).size()));
        snapshot.put("pendingBytes", Long.valueOf(longValue(task.get("retainedBytes"), 0L)));
        if (Boolean.TRUE.equals(task.get("truncated"))) {
            snapshot.put("truncated", Boolean.TRUE);
            snapshot.put("truncateReason", task.get("truncateReason"));
        }
        return snapshot;
    }

    private void ackTask(String id, long cursor) {
        Map task = findTask(id);
        if (task == null) return;
        synchronized (task) {
            results.put("cursor", Long.valueOf(acknowledge(task, cursor)));
        }
        scheduleWorkers(task);
        results.put("code", Integer.valueOf(200));
    }

    /** Caller holds the task lock, including when ACK and page selection share a request. */
    private long acknowledge(Map task, long cursor) {
        List observations = (List) task.get("observations");
        int base = intValue(task.get("observationOffset"), 0);
        long bounded = Math.max((long) base, Math.min(cursor, (long) base + observations.size()));
        int remove = (int) (bounded - base);
        if (remove > 0) {
            List sizes = (List) task.get("observationSizes");
            long retained = longValue(task.get("retainedBytes"), 0L);
            for (int i = 0; i < remove; i++) retained -= ((long[]) sizes.get(i))[0];
            task.put("retainedBytes", Long.valueOf(Math.max(0L, retained)));
            observations.subList(0, remove).clear();
            sizes.subList(0, remove).clear();
            task.put("observationOffset", Integer.valueOf((int) bounded));
            refreshPressure(task);
            task.notifyAll();
        }
        return bounded;
    }

    private static long integerParam(Map input, String key, long fallback) {
        Object value = input.get(key);
        if (value == null) return fallback;
        if (!(value instanceof Integer) && !(value instanceof Long)) {
            throw new IllegalArgumentException(key + " must be an integer");
        }
        return ((Number) value).longValue();
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
        scheduleWorkers(task);
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

    private void refreshPressure(Map task) {
        int count = ((List) task.get("observations")).size();
        long bytes = longValue(task.get("retainedBytes"), 0L);
        if (count >= RESULT_HIGH_ITEMS || bytes >= RESULT_HIGH_BYTES) task.put("backpressured", Boolean.TRUE);
        else if (count <= RESULT_LOW_ITEMS && bytes <= RESULT_LOW_BYTES) task.put("backpressured", Boolean.FALSE);
    }

    private void trackSocket(Map task, Socket socket) throws IOException {
        synchronized (task) {
            if ("STOPPED".equals(task.get("status"))) { closeQuietly(socket); throw new IOException("network probe task stopped"); }
            List sockets = (List) task.get("sockets");
            if (sockets == null) { sockets = new ArrayList(); task.put("sockets", sockets); }
            sockets.add(socket);
        }
    }

    private void releaseSocket(Map task, Socket socket) {
        closeQuietly(socket);
        synchronized (task) {
            List sockets = (List) task.get("sockets");
            if (sockets != null) sockets.remove(socket);
        }
    }

    private void finishTask(Map task, boolean cancelled) {
        List sockets;
        synchronized (task) {
            if ("STOPPED".equals(task.get("status"))) return;
            task.put("status", "STOPPED");
            task.put("outcome", cancelled ? "CANCELLED" : "COMPLETED");
            task.put("backpressured", Boolean.FALSE);
            task.put("finishedAt", Long.valueOf(System.currentTimeMillis()));
            task.remove("targets");
            task.remove("plan");
            task.remove("workers");
            List active = (List) task.get("sockets");
            sockets = active == null ? new ArrayList() : new ArrayList(active);
            if (active != null) active.clear();
            task.notifyAll();
        }
        for (Object socket : sockets) closeQuietly(socket);
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

    // 缓存完整结果与去掉 evidence 后的编码预算，随 ACK 同步释放。
    private static long[] observationSize(Map observation) {
        long bytes = 32L;
        long summaryBytes = 32L;
        for (Object value : observation.entrySet()) {
            Map.Entry entry = (Map.Entry) value;
            long size = 32L + estimateSize(entry.getKey(), 1, true) + estimateSize(entry.getValue(), 1, true);
            bytes += size;
            if (!"evidence".equals(entry.getKey())) summaryBytes += size;
        }
        return new long[]{bytes, summaryBytes};
    }

    // 计划按字符估算；结果缓冲和分页按编码字节加容器开销估算。
    private static long estimateChars(Object value, int depth) {
        return estimateSize(value, depth, false);
    }

    private static long estimateSize(Object value, int depth, boolean encoded) {
        if (value == null) return 4L;
        if (depth > 8) return MAX_RESULT_BYTES + 1L;
        if (value instanceof String) {
            return 16L + (encoded ? encodedStringSize((String) value) : ((String) value).length());
        }
        if (value instanceof byte[]) return 16L + ((byte[]) value).length;
        long size = 32L;
        if (value instanceof Map) {
            Iterator entries = ((Map) value).entrySet().iterator();
            while (entries.hasNext()) {
                Map.Entry entry = (Map.Entry) entries.next();
                size += 32L + estimateSize(entry.getKey(), depth + 1, encoded)
                        + estimateSize(entry.getValue(), depth + 1, encoded);
                if (size > MAX_RESULT_BYTES) break;
            }
        } else if (value instanceof List) {
            List values = (List) value;
            for (int i = 0; i < values.size(); i++) {
                size += 8L + estimateSize(values.get(i), depth + 1, encoded);
                if (size > MAX_RESULT_BYTES) break;
            }
        } else {
            String text = String.valueOf(value);
            size += encoded ? encodedStringSize(text) : text.length();
        }
        return size;
    }

    // Java 序列化字符串使用 modified UTF-8；逐字符计数避免创建编码数组。
    private static long encodedStringSize(String value) {
        long bytes = 0L;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            bytes += c > 0 && c < 128 ? 1 : c < 2048 ? 2 : 3;
        }
        return bytes;
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
        if (name.indexOf("NoRouteToHost") >= 0) return "NO_ROUTE";
        if (name.indexOf("ConnectException") >= 0 && messageOf(error).toLowerCase(Locale.ENGLISH).indexOf("refused") >= 0) return "REFUSED";
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
        return null;
    }
}
