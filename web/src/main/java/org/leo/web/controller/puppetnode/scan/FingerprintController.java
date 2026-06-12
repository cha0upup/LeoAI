package org.leo.web.controller.puppetnode.scan;


import org.leo.core.config.LeoConfig;
import org.leo.core.puppet.impl.JavaPuppetNode;
import org.leo.core.util.json.JsonUtil;
import org.leo.core.util.ApiResponse;
import org.leo.web.util.AuditLogUtil;
import org.leo.web.util.ControllerUtil;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/puppet-node/fingerprint")
public class FingerprintController {

    private static final String COMPONENT_CLASS = "FingerprintComponent";
    private static final String FINGERPRINT_DIR_NAME = "fingerprint";
    private static final String FINGERPRINT_FILE_SUFFIX = ".json";

    /**
     * 启动指纹扫描
     * targets、threads 由客户端传递；rule 根据 fingerprintId 从 root/fingerprint/{fingerprintId}.json 加载。
     *
     * @param params 请求参数，包含：
     *              - sessionId: 会话ID（必需）
     *              - fingerprintId: 指纹ID，对应 root/fingerprint 目录下的 {fingerprintId}.json，用于加载 rule（必需）
     *              - targets: 目标列表（必需，由客户端传递）
     *              - threads: 并发线程数（必需，由客户端传递）
     * @return 扫描任务ID和状态
     */
    @RequestMapping(value = "/start-scan", method = RequestMethod.POST)
    public HashMap<String, Object> startScan(@RequestBody HashMap<String, Object> params) {
        JavaPuppetNode javaPuppetNode = null;
        String fingerprintId = null;
        try {
            javaPuppetNode = ControllerUtil.getPuppetNode(params);
            fingerprintId = ControllerUtil.getRequiredStringParam(params, "fingerprintId");

            Object targets = params.get("targets");
            if (targets == null) {
                throw new IllegalArgumentException("缺少必需参数: targets");
            }
            Object threadsObj = params.get("threads");
            if (threadsObj == null) {
                throw new IllegalArgumentException("缺少必需参数: threads");
            }
            int threads = threadsObj instanceof Number
                    ? ((Number) threadsObj).intValue()
                    : Integer.parseInt(String.valueOf(threadsObj));

            String safeName = getSafeFileName(fingerprintId);
            HashMap<String, Object> fingerprint = loadFingerprint(safeName);
            if (fingerprint == null) {
                throw new IllegalArgumentException("指纹不存在: " + fingerprintId);
            }
            Object rule = fingerprint.get("rule");
            if (rule == null) {
                throw new IllegalArgumentException("指纹配置缺少 rule: " + fingerprintId);
            }

            HashMap<String, Object> componentParams = new HashMap<>();
            componentParams.put("methodName", "startScan");
            componentParams.put("targets", normalizeJson(targets));
            componentParams.put("rule", normalizeJson(rule));
            componentParams.put("threads", Integer.valueOf(threads));

            Map<String, Object> results = javaPuppetNode.invokeComponent(COMPONENT_CLASS, componentParams);

            if (results == null) {
                AuditLogUtil.logFailure(javaPuppetNode, "FINGERPRINT_START", "启动指纹扫描", fingerprintId, params,
                        "组件调用返回结果为空", AuditLogUtil.getClientIp());
                return ApiResponse.error("启动指纹扫描失败: 组件调用返回结果为空");
            }

            Object code = results.get("code");
            if (code != null && !Integer.valueOf(200).equals(code)) {
                String errorMsg = (String) results.get("msg");
                AuditLogUtil.logFailure(javaPuppetNode, "FINGERPRINT_START", "启动指纹扫描", fingerprintId, params,
                        errorMsg != null ? errorMsg : "启动扫描失败", AuditLogUtil.getClientIp());
                return ApiResponse.error("启动指纹扫描失败: " + errorMsg);
            }

            AuditLogUtil.logSuccess(javaPuppetNode, "FINGERPRINT_START", "启动指纹扫描", fingerprintId, params,
                    ApiResponse.CODE_SUCCESS, "启动指纹扫描成功", AuditLogUtil.getClientIp());
            return ApiResponse.success(results);

        } catch (IllegalArgumentException e) {
            AuditLogUtil.logFailure(javaPuppetNode, "FINGERPRINT_START", "启动指纹扫描", fingerprintId, params,
                    e.getMessage(), AuditLogUtil.getClientIp());
            return ApiResponse.badRequest(e.getMessage());
        } catch (Exception e) {
            AuditLogUtil.logFailure(javaPuppetNode, "FINGERPRINT_START", "启动指纹扫描", fingerprintId, params,
                    e.getMessage(), AuditLogUtil.getClientIp());
            return ApiResponse.error("启动指纹扫描失败: " + e.getMessage());
        }
    }

