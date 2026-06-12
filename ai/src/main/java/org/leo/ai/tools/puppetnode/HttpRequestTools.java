package org.leo.ai.tools.puppetnode;

import org.leo.ai.agent.AiToolContext;
import org.leo.ai.util.PuppetNodeSessionUtils;
import org.leo.ai.util.ToolResultUtils;
import org.leo.core.puppet.impl.JavaPuppetNode;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * HTTP 请求工具
 * <p>
 * 在 puppet 侧发起 HTTP 请求，用于访问目标机器内网可达的 HTTP 服务。
 * 替代通过 execOnce("curl ...") 执行 HTTP 请求的场景。
 */
@Component
public class HttpRequestTools {

    private static final String CACHE_PREFIX = "http-request:";

    @Tool("""
            在 puppet 侧发起 HTTP 请求（支持 GET/POST/PUT/DELETE/HEAD）。
            method: 请求方法（GET/POST/PUT/DELETE/HEAD）。
            headers: 每行一个 Header，格式 "Key: Value"，可为空。
            body: 请求体，GET/HEAD 时可为空。
            connectTimeout/readTimeout: 超时毫秒，传 0 使用默认值。
            followRedirects: 是否跟随重定向。
            cache: 为 true 时对 GET 请求结果按 URL 缓存（适用于 Actuator、Nacos API、云 metadata 等幂等端点）；POST/PUT/DELETE 忽略此参数。
            返回状态码、响应头和响应体。
            """)
    public Map<String, Object> httpRequest(String method, String url,
                                           String headers, String body,
                                           int connectTimeout, int readTimeout,
                                           boolean followRedirects,
                                           boolean cache) throws Exception {
        String sessionId = AiToolContext.requireSessionId();
        // 仅 GET 方法支持缓存
        if (cache && "GET".equalsIgnoreCase(method)) {
            String cacheKey = CACHE_PREFIX + "GET:" + url;
            Object cached = PuppetNodeSessionUtils.getAiContextValue(sessionId, cacheKey);
            if (cached instanceof Map<?, ?>) {
                @SuppressWarnings("unchecked")
                Map<String, Object> cachedMap = (Map<String, Object>) cached;
                return cachedMap;
            }
            JavaPuppetNode node = PuppetNodeSessionUtils.getJavaPuppetNode(sessionId);
            Map<String, String> headerMap = parseHeaders(headers);
            Map<String, Object> result = node.httpRequest(method, url, headerMap, body, connectTimeout, readTimeout, followRedirects);
            if (result != null && isSuccess(result)) {
                PuppetNodeSessionUtils.putAiContextValue(sessionId, cacheKey, result);
            }
            compressResponseBody(result);
            return result;
        }
        JavaPuppetNode node = PuppetNodeSessionUtils.getJavaPuppetNode(sessionId);
        Map<String, String> headerMap = parseHeaders(headers);
        Map<String, Object> result = node.httpRequest(method, url, headerMap, body, connectTimeout, readTimeout, followRedirects);
        compressResponseBody(result);
        return result;
    }

    // ==================== Repeater：原始报文发送 ====================

    @Tool("在 puppet 侧发送原始 HTTP 报文（Repeater 模式）。传入完整的 HTTP 请求文本（请求行 + 头 + 空行 + body），支持自定义目标主机、端口、TLS、超时。适用于精确控制请求报文的场景，如安全测试、漏洞验证、API 调试。不缓存。")
    public Map<String, Object> sendRawRequest(String rawHttp,
                                              String targetHost, int targetPort,
                                              boolean useTls, boolean followRedirects,
                                              int connectTimeout, int readTimeout) throws Exception {
        String sessionId = AiToolContext.requireSessionId();
        JavaPuppetNode node = PuppetNodeSessionUtils.getJavaPuppetNode(sessionId);
        Map<String, Object> result = node.sendRawHttp(rawHttp, targetHost, targetPort, useTls, followRedirects, connectTimeout, readTimeout);
        compressResponseBody(result);
        return result;
    }

    // ==================== Fuzzer：批量变量替换发包 ====================

