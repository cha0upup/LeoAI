package org.leo.web.controller.puppetnode.scan;

import org.leo.core.puppet.impl.JavaPuppetNode;
import org.leo.core.util.ApiResponse;
import org.leo.service.fingerprint.FingerprintManageService;
import org.leo.web.util.AuditLogUtil;
import org.leo.web.util.ControllerUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 侦察扫描（Recon Scan）控制器
 * <p>
 * 以"目标优先"模式批量探测：客户端传入目标列表 + 规则选择器，
 * 服务端自动匹配对应指纹规则并发执行，返回二维结果 {targetKey → {fingerprintId → boolean}}。
 * <p>
 * 路由：/puppet-node/recon-scan/*
 *
 * @author LeoSpring
 */
@RestController
@RequestMapping("/puppet-node/recon-scan")
public class ReconScanController {

    private static final String COMPONENT_CLASS = "ReconScanComponent";

    @Autowired
    private FingerprintManageService fingerprintManageService;

    // ==================== 启动扫描 ====================

    /**
     * 启动侦察扫描
     *
     * @param params 请求参数，包含：
     *               - sessionId: 会话ID（必需）
     *               - targets: 目标列表，每项含 protocol/baseUrl（HTTP）或 protocol/host/port（TCP）（必需）
     *               - ruleSelector: 规则选择器（可选，默认加载全部规则）
     *                   - protocol: "http" | "tcp"（按协议过滤）
     *                   - tags: ["java", "cache", ...]（按标签过滤，OR 关系）
     *                   - fingerprintIds: ["weblogic_any", ...]（按 ID 精确加载）
     *               - threads: 并发线程数（必需）
     */
    @RequestMapping(value = "/start-scan", method = RequestMethod.POST)
    public HashMap<String, Object> startScan(@RequestBody HashMap<String, Object> params) {
        JavaPuppetNode javaPuppetNode = null;
        try {
            javaPuppetNode = ControllerUtil.getPuppetNode(params);

            Object targetsObj = params.get("targets");
            if (!(targetsObj instanceof List)) {
                throw new IllegalArgumentException("缺少必需参数: targets（数组）");
            }
            Object threadsObj = params.get("threads");
            if (threadsObj == null) {
                throw new IllegalArgumentException("缺少必需参数: threads");
            }
            int threads = threadsObj instanceof Number
                    ? ((Number) threadsObj).intValue()
                    : Integer.parseInt(String.valueOf(threadsObj));

            // 根据 ruleSelector 加载匹配规则
            Object selectorObj = params.get("ruleSelector");
            Map selector = selectorObj instanceof Map ? (Map) selectorObj : null;
            List<Map<String, Object>> rules = resolveRules(selector);

            if (rules.isEmpty()) {
                return ApiResponse.badRequest("ruleSelector 未匹配到任何指纹规则，请检查 protocol/tags/fingerprintIds");
            }

            HashMap<String, Object> componentParams = new HashMap<String, Object>();
            componentParams.put("methodName", "startScan");
            componentParams.put("targets", targetsObj);
            componentParams.put("rules",   rules);
            componentParams.put("threads", Integer.valueOf(threads));

            Map<String, Object> results = javaPuppetNode.invokeComponent(COMPONENT_CLASS, componentParams);

            if (results == null) {
                AuditLogUtil.logFailure(javaPuppetNode, "RECON_START", "启动侦察扫描", null, params,
                        "组件调用返回结果为空", AuditLogUtil.getClientIp());
                return ApiResponse.error("启动侦察扫描失败: 组件调用返回结果为空");
            }

            Object code = results.get("code");
            if (code != null && !Integer.valueOf(200).equals(code)) {
                String errorMsg = (String) results.get("msg");
                AuditLogUtil.logFailure(javaPuppetNode, "RECON_START", "启动侦察扫描", null, params,
                        errorMsg != null ? errorMsg : "启动失败", AuditLogUtil.getClientIp());
                return ApiResponse.error("启动侦察扫描失败: " + errorMsg);
            }

            AuditLogUtil.logSuccess(javaPuppetNode, "RECON_START", "启动侦察扫描", null, params,
                    ApiResponse.CODE_SUCCESS, "启动侦察扫描成功，规则数=" + rules.size(), AuditLogUtil.getClientIp());
            return ApiResponse.success(results);

        } catch (IllegalArgumentException e) {
            AuditLogUtil.logFailure(javaPuppetNode, "RECON_START", "启动侦察扫描", null, params,
                    e.getMessage(), AuditLogUtil.getClientIp());
            return ApiResponse.badRequest(e.getMessage());
        } catch (Exception e) {
            AuditLogUtil.logFailure(javaPuppetNode, "RECON_START", "启动侦察扫描", null, params,
                    e.getMessage(), AuditLogUtil.getClientIp());
            return ApiResponse.error("启动侦察扫描失败: " + e.getMessage());
        }
    }