    /**
     * 根据 fingerprintId 从 root/fingerprint 目录加载指纹配置。
     * 文件名为 {fingerprintId}.json，内容为 JSON，需包含 rule（targets、threads 由客户端传递）。
     */
    private HashMap<String, Object> loadFingerprint(String fingerprintId) throws Exception {
        File root = new File(LeoConfig.getVfsPath());
        File fingerprintDir = new File(root, FINGERPRINT_DIR_NAME);
        File fingerprintFile = new File(fingerprintDir, fingerprintId + FINGERPRINT_FILE_SUFFIX);
        if (!fingerprintFile.exists() || !fingerprintFile.isFile()) {
            return null;
        }
        String json = new String(Files.readAllBytes(fingerprintFile.toPath()), StandardCharsets.UTF_8);
        Object parsed = JsonUtil.fromJsonString(json, HashMap.class);
        Object normalized = normalizeJson(parsed);
        if (normalized instanceof HashMap) {
            return (HashMap<String, Object>) normalized;
        }
        return null;
    }

    /**
     * 递归把 fastjson 的 JSONObject/JSONArray 转成纯 HashMap/ArrayList。
     * puppet 侧不依赖 fastjson，含 JSONObject 的对象图通过 Java 序列化发过去后
     * 反序列化会抛 ClassNotFoundException: com.alibaba.fastjson.JSONObject。
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private Object normalizeJson(Object obj) {
        if (obj instanceof Map) {
            HashMap<String, Object> out = new HashMap<>();
            for (Map.Entry e : ((Map<?, ?>) obj).entrySet()) {
                out.put(String.valueOf(e.getKey()), normalizeJson(e.getValue()));
            }
            return out;
        }
        if (obj instanceof java.util.List) {
            java.util.ArrayList<Object> out = new java.util.ArrayList<>();
            for (Object item : (java.util.List<?>) obj) {
                out.add(normalizeJson(item));
            }
            return out;
        }
        return obj;
    }

    /**
     * 安全检查：防止路径遍历，确保文件名合法。
     */
    private String getSafeFileName(String fingerprintId) {
        String safeName = new File(fingerprintId).getName();
        if (safeName.contains("..") || safeName.contains("/") || safeName.contains("\\") || safeName.isEmpty()) {
            throw new IllegalArgumentException("fingerprintId 包含非法字符");
        }
        return safeName;
    }

    /**
     * 查询指纹扫描结果
     *
     * @param params 请求参数，包含：
     *              - sessionId: 会话ID（必需）
     *              - taskId: 扫描任务ID（必需）
     * @return 扫描任务信息和结果
     */
    @RequestMapping(value = "/query-result", method = RequestMethod.POST)
    public HashMap<String, Object> queryResult(@RequestBody HashMap<String, Object> params) {
        JavaPuppetNode javaPuppetNode = null;
        String taskId = null;
        try {
            javaPuppetNode = ControllerUtil.getPuppetNode(params);
            taskId = ControllerUtil.getRequiredStringParam(params, "taskId");

            HashMap<String, Object> componentParams = new HashMap<>();
            componentParams.put("methodName", "queryResult");
            componentParams.put("taskId", taskId);

            Map<String, Object> results = javaPuppetNode.invokeComponent(COMPONENT_CLASS, componentParams);

            if (results == null) {
                AuditLogUtil.logFailure(javaPuppetNode, "FINGERPRINT_QUERY", "查询指纹扫描结果", taskId, params,
                        "组件调用返回结果为空", AuditLogUtil.getClientIp());
                return ApiResponse.error("查询指纹扫描结果失败: 组件调用返回结果为空");
            }

            Object code = results.get("code");
            if (code != null && !Integer.valueOf(200).equals(code)) {
                String errorMsg = (String) results.get("msg");
                AuditLogUtil.logFailure(javaPuppetNode, "FINGERPRINT_QUERY", "查询指纹扫描结果", taskId, params,
                        errorMsg != null ? errorMsg : "查询结果失败", AuditLogUtil.getClientIp());
                return ApiResponse.error("查询指纹扫描结果失败: " + errorMsg);
            }

            AuditLogUtil.logSuccess(javaPuppetNode, "FINGERPRINT_QUERY", "查询指纹扫描结果", taskId, params,
                    ApiResponse.CODE_SUCCESS, "查询指纹扫描结果成功", AuditLogUtil.getClientIp());
            return ApiResponse.success(results);

        } catch (IllegalArgumentException e) {
            AuditLogUtil.logFailure(javaPuppetNode, "FINGERPRINT_QUERY", "查询指纹扫描结果", taskId, params,
                    e.getMessage(), AuditLogUtil.getClientIp());
            return ApiResponse.badRequest(e.getMessage());
        } catch (Exception e) {
            AuditLogUtil.logFailure(javaPuppetNode, "FINGERPRINT_QUERY", "查询指纹扫描结果", taskId, params,
                    e.getMessage(), AuditLogUtil.getClientIp());
            return ApiResponse.error("查询指纹扫描结果失败: " + e.getMessage());
        }
    }

