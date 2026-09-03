package org.leo.web.controller.puppetnode.scan;

import org.leo.core.puppet.capability.NetworkProbeCapable;
import org.leo.core.util.ApiResponse;
import org.leo.web.service.NetworkProbeAnalysisService;
import org.leo.web.util.ControllerUtil;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Entry point for the unified node-side network probe plan.
 *
 * <p>The service validates the bounded plan before it reaches a node. The
 * node still repeats these limits because component calls may originate from
 * other trusted service paths.</p>
 */
@RestController
@RequestMapping("/puppet-node/network-probe")
public class NetworkProbeController {

    private static final int MAX_TARGETS = 128;
    private static final int MAX_STAGES = 8;
    private static final int MAX_THREADS = 64;
    private static final int MAX_TIMEOUT_MS = 300000;
    private static final int MAX_READ_BYTES = 8192;
    private static final int MAX_HEADERS = 32;
    private static final int MAX_REQUEST_CHARS = 8192;

    private final NetworkProbeAnalysisService analysisService;

    public NetworkProbeController(NetworkProbeAnalysisService analysisService) {
        this.analysisService = analysisService;
    }

    @RequestMapping(value = "/capabilities", method = RequestMethod.POST)
    public java.util.HashMap<String, Object> capabilities(@RequestBody java.util.HashMap<String, Object> params) {
        return ControllerUtil.handleCapabilityCall(params, NetworkProbeCapable.class,
                "获取网络探测能力失败", NetworkProbeCapable::networkProbeCapabilities);
    }

    @RequestMapping(value = "/start", method = RequestMethod.POST)
    public java.util.HashMap<String, Object> start(@RequestBody java.util.HashMap<String, Object> params) {
        try {
            NetworkProbeAnalysisService.PreparedScan prepared = params != null && params.get("scan") != null
                    ? analysisService.prepare(params.get("scan")) : null;
            final Map<String, Object> plan = normalizePlan(prepared != null
                    ? prepared.plan() : params == null ? null : params.get("plan"));
            return ControllerUtil.handleCapabilityCall(params, NetworkProbeCapable.class,
                    "启动网络探测失败", node -> {
                        Map<String, Object> result = node.startNetworkProbe(plan);
                        if (prepared != null && result != null && result.get("taskId") != null) {
                            analysisService.register(String.valueOf(result.get("taskId")), prepared);
                        }
                        return result;
                    });
        } catch (IllegalArgumentException error) {
            return ApiResponse.badRequest(error.getMessage());
        } catch (Exception error) {
            return ApiResponse.error("启动网络探测失败: " + error.getMessage());
        }
    }

    @RequestMapping(value = "/query", method = RequestMethod.POST)
    public java.util.HashMap<String, Object> query(@RequestBody java.util.HashMap<String, Object> params) {
        try {
            String taskId = ControllerUtil.getRequiredStringParam(params, "taskId").trim();
            return ControllerUtil.handleCapabilityCall(params, NetworkProbeCapable.class,
                    "查询网络探测任务失败", node -> {
                        Map<String, Object> result = node.queryNetworkProbe(taskId);
                        analysisService.enrich(taskId, result);
                        return result;
                    });
        } catch (IllegalArgumentException error) {
            return ApiResponse.badRequest(error.getMessage());
        }
    }

    @RequestMapping(value = "/pause", method = RequestMethod.POST)
    public java.util.HashMap<String, Object> pause(@RequestBody java.util.HashMap<String, Object> params) {
        return taskOperation(params, "暂停网络探测失败", NetworkProbeCapable::pauseNetworkProbe);
    }

    @RequestMapping(value = "/resume", method = RequestMethod.POST)
    public java.util.HashMap<String, Object> resume(@RequestBody java.util.HashMap<String, Object> params) {
        return taskOperation(params, "继续网络探测失败", NetworkProbeCapable::resumeNetworkProbe);
    }