    // ==================== 查询结果 ====================

    @RequestMapping(value = "/query-result", method = RequestMethod.POST)
    public HashMap<String, Object> queryResult(@RequestBody HashMap<String, Object> params) {
        JavaPuppetNode javaPuppetNode = null;
        String taskId = null;
        try {
            javaPuppetNode = ControllerUtil.getPuppetNode(params);
            taskId = ControllerUtil.getRequiredStringParam(params, "taskId");

            HashMap<String, Object> componentParams = new HashMap<String, Object>();
            componentParams.put("methodName", "queryResult");
            componentParams.put("taskId", taskId);

            Map<String, Object> results = javaPuppetNode.invokeComponent(COMPONENT_CLASS, componentParams);

            if (results == null) {
                AuditLogUtil.logFailure(javaPuppetNode, "RECON_QUERY", "查询侦察扫描结果", taskId, params,
                        "组件调用返回结果为空", AuditLogUtil.getClientIp());
                return ApiResponse.error("查询侦察扫描结果失败: 组件调用返回结果为空");
            }

            Object code = results.get("code");
            if (code != null && !Integer.valueOf(200).equals(code)) {
                String errorMsg = (String) results.get("msg");
                AuditLogUtil.logFailure(javaPuppetNode, "RECON_QUERY", "查询侦察扫描结果", taskId, params,
                        errorMsg != null ? errorMsg : "查询失败", AuditLogUtil.getClientIp());
                return ApiResponse.error("查询侦察扫描结果失败: " + errorMsg);
            }

            AuditLogUtil.logSuccess(javaPuppetNode, "RECON_QUERY", "查询侦察扫描结果", taskId, params,
                    ApiResponse.CODE_SUCCESS, "查询侦察扫描结果成功", AuditLogUtil.getClientIp());
            return ApiResponse.success(results);

        } catch (IllegalArgumentException e) {
            AuditLogUtil.logFailure(javaPuppetNode, "RECON_QUERY", "查询侦察扫描结果", taskId, params,
                    e.getMessage(), AuditLogUtil.getClientIp());
            return ApiResponse.badRequest(e.getMessage());
        } catch (Exception e) {
            AuditLogUtil.logFailure(javaPuppetNode, "RECON_QUERY", "查询侦察扫描结果", taskId, params,
                    e.getMessage(), AuditLogUtil.getClientIp());
            return ApiResponse.error("查询侦察扫描结果失败: " + e.getMessage());
        }
    }

    // ==================== 暂停/恢复/停止 ====================

    @RequestMapping(value = "/pause-scan", method = RequestMethod.POST)
    public HashMap<String, Object> pauseScan(@RequestBody HashMap<String, Object> params) {
        JavaPuppetNode javaPuppetNode = null;
        String taskId = null;
        try {
            javaPuppetNode = ControllerUtil.getPuppetNode(params);
            taskId = ControllerUtil.getRequiredStringParam(params, "taskId");

            HashMap<String, Object> componentParams = new HashMap<String, Object>();
            componentParams.put("methodName", "pauseScan");
            componentParams.put("taskId", taskId);

            Map<String, Object> results = javaPuppetNode.invokeComponent(COMPONENT_CLASS, componentParams);

            if (results == null) {
                return ApiResponse.error("暂停侦察扫描失败: 组件调用返回结果为空");
            }
            Object code = results.get("code");
            if (code != null && !Integer.valueOf(200).equals(code)) {
                return ApiResponse.error("暂停侦察扫描失败: " + results.get("msg"));
            }
            return ApiResponse.success(results);

        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        } catch (Exception e) {
            return ApiResponse.error("暂停侦察扫描失败: " + e.getMessage());
        }
    }

    @RequestMapping(value = "/resume-scan", method = RequestMethod.POST)
    public HashMap<String, Object> resumeScan(@RequestBody HashMap<String, Object> params) {
        JavaPuppetNode javaPuppetNode = null;
        String taskId = null;
        try {
            javaPuppetNode = ControllerUtil.getPuppetNode(params);
            taskId = ControllerUtil.getRequiredStringParam(params, "taskId");

            HashMap<String, Object> componentParams = new HashMap<String, Object>();
            componentParams.put("methodName", "resumeScan");
            componentParams.put("taskId", taskId);

            Map<String, Object> results = javaPuppetNode.invokeComponent(COMPONENT_CLASS, componentParams);

            if (results == null) {
                return ApiResponse.error("继续侦察扫描失败: 组件调用返回结果为空");
            }
            Object code = results.get("code");
            if (code != null && !Integer.valueOf(200).equals(code)) {
                return ApiResponse.error("继续侦察扫描失败: " + results.get("msg"));
            }
            return ApiResponse.success(results);

        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        } catch (Exception e) {
            return ApiResponse.error("继续侦察扫描失败: " + e.getMessage());
        }
    }

