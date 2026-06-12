package org.leo.core.puppet.service;

import org.leo.core.net.Communication;
import org.leo.core.net.layer.RequestLayer;
import org.leo.core.net.layer.ResponseLayer;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

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
public class HttpSenderService extends ComponentService {

    private static final String COMPONENT_NAME = "HttpRequestComponent";
    private static final int DEFAULT_FUZZ_THREADS = 5;
    private static final int MAX_FUZZ_THREADS = 50;
    private static final long TASK_TTL_MILLIS = 30L * 60L * 1000L;

    // taskId -> FuzzTask
    private final ConcurrentHashMap<String, Map<String, Object>> fuzzTasks = new ConcurrentHashMap<String, Map<String, Object>>();

    public HttpSenderService(Communication communication,
                             List<RequestLayer> requestLayers,
                             List<ResponseLayer> responseLayers) {
        super(communication, requestLayers, responseLayers);
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
        String host = (targetHost != null && targetHost.length() > 0) ? targetHost : headers.get("Host");
        if (host == null || host.length() == 0) {
            throw new IllegalArgumentException("targetHost is required (or Host header must be present)");
        }
        // 去掉 Host 头中可能带的端口
        String pureHost = host.contains(":") ? host.substring(0, host.indexOf(':')) : host;

        String portPart = "";
        if ((useTls && targetPort != 443 && targetPort > 0) || (!useTls && targetPort != 80 && targetPort > 0)) {
            portPart = ":" + targetPort;
        }
        String url = scheme + "://" + pureHost + portPart + uri;

        // 更新 Host 头为实际目标
        headers.put("Host", pureHost + portPart);

        // 构建 Component 参数
        HashMap<String, Object> params = new HashMap<String, Object>();
        params.put("method", method);
        params.put("url", url);
        params.put("headers", new HashMap<String, String>(headers));
        if (body != null && body.length() > 0) {
            params.put("body", body);
        }
        if (connectTimeout > 0) {
            params.put("connectTimeout", Integer.valueOf(connectTimeout));
        }
        if (readTimeout > 0) {
            params.put("readTimeout", Integer.valueOf(readTimeout));
        }
        params.put("followRedirects", Boolean.valueOf(followRedirects));

        Map<String, Object> result = invokeComponent(COMPONENT_NAME, params);

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
    public Map<String, Object> startFuzz(String rawHttp, Map<String, List<String>> payloads,
                                         String targetHost, int targetPort, boolean useTls,
                                         int threads, int delayMs,
                                         Map<String, Object> matchRules) throws Exception {
        cleanupExpiredTasks();

        if (rawHttp == null || rawHttp.trim().length() == 0) {
            throw new IllegalArgumentException("rawHttp cannot be empty");
        }
        if (payloads == null || payloads.isEmpty()) {
            throw new IllegalArgumentException("payloads cannot be empty");
        }

        // 生成所有 payload 组合
        List<Map<String, String>> combinations = generateCombinations(payloads);
        if (combinations.isEmpty()) {
            throw new IllegalArgumentException("payloads generated 0 combinations");
        }

        if (threads < 1) threads = 1;
        if (threads > MAX_FUZZ_THREADS) threads = MAX_FUZZ_THREADS;
        if (threads > combinations.size()) threads = combinations.size();

        String taskId = UUID.randomUUID().toString();

        Map<String, Object> task = new ConcurrentHashMap<String, Object>();
        task.put("taskId", taskId);
        task.put("status", "RUNNING");
        task.put("total", Integer.valueOf(combinations.size()));
        task.put("completed", new AtomicInteger(0));
        task.put("results", Collections.synchronizedList(new ArrayList<Map<String, Object>>()));
        task.put("createdAt", Long.valueOf(System.currentTimeMillis()));
        task.put("rawTemplate", rawHttp);
        task.put("targetHost", targetHost);
        task.put("targetPort", Integer.valueOf(targetPort));
        task.put("useTls", Boolean.valueOf(useTls));
        if (matchRules != null) {
            task.put("matchRules", matchRules);
        }

        fuzzTasks.put(taskId, task);

        // 启动线程池
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        task.put("executor", pool);

        for (int i = 0; i < combinations.size(); i++) {
            final Map<String, String> combo = combinations.get(i);
            final int index = i;
            final String tId = taskId;
            final String rawTemplate = rawHttp;
            final String host = targetHost;
            final int port = targetPort;
            final boolean tls = useTls;
            final int delay = delayMs;
            final Map<String, Object> rules = matchRules;

            // 用 Thread + Runnable 模式避免匿名内部类（Java 1.6 COMPONENT_GUIDE 不适用于 service 层但保持风格一致）
            pool.execute(new FuzzWorker(this, tId, rawTemplate, combo, host, port, tls, delay, index, rules));
        }
        pool.shutdown();

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
        Map<String, Object> task = fuzzTasks.get(taskId);
        if (task == null) {
            HashMap<String, Object> result = new HashMap<String, Object>();
            result.put("code", Integer.valueOf(404));
            result.put("msg", "fuzz task not found: " + taskId);
            return result;
        }

        AtomicInteger completed = (AtomicInteger) task.get("completed");
        int total = ((Number) task.get("total")).intValue();
        int done = completed != null ? completed.get() : 0;

        // 自动标记完成
        if (done >= total && "RUNNING".equals(task.get("status"))) {
            task.put("status", "FINISHED");
            task.put("finishedAt", Long.valueOf(System.currentTimeMillis()));
            task.remove("executor");
        }

        HashMap<String, Object> result = new HashMap<String, Object>();
        result.put("code", Integer.valueOf(200));
        result.put("taskId", taskId);
        result.put("status", task.get("status"));
        result.put("total", Integer.valueOf(total));
        result.put("completed", Integer.valueOf(done));
        result.put("results", task.get("results"));
        result.put("createdAt", task.get("createdAt"));
        result.put("finishedAt", task.get("finishedAt"));
        return result;
    }

    /**
     * 停止 Fuzzer 任务
     */
    public Map<String, Object> stopFuzz(String taskId) {
        Map<String, Object> task = fuzzTasks.get(taskId);
        if (task == null) {
            HashMap<String, Object> result = new HashMap<String, Object>();
            result.put("code", Integer.valueOf(404));
            result.put("msg", "fuzz task not found: " + taskId);
            return result;
        }

        task.put("status", "STOPPED");
        task.put("finishedAt", Long.valueOf(System.currentTimeMillis()));

        Object executor = task.get("executor");
        if (executor instanceof ExecutorService) {
            ((ExecutorService) executor).shutdownNow();
        }
        task.remove("executor");

        HashMap<String, Object> result = new HashMap<String, Object>();
        result.put("code", Integer.valueOf(200));
        result.put("msg", "fuzzer stopped");
        return result;
    }

    // ==================== 内部：Fuzzer Worker ====================

    /**
     * Fuzzer 工作线程（独立类避免匿名内部类）
     */
    static class FuzzWorker implements Runnable {
        private final HttpSenderService service;
        private final String taskId;
        private final String rawTemplate;
        private final Map<String, String> combo;
        private final String host;
        private final int port;
        private final boolean tls;
        private final int delayMs;
        private final int index;
        private final Map<String, Object> matchRules;

        FuzzWorker(HttpSenderService service, String taskId, String rawTemplate,
                   Map<String, String> combo, String host, int port, boolean tls,
                   int delayMs, int index, Map<String, Object> matchRules) {
            this.service = service;
            this.taskId = taskId;
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
            Map<String, Object> task = service.fuzzTasks.get(taskId);
            if (task == null || "STOPPED".equals(task.get("status"))) {
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

                if ("STOPPED".equals(task.get("status"))) {
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
                entry.put("success", Boolean.TRUE);
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
                if (task != null) {
                    List<Map<String, Object>> results = (List<Map<String, Object>>) task.get("results");
                    if (results != null) {
                        results.add(entry);
                    }
                    AtomicInteger completed = (AtomicInteger) task.get("completed");
                    if (completed != null) {
                        completed.incrementAndGet();
                    }
                }
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
    static Map<String, Object> parseRawHttp(String rawHttp) {
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

        // 解析 headers
        LinkedHashMap<String, String> headers = new LinkedHashMap<String, String>();
        for (int i = 1; i < headerLines.length; i++) {
            String line = headerLines[i];
            int colonIdx = line.indexOf(':');
            if (colonIdx > 0) {
                String key = line.substring(0, colonIdx).trim();
                String value = line.substring(colonIdx + 1).trim();
                headers.put(key, value);
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
    static String renderTemplate(String template, Map<String, String> variables) {
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
    static List<Map<String, String>> generateCombinations(Map<String, List<String>> payloads) {
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
                continue;
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
    static boolean evaluateMatch(Map<String, Object> resp, Map<String, Object> rules) {
        if (rules == null || rules.isEmpty()) {
            return false;
        }

        // statusCode 匹配
        Object statusRule = rules.get("statusCode");
        if (statusRule != null) {
            Object actualStatus = resp.get("statusCode");
            if (statusRule instanceof List) {
                if (!((List) statusRule).contains(actualStatus)) {
                    return false;
                }
            } else if (statusRule instanceof Number) {
                if (!statusRule.equals(actualStatus)) {
                    return false;
                }
            }
        }

        // body 内容匹配
        Object bodyObj = resp.get("body");
        String bodyStr = bodyObj instanceof String ? (String) bodyObj : "";

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
            if (bodyStr.length() < ((Number) minLen).intValue()) {
                return false;
            }
        }

        Object maxLen = rules.get("bodyLengthMax");
        if (maxLen instanceof Number) {
            if (bodyStr.length() > ((Number) maxLen).intValue()) {
                return false;
            }
        }

        return true;
    }

    /**
     * 清理过期任务
     */
    private void cleanupExpiredTasks() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<String, Map<String, Object>>> it = fuzzTasks.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Map<String, Object>> entry = it.next();
            Map<String, Object> task = entry.getValue();
            String status = (String) task.get("status");
            if ("FINISHED".equals(status) || "STOPPED".equals(status)) {
                Object finishedAt = task.get("finishedAt");
                if (finishedAt instanceof Number) {
                    if (now - ((Number) finishedAt).longValue() > TASK_TTL_MILLIS) {
                        it.remove();
                    }
                }
            }
        }
    }
}