    @RequestMapping(value = "/stop", method = RequestMethod.POST)
    public java.util.HashMap<String, Object> stop(@RequestBody java.util.HashMap<String, Object> params) {
        return taskOperation(params, "终止网络探测失败", NetworkProbeCapable::stopNetworkProbe);
    }

    private java.util.HashMap<String, Object> taskOperation(
            java.util.HashMap<String, Object> params,
            String errorPrefix,
            TaskOperation taskOperation) {
        try {
            String taskId = ControllerUtil.getRequiredStringParam(params, "taskId").trim();
            return ControllerUtil.handleCapabilityCall(params, NetworkProbeCapable.class,
                    errorPrefix, node -> taskOperation.apply(node, taskId));
        } catch (IllegalArgumentException error) {
            return ApiResponse.badRequest(error.getMessage());
        }
    }

    private Map<String, Object> normalizePlan(Object value) {
        if (!(value instanceof Map<?, ?> rawPlan)) {
            throw new IllegalArgumentException("plan必须是对象");
        }
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("targets", normalizeTargets(rawPlan.get("targets")));
        plan.put("stages", normalizeStages(rawPlan.get("stages")));

        Object limitsValue = rawPlan.get("limits");
        if (limitsValue != null && !(limitsValue instanceof Map<?, ?>)) {
            throw new IllegalArgumentException("plan.limits必须是对象");
        }
        if (limitsValue instanceof Map<?, ?> rawLimits) {
            Map<String, Object> limits = new LinkedHashMap<>();
            if (rawLimits.containsKey("threads")) {
                limits.put("threads", Integer.valueOf(getInt(rawLimits.get("threads"), "limits.threads", 1, MAX_THREADS)));
            }
            if (rawLimits.containsKey("timeout")) {
                limits.put("timeout", Integer.valueOf(getInt(rawLimits.get("timeout"), "limits.timeout", 100, MAX_TIMEOUT_MS)));
            }
            if (rawLimits.containsKey("maxReadBytes")) {
                limits.put("maxReadBytes", Integer.valueOf(getInt(rawLimits.get("maxReadBytes"),
                        "limits.maxReadBytes", 256, MAX_READ_BYTES)));
            }
            plan.put("limits", limits);
        }
        return plan;
    }

    private List<Map<String, Object>> normalizeTargets(Object value) {
        if (!(value instanceof List<?> rawTargets) || rawTargets.isEmpty()) {
            throw new IllegalArgumentException("plan.targets必须是非空数组");
        }
        if (rawTargets.size() > MAX_TARGETS) {
            throw new IllegalArgumentException("plan.targets不能超过" + MAX_TARGETS + "个");
        }
        List<Map<String, Object>> targets = new ArrayList<>();
        for (int index = 0; index < rawTargets.size(); index++) {
            Object valueAtIndex = rawTargets.get(index);
            if (!(valueAtIndex instanceof Map<?, ?> rawTarget)) {
                throw new IllegalArgumentException("plan.targets[" + index + "]必须是对象");
            }
            Map<String, Object> target = new LinkedHashMap<>();
            String host = text(rawTarget.get("host"));
            String baseUrl = text(rawTarget.get("baseUrl"));
            String protocol = text(rawTarget.get("protocol")).toLowerCase(Locale.ROOT);
            if (!baseUrl.isEmpty()) {
                try {
                    URL url = new URL(baseUrl);
                    if (!("http".equalsIgnoreCase(url.getProtocol())
                            || "https".equalsIgnoreCase(url.getProtocol()))) {
                        throw new IllegalArgumentException("仅支持 http/https baseUrl");
                    }
                    if (url.getUserInfo() != null) {
                        throw new IllegalArgumentException("baseUrl不能包含用户信息");
                    }
                    if (host.isEmpty()) host = url.getHost();
                } catch (java.net.MalformedURLException error) {
                    throw new IllegalArgumentException("plan.targets[" + index + "].baseUrl格式无效");
                }
            }
            if (host.isEmpty()) {
                throw new IllegalArgumentException("plan.targets[" + index + "]需要host或baseUrl");
            }
            validateHost(host, index);
            if (protocol.isEmpty()) protocol = baseUrl.isEmpty() ? "tcp" : baseUrl.substring(0, baseUrl.indexOf(':')).toLowerCase(Locale.ROOT);
            if (!("tcp".equals(protocol) || "http".equals(protocol) || "https".equals(protocol))) {
                throw new IllegalArgumentException("plan.targets[" + index + "].protocol不支持");
            }
            target.put("host", host);
            target.put("protocol", protocol);
            if (!baseUrl.isEmpty()) target.put("baseUrl", baseUrl);
            if (rawTarget.containsKey("port")) {
                target.put("port", Integer.valueOf(getInt(rawTarget.get("port"),
                        "plan.targets[" + index + "].port", 1, 65535)));
            }
            if (rawTarget.containsKey("request")) {
                String request = rawTarget.get("request") == null ? "" : String.valueOf(rawTarget.get("request"));
                if (request.length() > MAX_REQUEST_CHARS || request.indexOf('\0') >= 0) {
                    throw new IllegalArgumentException("plan.targets[" + index + "].request过长");
                }
                target.put("request", request);
            }
            if (rawTarget.containsKey("headers")) target.put("headers", normalizeHeaders(rawTarget.get("headers"), index));
            copyMetadata(rawTarget, target, index);
            if (rawTarget.containsKey("httpRequest")) {
                target.put("httpRequest", normalizeHttpRequest(rawTarget.get("httpRequest"), index));
            }
            targets.add(target);
        }
        return targets;
    }