    /**
     * 暂停指纹扫描
     *
     * @param params 请求参数：sessionId（必需）、taskId（必需）
     * @return 操作结果
     */
    @RequestMapping(value = "/pause-scan", method = RequestMethod.POST)
    public HashMap<String, Object> pauseScan(@RequestBody HashMap<String, Object> params) {
        JavaPuppetNode javaPuppetNode = null;
        String taskId = null;
        try {
            javaPuppetNode = ControllerUtil.getPuppetNode(params);
            taskId = ControllerUtil.getRequiredStringParam(params, "taskId");

            HashMap<String, Object> componentParams = new HashMap<>();
            componentParams.put("methodName", "pauseScan");
            componentParams.put("taskId", taskId);

            Map<String, Object> results = javaPuppetNode.invokeComponent(COMPONENT_CLASS, componentParams);

            if (results == null) {
                AuditLogUtil.logFailure(javaPuppetNode, "FINGERPRINT_PAUSE", "暂停指纹扫描", taskId, params,
                        "组件调用返回结果为空", AuditLogUtil.getClientIp());
                return ApiResponse.error("暂停指纹扫描失败: 组件调用返回结果为空");
            }

            Object code = results.get("code");
            if (code != null && !Integer.valueOf(200).equals(code)) {
                String errorMsg = (String) results.get("msg");
                AuditLogUtil.logFailure(javaPuppetNode, "FINGERPRINT_PAUSE", "暂停指纹扫描", taskId, params,
                        errorMsg != null ? errorMsg : "暂停失败", AuditLogUtil.getClientIp());
                return ApiResponse.error("暂停指纹扫描失败: " + errorMsg);
            }

            AuditLogUtil.logSuccess(javaPuppetNode, "FINGERPRINT_PAUSE", "暂停指纹扫描", taskId, params,
                    ApiResponse.CODE_SUCCESS, "暂停指纹扫描成功", AuditLogUtil.getClientIp());
            return ApiResponse.success(results);

        } catch (IllegalArgumentException e) {
            AuditLogUtil.logFailure(javaPuppetNode, "FINGERPRINT_PAUSE", "暂停指纹扫描", taskId, params,
                    e.getMessage(), AuditLogUtil.getClientIp());
            return ApiResponse.badRequest(e.getMessage());
        } catch (Exception e) {
            AuditLogUtil.logFailure(javaPuppetNode, "FINGERPRINT_PAUSE", "暂停指纹扫描", taskId, params,
                    e.getMessage(), AuditLogUtil.getClientIp());
            return ApiResponse.error("暂停指纹扫描失败: " + e.getMessage());
        }
    }

