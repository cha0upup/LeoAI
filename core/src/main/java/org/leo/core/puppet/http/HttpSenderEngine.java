package org.leo.core.puppet.http;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.net.URI;
import java.nio.charset.StandardCharsets;

/**
 * HTTP 发包服务（Repeater + Fuzzer）
 * <p>
 * 基于 HttpRequestService 的单包发送能力，扩展：
 * 1. 原始 HTTP 报文解析 → 结构化请求 → 发送
 * 2. Fuzzer：payload 标记替换 + 多线程并发 + 异步任务管理
 * <p>
 * Fuzzer 变量语法：{{变量名}}
 * payloads 结构：Map<变量名, List<payload值>>
 */
public abstract class HttpSenderEngine implements AutoCloseable {

    private static final int MAX_FUZZ_THREADS = 50;
    private static final int MAX_FUZZ_COMBINATIONS = 10000;
    private static final int MAX_ACTIVE_FUZZ_TASKS = 16;
    private static final int FUZZ_QUEUE_CAPACITY = 256;
    private static final long TASK_TTL_MILLIS = 30L * 60L * 1000L;

    private final ConcurrentHashMap<String, HttpFuzzTask> fuzzTasks =
            new ConcurrentHashMap<String, HttpFuzzTask>();
    private final ThreadPoolExecutor fuzzExecutor;
    private boolean closed;

    protected HttpSenderEngine() {
        this(MAX_FUZZ_THREADS, FUZZ_QUEUE_CAPACITY);
    }

    HttpSenderEngine(int workerThreads, int queueCapacity) {
        if (workerThreads < 1 || queueCapacity < 1) {
            throw new IllegalArgumentException("HTTP fuzz executor sizing values must be positive");
        }
        final AtomicInteger sequence = new AtomicInteger();
        this.fuzzExecutor = new ThreadPoolExecutor(
                workerThreads,
                workerThreads,
                60L,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<Runnable>(queueCapacity),
                new ThreadFactory() {
                    @Override
                    public Thread newThread(Runnable runnable) {
                        Thread thread = new Thread(runnable,
                                "http-fuzz-" + sequence.incrementAndGet());
                        thread.setDaemon(true);
                        return thread;
                    }
                },
                new ThreadPoolExecutor.AbortPolicy());
        this.fuzzExecutor.allowCoreThreadTimeOut(true);
    }