    @RequestMapping(value = "/stop-scan", method = RequestMethod.POST)
    public HashMap<String, Object> stopScan(@RequestBody HashMap<String, Object> params) {
        JavaPuppetNode javaPuppetNode = null;
        String taskId = null;
        try {
            javaPuppetNode = ControllerUtil.getPuppetNode(params);
            taskId = ControllerUtil.getRequiredStringParam(params, "taskId");

            HashMap<String, Object> componentParams = new HashMap<String, Object>();
            componentParams.put("methodName", "stopScan");
            componentParams.put("taskId", taskId);

            Map<String, Object> results = javaPuppetNode.invokeComponent(COMPONENT_CLASS, componentParams);

            if (results == null) {
                return ApiResponse.error("终止侦察扫描失败: 组件调用返回结果为空");
            }
            Object code = results.get("code");
            if (code != null && !Integer.valueOf(200).equals(code)) {
                return ApiResponse.error("终止侦察扫描失败: " + results.get("msg"));
            }
            return ApiResponse.success(results);

        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        } catch (Exception e) {
            return ApiResponse.error("终止侦察扫描失败: " + e.getMessage());
        }
    }

    // ==================== 规则解析 ====================

    /**
     * 根据 ruleSelector 从本地指纹目录加载匹配规则（含完整 rule 字段）。
     *
     * <pre>
     * ruleSelector 优先级：
     *   1. fingerprintIds 不为空 → 按 ID 精确加载
     *   2. protocol 或 tags 不为空 → 从全量列表过滤后加载
     *   3. 全部为空 → 加载所有规则
     * </pre>
     */
    private List<Map<String, Object>> resolveRules(Map selector) throws Exception {
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();

        // 1. fingerprintIds 精确模式
        if (selector != null && selector.get("fingerprintIds") instanceof List) {
            List ids = (List) selector.get("fingerprintIds");
            for (int i = 0; i < ids.size(); i++) {
                String id = String.valueOf(ids.get(i));
                try {
                    HashMap<String, Object> fp = fingerprintManageService.getFingerprintById(id);
                    if (fp != null && fp.get("rule") != null) {
                        result.add(fp);
                    }
                } catch (Exception ignored) {
                    // 忽略不存在的 id
                }
            }
            return result;
        }

        // 2. 按 protocol / tags 过滤
        String filterProtocol = selector != null && selector.get("protocol") != null
                ? String.valueOf(selector.get("protocol")).toLowerCase().trim()
                : null;
        List filterTags = selector != null && selector.get("tags") instanceof List
                ? (List) selector.get("tags")
                : null;

        // 获取摘要列表用于过滤（摘要里有 protocol 和 tags）
        List<Map<String, Object>> summaries = (filterProtocol != null)
                ? fingerprintManageService.getFingerprintsByProtocol(filterProtocol)
                : fingerprintManageService.listFingerprints();

        // 按 tags 进一步过滤（tags 是 OR 关系：只要有任意一个 tag 命中即包含）
        for (int i = 0; i < summaries.size(); i++) {
            Map<String, Object> summary = summaries.get(i);
            if (filterTags != null && !filterTags.isEmpty()) {
                if (!hasAnyTag(summary, filterTags)) {
                    continue;
                }
            }
            // 加载含 rule 的完整对象
            Object fId = summary.get("fingerprintId");
            if (fId == null) continue;
            try {
                HashMap<String, Object> fp = fingerprintManageService.getFingerprintById(String.valueOf(fId));
                if (fp != null && fp.get("rule") != null) {
                    result.add(fp);
                }
            } catch (Exception ignored) {
                // 忽略读取失败的单个规则
            }
        }

        return result;
    }

    /** 判断指纹摘要是否包含 filterTags 中的任意一个标签（OR 关系） */
    private boolean hasAnyTag(Map<String, Object> summary, List filterTags) {
        Object tagsObj = summary.get("tags");
        if (!(tagsObj instanceof List)) return false;
        List fpTags = (List) tagsObj;
        for (int i = 0; i < filterTags.size(); i++) {
            String wantTag = String.valueOf(filterTags.get(i)).toLowerCase();
            for (int j = 0; j < fpTags.size(); j++) {
                if (wantTag.equalsIgnoreCase(String.valueOf(fpTags.get(j)))) {
                    return true;
                }
            }
        }
        return false;
    }
}
