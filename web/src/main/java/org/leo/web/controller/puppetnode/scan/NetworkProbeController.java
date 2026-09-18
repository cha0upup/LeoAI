package org.leo.web.controller.puppetnode.scan;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.leo.core.puppet.capability.NetworkProbeCapable;
import org.leo.core.util.ApiResponse;
import org.leo.web.dto.puppetnode.scan.NetworkDiscoveryDtos.PreviewResponse;
import org.leo.web.dto.puppetnode.scan.NetworkDiscoveryDtos.ScanConfig;
import org.leo.web.service.NetworkProbeResultStore;
import org.leo.web.service.NetworkProbeWorkflowService;
import org.leo.web.service.discovery.ScanPreviewService;
import org.leo.web.service.discovery.ScanPlanService;
import org.leo.web.exception.ApiException;
import org.leo.web.util.ControllerUtil;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Entry point for the unified node-side network probe plan.
 *
 * <p>The service expands the logical scan and the orchestration layer splits
 * it into transport-safe node batches. Validation and policy decisions are
 * completed before a batch is sent to the node.</p>
 */
@RestController
@RequestMapping("/puppet-node/network-probe")
public class NetworkProbeController {

    private final NetworkProbeWorkflowService workflowService;
    private final ScanPreviewService previewService;
    private final ScanPlanService planService;
    private final ObjectMapper objectMapper;
    private final NetworkProbeResultStore resultStore;

    public NetworkProbeController(NetworkProbeWorkflowService workflowService,
                                  ScanPreviewService previewService,
                                  ScanPlanService planService,
                                  NetworkProbeResultStore resultStore,
                                  ObjectMapper objectMapper) {
        this.workflowService = workflowService;
        this.previewService = previewService;
        this.planService = planService;
        this.resultStore = resultStore;
        this.objectMapper = objectMapper;
    }

    @RequestMapping(value = "/workflow/start", method = RequestMethod.POST)
    public java.util.HashMap<String, Object> startWorkflow(@RequestBody java.util.HashMap<String, Object> params) {
        try {
            String sessionId = ControllerUtil.getRequiredStringParam(params, "sessionId").trim();
            // Validate access before parsing user-controlled CIDR, DNS or range inputs.
            ControllerUtil.requireCapability(params, NetworkProbeCapable.class);
            ScanPlanService.ScanPlan plan = planService.plan(parseScanConfig(params.get("scan")));
            return ControllerUtil.handleCapabilityCall(params, NetworkProbeCapable.class,
                    "启动扫描工作流失败", node -> workflowService.start(sessionId, node, plan));
        } catch (IllegalArgumentException error) {
            return ApiResponse.badRequest(error.getMessage());
        } catch (ApiException error) {
            throw error;
        } catch (Exception error) {
            return ApiResponse.error("启动扫描工作流失败: " + error.getMessage());
        }
    }

    @RequestMapping(value = "/workflow/preview", method = RequestMethod.POST)
    public java.util.HashMap<String, Object> previewWorkflow(@RequestBody java.util.HashMap<String, Object> params) {
        try {
            String sessionId = ControllerUtil.getRequiredStringParam(params, "sessionId").trim();
            // Preview is a server-side calculation and remains available while the node is offline.
            ControllerUtil.getPuppetNodeSession(sessionId);
            PreviewResponse response = previewService.preview(parseScanConfig(params.get("scan")));
            return ApiResponse.success(response);
        } catch (IllegalArgumentException error) {
            return ApiResponse.badRequest(error.getMessage());
        } catch (ApiException error) {
            throw error;
        } catch (Exception error) {
            return ApiResponse.error("预览扫描工作流失败: " + error.getMessage());
        }
    }

    @RequestMapping(value = "/workflow/query", method = RequestMethod.POST)
    public java.util.HashMap<String, Object> queryWorkflow(@RequestBody java.util.HashMap<String, Object> params) {
        try {
            String sessionId = ControllerUtil.getRequiredStringParam(params, "sessionId").trim();
            String taskId = ControllerUtil.getRequiredStringParam(params, "taskId").trim();
            ControllerUtil.getPuppetNodeSession(sessionId);
            Map<String, Object> live = workflowService.querySummaryIfPresent(sessionId, taskId);
            if (!live.isEmpty()) {
                Map<String, Object> persisted = resultStore.summaryCounts(sessionId, taskId);
                if (!persisted.isEmpty()) {
                    for (String key : List.of("openCount", "serviceCount", "errorCount", "fingerprintCount", "identifiedApplicationCount")) {
                        if (persisted.get(key) != null) live.put(key, persisted.get(key));
                    }
                }
                return ApiResponse.success(live);
            }
            Map<String, Object> persisted = resultStore.summary(sessionId, taskId);
            if (!persisted.isEmpty()) return ApiResponse.success(persisted);
            return ApiResponse.notFound("扫描工作流任务不存在或不属于当前会话");
        } catch (IllegalArgumentException error) {
            return ApiResponse.badRequest(error.getMessage());
        }
    }

    @RequestMapping(value = "/workflow/fingerprints/query", method = RequestMethod.POST)
    public java.util.HashMap<String, Object> queryFingerprints(@RequestBody java.util.HashMap<String, Object> params) {
        String sessionId = ControllerUtil.getRequiredStringParam(params, "sessionId").trim();
        String taskId = ControllerUtil.getRequiredStringParam(params, "taskId").trim();
        ControllerUtil.getPuppetNodeSession(sessionId);
        Map<String, Object> result = resultStore.queryFingerprintMatches(sessionId, taskId, params);
        return result.isEmpty() ? ApiResponse.notFound("扫描任务不存在或不属于当前会话") : ApiResponse.success(result);
    }

