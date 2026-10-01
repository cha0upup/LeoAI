package org.leo.web.controller.agentnode.httpsender;

import org.leo.core.agent.capability.HttpSenderCapable;
import org.leo.core.util.ApiResponse;
import org.leo.web.util.ControllerUtil;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * HTTP 发包控制器（Repeater + Fuzzer）
 * 前端 API 入口，对应 HttpSenderService
 */
@RestController
@RequestMapping("/agent-node/http-sender")
public class HttpSenderController {

    // ==================== Repeater ====================

    /**
     * 发送原始 HTTP 报文
     *
     * @param params 请求参数：
     *               - sessionId: 会话ID（必需）
     *               - rawHttp: 原始 HTTP 报文文本（必需）
     *               - targetHost: 目标主机（可选，默认从 Host 头解析）
     *               - targetPort: 目标端口（可选，默认 80/443）
     *               - useTls: 是否 HTTPS（可选，默认 false）
     *               - followRedirects: 是否跟随重定向（可选，默认 false）
     *               - connectTimeout: 连接超时毫秒（可选，默认 0=使用默认）
     *               - readTimeout: 读取超时毫秒（可选，默认 0=使用默认）
     */
    @RequestMapping(value = "/send", method = RequestMethod.POST)
    public HashMap<String, Object> send(@RequestBody HashMap<String, Object> params) {
        try {
            String rawHttp = ControllerUtil.getRequiredStringParam(params, "rawHttp");
            String targetHost = ControllerUtil.getStr(params, "targetHost");
            int targetPort = ControllerUtil.getInt(params, "targetPort", 0);
            boolean useTls = ControllerUtil.getBool(params, "useTls");
            boolean followRedirects = ControllerUtil.getBool(params, "followRedirects");
            int connectTimeout = ControllerUtil.getInt(params, "connectTimeout", 0);
            int readTimeout = ControllerUtil.getInt(params, "readTimeout", 0);
            requireNonNegative(connectTimeout, "connectTimeout");
            requireNonNegative(readTimeout, "readTimeout");
            if (targetPort <= 0) {
                targetPort = useTls ? 443 : 80;
            }
            requireValidPort(targetPort);
            int finalTargetPort = targetPort;
            return ControllerUtil.handleCapabilityCall(params, HttpSenderCapable.class, "发送请求失败",
                    node -> node.sendRawHttp(rawHttp, targetHost, finalTargetPort, useTls, followRedirects, connectTimeout, readTimeout));
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        }
    }

    // ==================== Fuzzer ====================

    /**
     * 启动 Fuzzer 任务
     *
     * @param params 请求参数：
     *               - sessionId: 会话ID（必需）
     *               - rawHttp: 原始 HTTP 报文模板，含 {{变量名}} 标记（必需）
     *               - payloads: payload 映射 {变量名: [值列表]}（必需）
     *               - targetHost: 目标主机（可选）
     *               - targetPort: 目标端口（可选）
     *               - useTls: 是否 HTTPS（可选，默认 false）
     *               - threads: 并发线程数（可选，默认 5）
     *               - delayMs: 请求间隔毫秒（可选，默认 0）
     *               - matchRules: 匹配规则（可选）
     */
    @RequestMapping(value = "/fuzz/start", method = RequestMethod.POST)
    public HashMap<String, Object> startFuzz(@RequestBody HashMap<String, Object> params) {
        try {
            String rawHttp = ControllerUtil.getRequiredStringParam(params, "rawHttp");
            Map<String, List<String>> payloads = getPayloads(params.get("payloads"));
            String targetHost = ControllerUtil.getStr(params, "targetHost");
            int targetPort = ControllerUtil.getInt(params, "targetPort", 0);
            boolean useTls = ControllerUtil.getBool(params, "useTls");
            int threads = ControllerUtil.getInt(params, "threads", 5);
            int delayMs = ControllerUtil.getInt(params, "delayMs", 0);
            if (threads < 1 || threads > 100) {
                throw new IllegalArgumentException("threads必须在1到100之间");
            }
            requireNonNegative(delayMs, "delayMs");
            Map<String, Object> matchRules = getOptionalStringKeyMap(params.get("matchRules"), "matchRules");
            if (targetPort <= 0) {
                targetPort = useTls ? 443 : 80;
            }
            requireValidPort(targetPort);
            int finalTargetPort = targetPort;
            return ControllerUtil.handleCapabilityCall(params, HttpSenderCapable.class, "启动 Fuzzer 失败",
                    node -> node.startFuzz(rawHttp, payloads, targetHost, finalTargetPort, useTls, threads, delayMs, matchRules));
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        }
    }

    /**
     * 查询 Fuzzer 任务结果
     *
     * @param params 请求参数：
     *               - sessionId: 会话ID（必需）
     *               - taskId: 任务ID（必需）
     */
    @RequestMapping(value = "/fuzz/query", method = RequestMethod.POST)
    public HashMap<String, Object> queryFuzz(@RequestBody HashMap<String, Object> params) {
        try {
            String taskId = ControllerUtil.getRequiredStringParam(params, "taskId");
            return ControllerUtil.handleCapabilityCall(params, HttpSenderCapable.class, "查询 Fuzzer 任务失败", node -> node.queryFuzz(taskId));
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        }
    }

    /**
     * 停止 Fuzzer 任务
     *
     * @param params 请求参数：
     *               - sessionId: 会话ID（必需）
     *               - taskId: 任务ID（必需）
     */
    @RequestMapping(value = "/fuzz/stop", method = RequestMethod.POST)
    public HashMap<String, Object> stopFuzz(@RequestBody HashMap<String, Object> params) {
        try {
            String taskId = ControllerUtil.getRequiredStringParam(params, "taskId");
            return ControllerUtil.handleCapabilityCall(params, HttpSenderCapable.class, "停止 Fuzzer 任务失败", node -> node.stopFuzz(taskId));
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        }
    }

    private Map<String, List<String>> getPayloads(Object value) {
        if (!(value instanceof Map<?, ?> payloadMap) || payloadMap.isEmpty()) {
            throw new IllegalArgumentException("payloads必须是非空对象");
        }
        Map<String, List<String>> payloads = new LinkedHashMap<>();
        payloadMap.forEach((key, rawValues) -> {
            if (!(key instanceof String variableName) || variableName.isBlank()) {
                throw new IllegalArgumentException("payloads变量名不能为空");
            }
            if (!(rawValues instanceof List<?> values)) {
                throw new IllegalArgumentException("payloads." + variableName + "必须是字符串数组");
            }
            List<String> stringValues = new ArrayList<>();
            for (Object rawValue : values) {
                if (!(rawValue instanceof String stringValue)) {
                    throw new IllegalArgumentException("payloads." + variableName + "必须是字符串数组");
                }
                stringValues.add(stringValue);
            }
            payloads.put(variableName.trim(), stringValues);
        });
        return payloads;
    }

    private Map<String, Object> getOptionalStringKeyMap(Object value, String fieldName) {
        if (value == null) {
            return null;
        }
        if (!(value instanceof Map<?, ?> source)) {
            throw new IllegalArgumentException(fieldName + "必须是对象");
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        source.forEach((key, item) -> {
            if (!(key instanceof String stringKey)) {
                throw new IllegalArgumentException(fieldName + "只能包含字符串键");
            }
            copy.put(stringKey, item);
        });
        return copy;
    }

    private void requireValidPort(int port) {
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("targetPort必须在1到65535之间");
        }
    }

    private void requireNonNegative(int value, String fieldName) {
        if (value < 0) {
            throw new IllegalArgumentException(fieldName + "不能为负数");
        }
    }
}