    private List<String> normalizeStages(Object value) {
        List<String> stages = new ArrayList<>();
        if (value == null) {
            stages.add("tcp-connect");
            return stages;
        }
        if (!(value instanceof List<?> rawStages) || rawStages.isEmpty()) {
            throw new IllegalArgumentException("plan.stages必须是非空数组");
        }
        if (rawStages.size() > MAX_STAGES) throw new IllegalArgumentException("plan.stages不能超过" + MAX_STAGES + "个");
        for (Object stageValue : rawStages) {
            String stage = text(stageValue).toLowerCase(Locale.ROOT);
            if (!("tcp-connect".equals(stage) || "tcp-exchange".equals(stage)
                    || "http-head".equals(stage) || "http-request".equals(stage)
                    || "tls-handshake".equals(stage))) {
                throw new IllegalArgumentException("plan.stages包含不支持的阶段: " + stage);
            }
            if (!stages.contains(stage)) stages.add(stage);
        }
        return stages;
    }

    private Map<String, String> normalizeHeaders(Object value, int index) {
        if (!(value instanceof Map<?, ?> rawHeaders)) {
            throw new IllegalArgumentException("plan.targets[" + index + "].headers必须是对象");
        }
        if (rawHeaders.size() > MAX_HEADERS) throw new IllegalArgumentException("headers不能超过" + MAX_HEADERS + "个");
        Map<String, String> headers = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : rawHeaders.entrySet()) {
            String name = text(entry.getKey());
            String headerValue = text(entry.getValue());
            if (name.isEmpty() || name.indexOf('\r') >= 0 || name.indexOf('\n') >= 0
                    || headerValue.indexOf('\r') >= 0 || headerValue.indexOf('\n') >= 0) {
                throw new IllegalArgumentException("headers包含无效字符");
            }
            headers.put(name, headerValue);
        }
        return headers;
    }

    private void copyMetadata(Map<?, ?> rawTarget, Map<String, Object> target, int index) {
        for (String field : List.of("targetId", "probeId", "ruleId")) {
            String value = text(rawTarget.get(field));
            if (value.length() > 128) {
                throw new IllegalArgumentException("plan.targets[" + index + "]." + field + "过长");
            }
            if (!value.isEmpty()) target.put(field, value);
        }
        if (rawTarget.containsKey("requestIndex")) {
            target.put("requestIndex", Integer.valueOf(getInt(rawTarget.get("requestIndex"),
                    "plan.targets[" + index + "].requestIndex", 0, 255)));
        }
        String stage = text(rawTarget.get("stage")).toLowerCase(Locale.ROOT);
        if (!stage.isEmpty()) {
            if (!List.of("tcp-connect", "tcp-exchange", "http-head", "http-request", "tls-handshake").contains(stage)) {
                throw new IllegalArgumentException("plan.targets[" + index + "].stage不支持");
            }
            target.put("stage", stage);
        }
        if (rawTarget.containsKey("timeout")) {
            target.put("timeout", Integer.valueOf(getInt(rawTarget.get("timeout"),
                    "plan.targets[" + index + "].timeout", 100, MAX_TIMEOUT_MS)));
        }
        if (rawTarget.containsKey("maxReadBytes")) {
            target.put("maxReadBytes", Integer.valueOf(getInt(rawTarget.get("maxReadBytes"),
                    "plan.targets[" + index + "].maxReadBytes", 256, MAX_READ_BYTES)));
        }
    }

    private Map<String, Object> normalizeHttpRequest(Object value, int index) {
        if (!(value instanceof Map<?, ?> rawRequest)) {
            throw new IllegalArgumentException("plan.targets[" + index + "].httpRequest必须是对象");
        }
        Map<String, Object> request = new LinkedHashMap<>();
        String method = text(rawRequest.get("method")).toUpperCase(Locale.ROOT);
        if (method.isEmpty()) method = "GET";
        if (!List.of("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS").contains(method)) {
            throw new IllegalArgumentException("plan.targets[" + index + "].httpRequest.method不支持");
        }
        String path = text(rawRequest.get("path"));
        if (path.isEmpty()) path = text(rawRequest.get("uri"));
        if (path.isEmpty()) path = "/";
        if (path.length() > MAX_REQUEST_CHARS || path.indexOf('\r') >= 0 || path.indexOf('\n') >= 0) {
            throw new IllegalArgumentException("plan.targets[" + index + "].httpRequest.path格式无效");
        }
        request.put("method", method);
        request.put("path", path);
        String charset = text(rawRequest.get("charset"));
        request.put("charset", charset.isEmpty() ? "UTF-8" : charset);
        if (rawRequest.containsKey("body")) {
            String body = rawRequest.get("body") == null ? "" : String.valueOf(rawRequest.get("body"));
            if (body.length() > MAX_REQUEST_CHARS || body.indexOf('\0') >= 0) {
                throw new IllegalArgumentException("plan.targets[" + index + "].httpRequest.body过长");
            }
            request.put("body", body);
        }
        if (rawRequest.containsKey("headers")) {
            request.put("headers", normalizeHeaders(rawRequest.get("headers"), index));
        }
        return request;
    }

    private void validateHost(String host, int index) {
        if (host.length() > 253 || host.indexOf('\r') >= 0 || host.indexOf('\n') >= 0) {
            throw new IllegalArgumentException("plan.targets[" + index + "].host格式无效");
        }
        for (int i = 0; i < host.length(); i++) {
            if (Character.isWhitespace(host.charAt(i))) {
                throw new IllegalArgumentException("plan.targets[" + index + "].host不能包含空白字符");
            }
        }
    }

    private int getInt(Object value, String field, int min, int max) {
        if (value == null) throw new IllegalArgumentException(field + "不能为空");
        final int result;
        try {
            result = value instanceof Number number ? number.intValue() : Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException(field + "必须是整数");
        }
        if (result < min || result > max) throw new IllegalArgumentException(field + "必须在" + min + "到" + max + "之间");
        return result;
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    @FunctionalInterface
    private interface TaskOperation {
        Map<String, Object> apply(NetworkProbeCapable node, String taskId) throws Exception;
    }
}