    @RequestMapping(value = "/workflow/fingerprints/evidence", method = RequestMethod.POST)
    public java.util.HashMap<String, Object> fingerprintEvidence(@RequestBody java.util.HashMap<String, Object> params) {
        String sessionId = ControllerUtil.getRequiredStringParam(params, "sessionId").trim();
        String taskId = ControllerUtil.getRequiredStringParam(params, "taskId").trim();
        String matchKey = ControllerUtil.getRequiredStringParam(params, "matchKey").trim();
        ControllerUtil.getPuppetNodeSession(sessionId);
        Map<String, Object> result = resultStore.fingerprintEvidence(sessionId, taskId, matchKey);
        return result.isEmpty() ? ApiResponse.notFound("识别证据不存在或不属于当前会话") : ApiResponse.success(result);
    }

    @RequestMapping(value = "/workflow/tasks", method = RequestMethod.POST)
    public java.util.HashMap<String, Object> listWorkflows(@RequestBody java.util.HashMap<String, Object> params) {
        try {
            String sessionId = ControllerUtil.getRequiredStringParam(params, "sessionId").trim();
            ControllerUtil.getPuppetNodeSession(sessionId);
            return ApiResponse.success(Map.of("tasks", resultStore.list(sessionId)));
        } catch (IllegalArgumentException error) {
            return ApiResponse.badRequest(error.getMessage());
        }
    }

    @RequestMapping(value = "/workflow/pause", method = RequestMethod.POST)
    public java.util.HashMap<String, Object> pauseWorkflow(@RequestBody java.util.HashMap<String, Object> params) {
        return workflowOperation(params, "暂停扫描工作流失败", workflowService::pause);
    }

    @RequestMapping(value = "/workflow/resume", method = RequestMethod.POST)
    public java.util.HashMap<String, Object> resumeWorkflow(@RequestBody java.util.HashMap<String, Object> params) {
        return workflowOperation(params, "继续扫描工作流失败", workflowService::resume);
    }

    @RequestMapping(value = "/workflow/stop", method = RequestMethod.POST)
    public java.util.HashMap<String, Object> stopWorkflow(@RequestBody java.util.HashMap<String, Object> params) {
        return workflowOperation(params, "终止扫描工作流失败", workflowService::stop);
    }

    @RequestMapping(value = "/workflow/delete", method = RequestMethod.POST)
    public java.util.HashMap<String, Object> deleteWorkflow(@RequestBody java.util.HashMap<String, Object> params) {
        try {
            String sessionId = ControllerUtil.getRequiredStringParam(params, "sessionId").trim();
            String taskId = ControllerUtil.getRequiredStringParam(params, "taskId").trim();
            // Deleting a completed history item must remain possible when the
            // selected puppet is offline; only the session ownership check is
            // needed before removing durable rows.
            ControllerUtil.getPuppetNodeSession(sessionId);
            Map<String, Object> result = workflowService.delete(sessionId, taskId);
            if (!resultStore.deleteTask(sessionId, taskId)) {
                return ApiResponse.error("删除扫描结果失败");
            }
            return ApiResponse.success(result);
        } catch (ApiException error) {
            // Preserve ownership and not-found HTTP semantics for callers.
            throw error;
        } catch (IllegalArgumentException error) {
            return ApiResponse.badRequest(error.getMessage());
        } catch (Exception error) {
            return ApiResponse.error("删除扫描工作流失败: " + error.getMessage());
        }
    }

    @RequestMapping(value = "/workflow/results/query", method = RequestMethod.POST)
    public java.util.HashMap<String, Object> queryWorkflowResults(
            @RequestBody java.util.HashMap<String, Object> params) {
        try {
            String sessionId = ControllerUtil.getRequiredStringParam(params, "sessionId").trim();
            String taskId = ControllerUtil.getRequiredStringParam(params, "taskId").trim();
            ControllerUtil.getPuppetNodeSession(sessionId);
            Map<String, Object> result = resultStore.queryResults(sessionId, taskId, params);
            if (result.isEmpty()) return ApiResponse.notFound("扫描任务不存在或不属于当前会话");
            return ApiResponse.success(result);
        } catch (IllegalArgumentException error) {
            return ApiResponse.badRequest(error.getMessage());
        }
    }

    private java.util.HashMap<String, Object> workflowOperation(
            java.util.HashMap<String, Object> params,
            String errorPrefix,
            TaskOperation taskOperation) {
        try {
            String sessionId = ControllerUtil.getRequiredStringParam(params, "sessionId").trim();
            String taskId = ControllerUtil.getRequiredStringParam(params, "taskId").trim();
            ControllerUtil.requireCapability(params, NetworkProbeCapable.class);
            Map<String, Object> result = new LinkedHashMap<>(taskOperation.apply(sessionId, taskId));
            result.remove("code");
            return ApiResponse.success(result);
        } catch (IllegalArgumentException error) {
            return ApiResponse.badRequest(error.getMessage());
        } catch (ApiException error) {
            throw error;
        } catch (Exception error) {
            return ApiResponse.error(errorPrefix + ": " + error.getMessage());
        }
    }

    private ScanConfig parseScanConfig(Object value) {
        if (value == null) throw new IllegalArgumentException("scan必须是对象");
        try {
            ScanConfig scan = objectMapper.convertValue(value, ScanConfig.class);
            if (scan == null || scan.targets() == null) {
                throw new IllegalArgumentException("scan.targets不能为空");
            }
            return scan;
        } catch (IllegalArgumentException error) {
            String message = error.getMessage();
            if (message != null && message.startsWith("scan.")) throw error;
            throw new IllegalArgumentException("scan配置格式无效", error);
        }
    }

    @FunctionalInterface
    private interface TaskOperation {
        Map<String, Object> apply(String sessionId, String taskId) throws Exception;
    }
}