    @Tool("启动 Fuzzer 批量发包任务。rawHttp 中用 {{变量名}} 标记变量位置，payloads 传入 {变量名: [值列表]} 的映射，自动生成笛卡尔积组合并发。"
            + "threads 为并发线程数（默认5，最大50），delayMs 为请求间延迟毫秒。"
            + "matchRules 可选匹配规则：{statusCode, bodyContains, bodyNotContains, bodyLengthMin, bodyLengthMax}。"
            + "返回 taskId，用 queryFuzz 轮询进度和结果，用 stopFuzz 终止。")
    public Map<String, Object> startFuzz(String rawHttp,
                                         Map<String, List<String>> payloads,
                                         String targetHost, int targetPort,
                                         boolean useTls, int threads, int delayMs,
                                         Map<String, Object> matchRules) throws Exception {
        String sessionId = AiToolContext.requireSessionId();
        JavaPuppetNode node = PuppetNodeSessionUtils.getJavaPuppetNode(sessionId);
        return node.startFuzz(rawHttp, payloads, targetHost, targetPort, useTls, threads, delayMs, matchRules);
    }

    @Tool("查询 Fuzzer 任务进度和结果。传入 startFuzz 返回的 taskId。返回 status（RUNNING/FINISHED/STOPPED）、completed/total 进度、results 列表（每条包含 payloads、statusCode、bodyLength、matched 等）。")
    public Map<String, Object> queryFuzz(String taskId) throws Exception {
        String sessionId = AiToolContext.requireSessionId();
        JavaPuppetNode node = PuppetNodeSessionUtils.getJavaPuppetNode(sessionId);
        return node.queryFuzz(taskId);
    }

    @Tool("停止正在运行的 Fuzzer 任务。传入 taskId，强制终止所有进行中的请求。")
    public Map<String, Object> stopFuzz(String taskId) throws Exception {
        String sessionId = AiToolContext.requireSessionId();
        JavaPuppetNode node = PuppetNodeSessionUtils.getJavaPuppetNode(sessionId);
        return node.stopFuzz(taskId);
    }

    // ==================== 响应体压缩 ====================

    /**
     * 压缩 HTTP 响应体。检查常见的 body 字段名（body、data、responseBody）。
     */
    private void compressResponseBody(Map<String, Object> result) {
        if (result == null) return;
        // 按优先级检查可能的响应体字段
        for (String field : new String[]{"body", "data", "responseBody"}) {
            Object val = result.get(field);
            if (val instanceof String s && s.length() > ToolResultUtils.DEFAULT_COMMAND_OUTPUT_THRESHOLD) {
                ToolResultUtils.compressMapField(result, field, ToolResultUtils.DEFAULT_COMMAND_OUTPUT_THRESHOLD);
                return;
            }
            if (val instanceof byte[] bytes && bytes.length > ToolResultUtils.DEFAULT_COMMAND_OUTPUT_THRESHOLD) {
                ToolResultUtils.compressMapField(result, field, ToolResultUtils.DEFAULT_COMMAND_OUTPUT_THRESHOLD);
                return;
            }
        }
    }

    // ==================== 私有辅助方法 ====================

    /**
     * 解析请求头字符串为 Map。
     * <p>
     * 支持格式：每行一个 Header，格式为 "Key: Value"。
     * 例如：
     * <pre>
     * Content-Type: application/json
     * Authorization: Bearer token123
     * </pre>
     *
     * @param headers 请求头字符串，可为 null 或空
     * @return 解析后的 Header Map
     */
    private Map<String, String> parseHeaders(String headers) {
        if (headers == null || headers.isBlank()) {
            return null;
        }

        Map<String, String> headerMap = new HashMap<>();
        String[] lines = headers.split("\n");
        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty()) {
                continue;
            }
            int colonIndex = line.indexOf(':');
            if (colonIndex > 0) {
                String key = line.substring(0, colonIndex).trim();
                String value = line.substring(colonIndex + 1).trim();
                if (!key.isEmpty()) {
                    headerMap.put(key, value);
                }
            }
        }

        return headerMap.isEmpty() ? null : headerMap;
    }

    private boolean isSuccess(Map<String, Object> results) {
        if (results == null) {
            return false;
        }
        Object code = results.get("code");
        return Integer.valueOf(200).equals(code);
    }
}