    /**
     * 继续指纹扫描
     *
     * @param params 请求参数：sessionId（必需）、taskId（必需）
     * @return 操作结果
     */
    @RequestMapping(value = "/resume-scan", method = RequestMethod.POST)
    public HashMap<String, Object> resumeScan(@RequestBody HashMap<String, Object> params) {
        JavaPuppetNode javaPuppetNode = null;
        String taskId = null;
        try {
            javaPuppetNode = ControllerUtil.getPuppetNode(params);
            taskId = ControllerUtil.getRequiredStringParam(params, "taskId");

            HashMap<String, Object> componentParams = new HashMap<>();
            componentParams.put("methodName", "resumeScan");
            componentParams.put("taskId", taskId);

            Map<String, Object> results = javaPuppetNode.invokeComponent(COMPONENT_CLASS, componentParams);

            if (results == null) {
                AuditLogUtil.logFailure(javaPuppetNode, "FINGERPRINT_RESUME", "继续指纹扫描", taskId, params,
                        "组件调用返回结果为空", AuditLogUtil.getClientIp());
                return ApiResponse.error("继续指纹扫描失败: 组件调用返回结果为空");
            }

            Object code = results.get("code");
            if (code != null && !Integer.valueOf(200).equals(code)) {
                String errorMsg = (String) results.get("msg");
                AuditLogUtil.logFailure(javaPuppetNode, "FINGERPRINT_RESUME", "继续指纹扫描", taskId, params,
                        errorMsg != null ? errorMsg : "继续失败", AuditLogUtil.getClientIp());
                return ApiResponse.error("继续指纹扫描失败: " + errorMsg);
            }

            AuditLogUtil.logSuccess(javaPuppetNode, "FINGERPRINT_RESUME", "继续指纹扫描", taskId, params,
                    ApiResponse.CODE_SUCCESS, "继续指纹扫描成功", AuditLogUtil.getClientIp());
            return ApiResponse.success(results);

        } catch (IllegalArgumentException e) {
            AuditLogUtil.logFailure(javaPuppetNode, "FINGERPRINT_RESUME", "继续指纹扫描", taskId, params,
                    e.getMessage(), AuditLogUtil.getClientIp());
            return ApiResponse.badRequest(e.getMessage());
        } catch (Exception e) {
            AuditLogUtil.logFailure(javaPuppetNode, "FINGERPRINT_RESUME", "继续指纹扫描", taskId, params,
                    e.getMessage(), AuditLogUtil.getClientIp());
            return ApiResponse.error("继续指纹扫描失败: " + e.getMessage());
        }
    }

    /**
     * 终止指纹扫描
     *
     * @param params 请求参数：sessionId（必需）、taskId（必需）
     * @return 操作结果
     */
    @RequestMapping(value = "/stop-scan", method = RequestMethod.POST)
    public HashMap<String, Object> stopScan(@RequestBody HashMap<String, Object> params) {
        JavaPuppetNode javaPuppetNode = null;
        String taskId = null;
        try {
            javaPuppetNode = ControllerUtil.getPuppetNode(params);
            taskId = ControllerUtil.getRequiredStringParam(params, "taskId");

            HashMap<String, Object> componentParams = new HashMap<>();
            componentParams.put("methodName", "stopScan");
            componentParams.put("taskId", taskId);

            Map<String, Object> results = javaPuppetNode.invokeComponent(COMPONENT_CLASS, componentParams);

            if (results == null) {
                AuditLogUtil.logFailure(javaPuppetNode, "FINGERPRINT_STOP", "终止指纹扫描", taskId, params,
                        "组件调用返回结果为空", AuditLogUtil.getClientIp());
                return ApiResponse.error("终止指纹扫描失败: 组件调用返回结果为空");
            }

            Object code = results.get("code");
            if (code != null && !Integer.valueOf(200).equals(code)) {
                String errorMsg = (String) results.get("msg");
                AuditLogUtil.logFailure(javaPuppetNode, "FINGERPRINT_STOP", "终止指纹扫描", taskId, params,
                        errorMsg != null ? errorMsg : "终止失败", AuditLogUtil.getClientIp());
                return ApiResponse.error("终止指纹扫描失败: " + errorMsg);
            }

            AuditLogUtil.logSuccess(javaPuppetNode, "FINGERPRINT_STOP", "终止指纹扫描", taskId, params,
                    ApiResponse.CODE_SUCCESS, "终止指纹扫描成功", AuditLogUtil.getClientIp());
            return ApiResponse.success(results);

        } catch (IllegalArgumentException e) {
            AuditLogUtil.logFailure(javaPuppetNode, "FINGERPRINT_STOP", "终止指纹扫描", taskId, params,
                    e.getMessage(), AuditLogUtil.getClientIp());
            return ApiResponse.badRequest(e.getMessage());
        } catch (Exception e) {
            AuditLogUtil.logFailure(javaPuppetNode, "FINGERPRINT_STOP", "终止指纹扫描", taskId, params,
                    e.getMessage(), AuditLogUtil.getClientIp());
            return ApiResponse.error("终止指纹扫描失败: " + e.getMessage());
        }
    }
}