    protected abstract Map<String, Object> executeRequest(
            String method, String url, Map<String, String> headers, String body,
            int connectTimeout, int readTimeout, boolean followRedirects) throws Exception;

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        for (HttpFuzzTask task : fuzzTasks.values()) {
            task.stop();
        }
        fuzzTasks.clear();
        fuzzExecutor.purge();
        fuzzExecutor.shutdownNow();
    }

    // ==================== Repeater：单包发送 ====================

    /**
     * 解析原始 HTTP 报文并发送
     *
     * @param rawHttp         原始 HTTP 报文文本（请求行 + 头 + 空行 + body）
     * @param targetHost      目标主机（IP 或域名，覆盖 Host 头中的值）
     * @param targetPort      目标端口
     * @param useTls          是否使用 HTTPS
     * @param followRedirects 是否跟随重定向
     * @param connectTimeout  连接超时（毫秒，0 使用默认）
     * @param readTimeout     读取超时（毫秒，0 使用默认）
     * @return 请求结果（包含 statusCode、responseHeaders、body 等）
     */
    public Map<String, Object> sendRawHttp(String rawHttp, String targetHost, int targetPort,
                                           boolean useTls, boolean followRedirects,
                                           int connectTimeout, int readTimeout) throws Exception {
        Map<String, Object> parsed = parseRawHttp(rawHttp);

        String method = (String) parsed.get("method");
        String uri = (String) parsed.get("uri");
        Map<String, String> headers = (Map<String, String>) parsed.get("headers");
        String body = (String) parsed.get("body");

        // 构建完整 URL
        String scheme = useTls ? "https" : "http";
        String host = (targetHost != null && targetHost.trim().length() > 0)
                ? targetHost.trim() : getHeader(headers, "Host");
        if (host == null || host.length() == 0) {
            throw new IllegalArgumentException("targetHost is required (or Host header must be present)");
        }
        if (targetPort < 1 || targetPort > 65535) {
            throw new IllegalArgumentException("targetPort must be between 1 and 65535");
        }
        String pureHost = normalizeHost(host);

        String portPart = "";
        if ((useTls && targetPort != 443 && targetPort > 0) || (!useTls && targetPort != 80 && targetPort > 0)) {
            portPart = ":" + targetPort;
        }
        String requestTarget = normalizeRequestTarget(uri);
        String url = scheme + "://" + formatHostForUrl(pureHost) + portPart + requestTarget;

        // 更新 Host 头为实际目标
        putHeader(headers, "Host", formatHostForHeader(pureHost) + portPart);
        if (body != null) {
            putHeader(headers, "Content-Length",
                    String.valueOf(body.getBytes(StandardCharsets.UTF_8).length));
        } else {
            removeHeader(headers, "Content-Length");
        }

        Map<String, Object> result = executeRequest(method, url,
                new LinkedHashMap<String, String>(headers), body,
                connectTimeout, readTimeout, followRedirects);

        // 附加请求元信息到结果
        if (result == null) {
            result = new HashMap<String, Object>();
        }
        result.put("requestMethod", method);
        result.put("requestUrl", url);
        result.put("requestHeaders", headers);
        if (body != null) {
            result.put("requestBody", body);
        }

        return result;
    }

    // ==================== Fuzzer：批量发包 ====================

    /**
     * 启动 Fuzzer 任务
     *
     * @param rawHttp    原始 HTTP 报文模板（包含 {{变量名}} 标记）
     * @param payloads   payload 映射：变量名 → payload 值列表
     * @param targetHost 目标主机
     * @param targetPort 目标端口
     * @param useTls     是否 HTTPS
     * @param threads    并发线程数
     * @param delayMs    每个请求间的延迟（毫秒，0 不延迟）
     * @param matchRules 匹配规则（可为 null）
     * @return 任务信息（taskId 等）
     */
    public synchronized Map<String, Object> startFuzz(String rawHttp, Map<String, List<String>> payloads,
                                         String targetHost, int targetPort, boolean useTls,
                                         int threads, int delayMs,
                                         Map<String, Object> matchRules) throws Exception {
        if (closed) {
            throw new IllegalStateException("HTTP sender engine is closed");
        }
        cleanupExpiredTasks();
        int activeTasks = 0;
        for (HttpFuzzTask existing : fuzzTasks.values()) {
            if (existing.isRunning()) activeTasks++;
        }
        if (activeTasks >= MAX_ACTIVE_FUZZ_TASKS) {
            throw new RejectedExecutionException("too many active HTTP fuzz tasks");
        }

        if (rawHttp == null || rawHttp.trim().length() == 0) {
            throw new IllegalArgumentException("rawHttp cannot be empty");
        }
        if (payloads == null || payloads.isEmpty()) {
            throw new IllegalArgumentException("payloads cannot be empty");
        }
        if (targetPort < 1 || targetPort > 65535) {
            throw new IllegalArgumentException("targetPort must be between 1 and 65535");
        }

        // 生成所有 payload 组合
        List<Map<String, String>> combinations = generateCombinations(payloads);
        if (combinations.isEmpty()) {
            throw new IllegalArgumentException("payloads generated 0 combinations");
        }
        if (combinations.size() > MAX_FUZZ_COMBINATIONS) {
            throw new IllegalArgumentException("payload combinations exceed " + MAX_FUZZ_COMBINATIONS);
        }
        if (delayMs < 0) delayMs = 0;

        if (threads < 1) threads = 1;
        if (threads > MAX_FUZZ_THREADS) threads = MAX_FUZZ_THREADS;
        if (threads > combinations.size()) threads = combinations.size();

        String taskId = UUID.randomUUID().toString();

        HttpFuzzTask task = new HttpFuzzTask(taskId, combinations.size(), System.currentTimeMillis());
        fuzzTasks.put(taskId, task);

        AtomicInteger nextIndex = new AtomicInteger();
        List<Future<?>> workers = new ArrayList<Future<?>>(threads);
        try {
            for (int i = 0; i < threads; i++) {
                workers.add(fuzzExecutor.submit(new FuzzRunner(
                        this, task, rawHttp, combinations, nextIndex,
                        targetHost, targetPort, useTls, delayMs, matchRules)));
            }
            task.attachWorkers(workers);
        } catch (RuntimeException error) {
            for (Future<?> worker : workers) worker.cancel(true);
            fuzzExecutor.purge();
            task.stop();
            fuzzTasks.remove(taskId, task);
            throw error;
        }

        HashMap<String, Object> result = new HashMap<String, Object>();
        result.put("code", Integer.valueOf(200));
        result.put("taskId", taskId);
        result.put("total", Integer.valueOf(combinations.size()));
        result.put("threads", Integer.valueOf(threads));
        result.put("msg", "fuzzer started");
        return result;
    }

    /**
     * 查询 Fuzzer 任务结果
     */
    public Map<String, Object> queryFuzz(String taskId) {
        HttpFuzzTask task = fuzzTasks.get(taskId);
        if (task == null) {
            HashMap<String, Object> result = new HashMap<String, Object>();
            result.put("code", Integer.valueOf(404));
            result.put("msg", "fuzz task not found: " + taskId);
            return result;
        }

        return task.snapshot();
    }

    /**
     * 停止 Fuzzer 任务
     */
    public Map<String, Object> stopFuzz(String taskId) {
        HttpFuzzTask task = fuzzTasks.get(taskId);
        if (task == null) {
            HashMap<String, Object> result = new HashMap<String, Object>();
            result.put("code", Integer.valueOf(404));
            result.put("msg", "fuzz task not found: " + taskId);
            return result;
        }

        boolean stopped = task.stop();
        fuzzExecutor.purge();

        HashMap<String, Object> result = new HashMap<String, Object>();
        result.put("code", Integer.valueOf(200));
        result.put("msg", stopped ? "fuzzer stopped" : "fuzzer already completed");
        return result;
    }

    // ==================== 内部：Fuzzer Worker ====================

    /** A bounded number of runners pull combinations from one task-local cursor. */
    static class FuzzRunner implements Runnable {
        private final HttpSenderEngine service;
        private final HttpFuzzTask task;
        private final String rawTemplate;
        private final List<Map<String, String>> combinations;
        private final AtomicInteger nextIndex;
        private final String host;
        private final int port;
        private final boolean tls;
        private final int delayMs;
        private final Map<String, Object> matchRules;

        FuzzRunner(HttpSenderEngine service, HttpFuzzTask task, String rawTemplate,
                   List<Map<String, String>> combinations, AtomicInteger nextIndex,
                   String host, int port, boolean tls, int delayMs,
                   Map<String, Object> matchRules) {
            this.service = service;
            this.task = task;
            this.rawTemplate = rawTemplate;
            this.combinations = combinations;
            this.nextIndex = nextIndex;
            this.host = host;
            this.port = port;
            this.tls = tls;
            this.delayMs = delayMs;
            this.matchRules = matchRules;
        }

        @Override
        public void run() {
            while (!task.isStopped()) {
                int index = nextIndex.getAndIncrement();
                if (index >= combinations.size()) return;
                new FuzzWorker(service, task, rawTemplate, combinations.get(index),
                        host, port, tls, delayMs, index, matchRules).run();
            }
        }
    }

    /**
     * Fuzzer 工作线程（独立类避免匿名内部类）
     */
    static class FuzzWorker implements Runnable {
        private final HttpSenderEngine service;
        private final HttpFuzzTask task;
        private final String rawTemplate;
        private final Map<String, String> combo;
        private final String host;
        private final int port;
        private final boolean tls;
        private final int delayMs;
        private final int index;
        private final Map<String, Object> matchRules;

        FuzzWorker(HttpSenderEngine service, HttpFuzzTask task, String rawTemplate,
                   Map<String, String> combo, String host, int port, boolean tls,
                   int delayMs, int index, Map<String, Object> matchRules) {
            this.service = service;
            this.task = task;
            this.rawTemplate = rawTemplate;
            this.combo = combo;
            this.host = host;
            this.port = port;
            this.tls = tls;
            this.delayMs = delayMs;
            this.index = index;
            this.matchRules = matchRules;
        }

        public void run() {
            if (task.isStopped()) {
                return;
            }

            HashMap<String, Object> entry = new HashMap<String, Object>();
            entry.put("index", Integer.valueOf(index));
            entry.put("payloads", new HashMap<String, String>(combo));

            long startTime = System.currentTimeMillis();
            try {
                if (delayMs > 0 && index > 0) {
                    Thread.sleep(delayMs);
                }

                if (task.isStopped()) {
                    return;
                }

                // 替换 payload 变量
                String rendered = renderTemplate(rawTemplate, combo);

                // 发送请求
                Map<String, Object> resp = service.sendRawHttp(rendered, host, port, tls, false, 0, 0);

                long elapsed = System.currentTimeMillis() - startTime;
                entry.put("elapsed", Long.valueOf(elapsed));

                if (resp != null) {
                    entry.put("statusCode", resp.get("statusCode"));
                    entry.put("bodyType", resp.get("bodyType"));
                    // body 可能很长，提取长度和前 500 字符摘要
                    Object body = resp.get("body");
                    if (body instanceof String) {
                        String bodyStr = (String) body;
                        entry.put("bodyLength", Integer.valueOf(bodyStr.length()));
                        if (bodyStr.length() > 500) {
                            entry.put("bodyPreview", bodyStr.substring(0, 500));
                        } else {
                            entry.put("bodyPreview", bodyStr);
                        }
                    } else if (body instanceof byte[]) {
                        entry.put("bodyLength", Integer.valueOf(((byte[]) body).length));
                    }
                    entry.put("responseHeaders", resp.get("responseHeaders"));

                    // 匹配规则判定
                    if (matchRules != null) {
                        entry.put("matched", Boolean.valueOf(evaluateMatch(resp, matchRules)));
                    }
                }
                Object resultCode = resp == null ? null : resp.get("code");
                boolean successful = resultCode instanceof Number code
                        && code.intValue() >= 200 && code.intValue() < 300;
                entry.put("success", Boolean.valueOf(successful));
                if (!successful && resp != null && resp.get("msg") != null) {
                    entry.put("error", String.valueOf(resp.get("msg")));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                entry.put("success", Boolean.FALSE);
                entry.put("error", "interrupted");
                return;
            } catch (Exception e) {
                long elapsed = System.currentTimeMillis() - startTime;
                entry.put("elapsed", Long.valueOf(elapsed));
                entry.put("success", Boolean.FALSE);
                entry.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
            } finally {
                task.record(entry);
            }
        }
    }

    // ==================== 原始 HTTP 报文解析 ====================

    /**
     * 解析原始 HTTP 报文为结构化数据
     *
     * @param rawHttp 原始报文文本
     * @return {method, uri, httpVersion, headers(Map), body}
     */
    public static Map<String, Object> parseRawHttp(String rawHttp) {
        if (rawHttp == null || rawHttp.trim().length() == 0) {
            throw new IllegalArgumentException("raw HTTP request cannot be empty");
        }

        // 统一换行符
        rawHttp = rawHttp.replace("\r\n", "\n").replace("\r", "\n");

        // 分离头部和 body（空行分隔）
        String headersPart;
        String body = null;
        int emptyLineIdx = rawHttp.indexOf("\n\n");
        if (emptyLineIdx >= 0) {
            headersPart = rawHttp.substring(0, emptyLineIdx);
            body = rawHttp.substring(emptyLineIdx + 2);
            if (body.length() == 0) {
                body = null;
            }
        } else {
            headersPart = rawHttp;
        }

        String[] headerLines = headersPart.split("\n");
        if (headerLines.length == 0) {
            throw new IllegalArgumentException("invalid raw HTTP: no request line");
        }

        // 解析请求行：METHOD URI HTTP/x.x
        String requestLine = headerLines[0].trim();
        String[] parts = requestLine.split("\\s+");
        if (parts.length < 2) {
            throw new IllegalArgumentException("invalid request line: " + requestLine);
        }

        String method = parts[0].toUpperCase();
        String uri = parts[1];
        String httpVersion = parts.length >= 3 ? parts[2] : "HTTP/1.1";
        if (!method.matches("[A-Z][A-Z0-9!#$%&'*+.^_`|~-]{0,31}")) {
            throw new IllegalArgumentException("invalid HTTP method: " + method);
        }
        if (!httpVersion.matches("HTTP/\\d(?:\\.\\d)?")) {
            throw new IllegalArgumentException("invalid HTTP version: " + httpVersion);
        }

        // 解析 headers
        LinkedHashMap<String, String> headers = new LinkedHashMap<String, String>();
        for (int i = 1; i < headerLines.length; i++) {
            String line = headerLines[i];
            int colonIdx = line.indexOf(':');
            if (colonIdx > 0) {
                String key = line.substring(0, colonIdx).trim();
                String value = line.substring(colonIdx + 1).trim();
                if (key.length() > 0) headers.put(key, value);
            }
        }

        HashMap<String, Object> result = new HashMap<String, Object>();
        result.put("method", method);
        result.put("uri", uri);
        result.put("httpVersion", httpVersion);
        result.put("headers", headers);
        result.put("body", body);
        return result;
    }

    // ==================== Fuzzer 辅助 ====================

    /**
     * 替换模板中的 {{变量名}} 标记
     */
    public static String renderTemplate(String template, Map<String, String> variables) {
        String result = template;
        for (Map.Entry<String, String> entry : variables.entrySet()) {
            result = result.replace("{{" + entry.getKey() + "}}", entry.getValue());
        }
        return result;
    }

    /**
     * 生成 payload 笛卡尔积组合
     * 例如 {"user": ["admin","root"], "pass": ["123","456"]}
     * → [{user=admin,pass=123}, {user=admin,pass=456}, {user=root,pass=123}, {user=root,pass=456}]
     */
    public static List<Map<String, String>> generateCombinations(Map<String, List<String>> payloads) {
        List<String> keys = new ArrayList<String>(payloads.keySet());
        List<Map<String, String>> result = new ArrayList<Map<String, String>>();

        if (keys.isEmpty()) {
            return result;
        }

        // 初始化第一个变量的值
        String firstKey = keys.get(0);
        List<String> firstValues = payloads.get(firstKey);
        if (firstValues == null || firstValues.isEmpty()) {
            return result;
        }
        for (int i = 0; i < firstValues.size(); i++) {
            HashMap<String, String> combo = new HashMap<String, String>();
            combo.put(firstKey, firstValues.get(i));
            result.add(combo);
        }

        // 逐个变量做笛卡尔积扩展
        for (int k = 1; k < keys.size(); k++) {
            String key = keys.get(k);
            List<String> values = payloads.get(key);
            if (values == null || values.isEmpty()) {
                return Collections.emptyList();
            }
            if ((long) result.size() * (long) values.size() > MAX_FUZZ_COMBINATIONS) {
                throw new IllegalArgumentException("payload combinations exceed " + MAX_FUZZ_COMBINATIONS);
            }

            List<Map<String, String>> expanded = new ArrayList<Map<String, String>>();
            for (int i = 0; i < result.size(); i++) {
                Map<String, String> existing = result.get(i);
                for (int j = 0; j < values.size(); j++) {
                    HashMap<String, String> newCombo = new HashMap<String, String>(existing);
                    newCombo.put(key, values.get(j));
                    expanded.add(newCombo);
                }
            }
            result = expanded;
        }

        return result;
    }

    /**
     * 匹配规则判定
     * 支持规则：
     * - statusCode: 匹配状态码（精确或列表）
     * - bodyContains: 响应体包含指定字符串
     * - bodyNotContains: 响应体不包含指定字符串
     * - bodyLengthMin / bodyLengthMax: 响应体长度范围
     * - headerContains: 响应头包含指定值
     */
    public static boolean evaluateMatch(Map<String, Object> resp, Map<String, Object> rules) {
        if (rules == null || rules.isEmpty()) {
            return false;
        }

        // statusCode 匹配
        Object statusRule = rules.get("statusCode");
        if (statusRule != null) {
            Object actualStatus = resp.get("statusCode");
            if (statusRule instanceof List) {
                boolean found = false;
                for (Object expected : (List) statusRule) {
                    if (sameNumber(expected, actualStatus)) { found = true; break; }
                }
                if (!found) {
                    return false;
                }
            } else if (statusRule instanceof Number) {
                if (!sameNumber(statusRule, actualStatus)) {
                    return false;
                }
            }
        }

        // body 内容匹配
        Object bodyObj = resp.get("body");
        String bodyStr = bodyObj instanceof String ? (String) bodyObj : "";
        int bodyLength = bodyObj instanceof byte[] ? ((byte[]) bodyObj).length : bodyStr.length();

        Object bodyContains = rules.get("bodyContains");
        if (bodyContains instanceof String) {
            if (!bodyStr.contains((String) bodyContains)) {
                return false;
            }
        }

        Object bodyNotContains = rules.get("bodyNotContains");
        if (bodyNotContains instanceof String) {
            if (bodyStr.contains((String) bodyNotContains)) {
                return false;
            }
        }

        // body 长度范围
        Object minLen = rules.get("bodyLengthMin");
        if (minLen instanceof Number) {
            if (bodyLength < ((Number) minLen).intValue()) {
                return false;
            }
        }

        Object maxLen = rules.get("bodyLengthMax");
        if (maxLen instanceof Number) {
            if (bodyLength > ((Number) maxLen).intValue()) {
                return false;
            }
        }

        Object headerContains = rules.get("headerContains");
        if (headerContains instanceof String) {
            Object rawHeaders = resp.get("responseHeaders");
            if (!(rawHeaders instanceof Map) || !headersContain((Map<?, ?>) rawHeaders, (String) headerContains)) {
                return false;
            }
        }

        return true;
    }

    private static boolean headersContain(Map<?, ?> headers, String expected) {
        for (Map.Entry<?, ?> entry : headers.entrySet()) {
            if (String.valueOf(entry.getKey()).contains(expected)) return true;
            Object value = entry.getValue();
            if (value instanceof Iterable<?>) {
                for (Object item : (Iterable<?>) value) {
                    if (String.valueOf(item).contains(expected)) return true;
                }
            } else if (value != null && String.valueOf(value).contains(expected)) {
                return true;
            }
        }
        return false;
    }

    private static boolean sameNumber(Object left, Object right) {
        return left instanceof Number && right instanceof Number
                && ((Number) left).longValue() == ((Number) right).longValue();
    }

    private static String getHeader(Map<String, String> headers, String name) {
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (name.equalsIgnoreCase(entry.getKey())) return entry.getValue();
        }
        return null;
    }

    private static void putHeader(Map<String, String> headers, String name, String value) {
        removeHeader(headers, name);
        headers.put(name, value);
    }

    private static void removeHeader(Map<String, String> headers, String name) {
        Iterator<String> iterator = headers.keySet().iterator();
        while (iterator.hasNext()) {
            if (name.equalsIgnoreCase(iterator.next())) iterator.remove();
        }
    }

    private static String normalizeHost(String authority) {
        try {
            String value = authority.trim();
            URI parsed = new URI("http://" + value);
            String host = parsed.getHost();
            if (host != null && host.length() > 0) return host;
        } catch (Exception ignored) {
        }
        String value = authority.trim();
        if (value.startsWith("[") && value.contains("]")) {
            return value.substring(1, value.indexOf(']'));
        }
        int firstColon = value.indexOf(':');
        int lastColon = value.lastIndexOf(':');
        return firstColon > 0 && firstColon == lastColon ? value.substring(0, firstColon) : value;
    }

    private static String formatHostForUrl(String host) {
        return host.indexOf(':') >= 0 ? "[" + host + "]" : host;
    }

    private static String formatHostForHeader(String host) {
        return formatHostForUrl(host);
    }

    private static String normalizeRequestTarget(String requestTarget) {
        if (requestTarget == null || requestTarget.length() == 0) return "/";
        try {
            URI parsed = new URI(requestTarget);
            if (parsed.isAbsolute()) {
                String path = parsed.getRawPath();
                if (path == null || path.length() == 0) path = "/";
                return parsed.getRawQuery() == null ? path : path + "?" + parsed.getRawQuery();
            }
        } catch (Exception ignored) {
        }
        return requestTarget.startsWith("/") ? requestTarget : "/" + requestTarget;
    }

    /**
     * 清理过期任务
     */
    private void cleanupExpiredTasks() {
        long now = System.currentTimeMillis();
        for (Map.Entry<String, HttpFuzzTask> entry : fuzzTasks.entrySet()) {
            HttpFuzzTask task = entry.getValue();
            if (task.isExpired(now, TASK_TTL_MILLIS)) {
                fuzzTasks.remove(entry.getKey(), task);
            }
        }
    }
}
