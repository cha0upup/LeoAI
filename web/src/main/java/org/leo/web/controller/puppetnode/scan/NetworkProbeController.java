package org.leo.web.controller.puppetnode.scan;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.leo.core.puppet.capability.NetworkProbeCapable;
import org.leo.core.util.ApiResponse;
import org.leo.web.dto.puppetnode.scan.NetworkDiscoveryDtos.ExecutionConfig;
import org.leo.web.dto.puppetnode.scan.NetworkDiscoveryDtos.PreviewResponse;
import org.leo.web.dto.puppetnode.scan.NetworkDiscoveryDtos.ResolvedTarget;
import org.leo.web.dto.puppetnode.scan.NetworkDiscoveryDtos.ScanConfig;
import org.leo.web.service.NetworkProbeResultStore;
import org.leo.web.service.NetworkProbeWorkflowService;
import org.leo.web.service.discovery.PortPolicyResolver;
import org.leo.web.service.discovery.NetworkProbeLimits;
import org.leo.web.service.discovery.ScanPreviewService;
import org.leo.web.service.discovery.TargetResolver;
import org.leo.web.util.ControllerUtil;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
    private final TargetResolver targetResolver;
    private final PortPolicyResolver portPolicyResolver;
    private final ObjectMapper objectMapper;
    private NetworkProbeResultStore resultStore;

    public NetworkProbeController(NetworkProbeWorkflowService workflowService,
                                  ScanPreviewService previewService,
                                  TargetResolver targetResolver,
                                  PortPolicyResolver portPolicyResolver,
                                  ObjectMapper objectMapper) {
        this.workflowService = workflowService;
        this.previewService = previewService;
        this.targetResolver = targetResolver;
        this.portPolicyResolver = portPolicyResolver;
        this.objectMapper = objectMapper;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setResultStore(NetworkProbeResultStore resultStore) {
        this.resultStore = resultStore;
    }

    @RequestMapping(value = "/workflow/start", method = RequestMethod.POST)
    public java.util.HashMap<String, Object> startWorkflow(@RequestBody java.util.HashMap<String, Object> params) {
        try {
            String sessionId = ControllerUtil.getRequiredStringParam(params, "sessionId").trim();
            // Validate access before parsing user-controlled CIDR, DNS or range inputs.
            ControllerUtil.requireCapability(params, NetworkProbeCapable.class);
            Map<String, Object> workflow = buildWorkflow(parseScanConfig(params.get("scan")));
            return ControllerUtil.handleCapabilityCall(params, NetworkProbeCapable.class,
                    "启动扫描工作流失败", node -> workflowService.start(sessionId, node, workflow));
        } catch (IllegalArgumentException error) {
            return ApiResponse.badRequest(error.getMessage());
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
                Map<String, Object> persisted = resultStore == null
                        ? Collections.emptyMap() : resultStore.summaryCounts(sessionId, taskId);
                if (!persisted.isEmpty()) {
                    for (String key : List.of("openCount", "serviceCount", "errorCount")) {
                        if (persisted.get(key) != null) live.put(key, persisted.get(key));
                    }
                }
                return ApiResponse.success(live);
            }
            if (resultStore != null) {
                Map<String, Object> persisted = resultStore.summary(sessionId, taskId);
                if (!persisted.isEmpty()) return ApiResponse.success(persisted);
            }
            return ApiResponse.notFound("扫描工作流任务不存在或不属于当前会话");
        } catch (IllegalArgumentException error) {
            return ApiResponse.badRequest(error.getMessage());
        }
    }

    @RequestMapping(value = "/workflow/tasks", method = RequestMethod.POST)
    public java.util.HashMap<String, Object> listWorkflows(@RequestBody java.util.HashMap<String, Object> params) {
        try {
            String sessionId = ControllerUtil.getRequiredStringParam(params, "sessionId").trim();
            ControllerUtil.getPuppetNodeSession(sessionId);
            if (resultStore != null) return ApiResponse.success(Map.of("tasks", resultStore.list(sessionId)));
            return ApiResponse.success(Map.of("tasks", workflowService.listSummaries(sessionId)));
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
            if (resultStore != null && !resultStore.deleteTask(sessionId, taskId)) {
                return ApiResponse.error("删除扫描结果失败");
            }
            return ApiResponse.success(result);
        } catch (org.leo.web.exception.ApiException error) {
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
            if (resultStore == null) return ApiResponse.error("扫描结果存储未初始化");
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

    private Map<String, Object> buildWorkflow(ScanConfig scan) {
        List<ResolvedTarget> resolvedTargets = targetResolver.resolve(scan.targets());
        List<Integer> policyPorts = portPolicyResolver.resolve(scan.portPolicy());
        LinkedHashSet<String> hosts = new LinkedHashSet<>();
        Map<String, Set<Integer>> explicitPortsByHost = new LinkedHashMap<>();
        Set<String> hostsUsingPolicy = new LinkedHashSet<>();
        Set<Integer> policyPortSet = new LinkedHashSet<>(policyPorts);
        Map<String, LinkedHashSet<Integer>> reachabilityPortsByHost = new LinkedHashMap<>();
        long reachabilityCount = 0L;

        for (ResolvedTarget resolved : resolvedTargets) {
            String host = text(resolved.ip());
            if (host.isEmpty()) continue;
            hosts.add(host);
            if (resolved.port() == null) {
                hostsUsingPolicy.add(host);
                LinkedHashSet<Integer> reachabilityPorts = reachabilityPortsByHost
                        .computeIfAbsent(host, ignored -> new LinkedHashSet<>());
                for (Integer port : NetworkProbeLimits.DEFAULT_REACHABILITY_PORTS) {
                    if (reachabilityPorts.add(port)) reachabilityCount++;
                }
                for (Integer port : policyPorts) {
                    if (reachabilityPorts.size() >= NetworkProbeLimits.MAX_REACHABILITY_PROBES_PER_HOST) break;
                    if (reachabilityPorts.add(port)) reachabilityCount++;
                }
            } else {
                explicitPortsByHost.computeIfAbsent(host, ignored -> new LinkedHashSet<>()).add(resolved.port());
                LinkedHashSet<Integer> reachabilityPorts = reachabilityPortsByHost
                        .computeIfAbsent(host, ignored -> new LinkedHashSet<>());
                if (reachabilityPorts.size() >= NetworkProbeLimits.MAX_REACHABILITY_PROBES_PER_HOST
                        && !reachabilityPorts.contains(resolved.port())) {
                    throw new IllegalArgumentException("单台主机的探活端口不能超过"
                            + NetworkProbeLimits.MAX_REACHABILITY_PROBES_PER_HOST + "个");
                }
                if (reachabilityPorts.add(resolved.port())) reachabilityCount++;
            }
            if (reachabilityCount > NetworkProbeLimits.MAX_REACHABILITY_PROBES) {
                throw new IllegalArgumentException("探活目标数不能超过"
                        + NetworkProbeLimits.MAX_REACHABILITY_PROBES + "个，请缩小主机范围或减少探活端口");
            }
        }
        if (hosts.isEmpty()) throw new IllegalArgumentException("扫描目标展开后为空");
        if (hosts.size() > NetworkProbeLimits.MAX_RESOLVED_HOSTS) {
            throw new IllegalArgumentException("扫描主机数不能超过"
                    + NetworkProbeLimits.MAX_RESOLVED_HOSTS + "个");
        }

        long combinationCount = 0L;
        for (String host : hosts) {
            Set<Integer> explicitPorts = explicitPortsByHost.getOrDefault(host, Set.of());
            long hostCombinations = explicitPorts.size();
            if (hostsUsingPolicy.contains(host)) {
                long policyCombinations = policyPortSet.size();
                for (Integer port : explicitPorts) {
                    if (policyPortSet.contains(port)) policyCombinations--;
                }
                hostCombinations += policyCombinations;
            }
            combinationCount += hostCombinations;
            if (combinationCount > NetworkProbeLimits.MAX_ENDPOINT_COMBINATIONS) {
                throw new IllegalArgumentException("扫描组合数不能超过"
                        + NetworkProbeLimits.MAX_ENDPOINT_COMBINATIONS + "个");
            }
        }

        LinkedHashSet<Integer> ports = new LinkedHashSet<>();
        LinkedHashSet<String> endpointKeys = new LinkedHashSet<>();
        LinkedHashSet<String> reachabilityKeys = new LinkedHashSet<>();
        List<Map<String, Object>> targets = new ArrayList<>();
        List<Map<String, Object>> reachabilityTargets = new ArrayList<>();

        for (Map.Entry<String, LinkedHashSet<Integer>> entry : reachabilityPortsByHost.entrySet()) {
            String host = entry.getKey();
            for (Integer port : entry.getValue()) {
                String key = host + ":" + port;
                if (reachabilityKeys.add(key)) {
                    Map<String, Object> target = new LinkedHashMap<>();
                    target.put("host", host);
                    target.put("port", port);
                    target.put("protocol", "tcp");
                    reachabilityTargets.add(target);
                }
            }
        }

        for (ResolvedTarget resolved : resolvedTargets) {
            String host = text(resolved.ip());
            if (host.isEmpty()) continue;
            Collection<Integer> targetPorts = resolved.port() == null
                    ? policyPorts : Collections.singletonList(resolved.port());
            for (Integer port : targetPorts) {
                String key = host + ":" + port;
                if (!endpointKeys.add(key)) continue;
                Map<String, Object> target = new LinkedHashMap<>();
                target.put("host", host);
                target.put("port", port);
                target.put("protocol", "tcp");
                target.put("targetId", resolved.targetId());
                if ("url".equalsIgnoreCase(resolved.source())) {
                    target.put("baseUrl", resolved.rawTarget());
                }
                targets.add(target);
                ports.add(port);
            }
        }

        if (targets.isEmpty()) throw new IllegalArgumentException("扫描目标展开后为空");
        ExecutionConfig execution = scan.execution();
        Map<String, Object> workflow = new LinkedHashMap<>();
        workflow.put("hosts", new ArrayList<>(hosts));
        workflow.put("ports", new ArrayList<>(ports));
        workflow.put("targets", targets);
        workflow.put("reachabilityTargets", reachabilityTargets);
        workflow.put("timeout", execution != null && execution.timeoutMs() != null
                ? execution.timeoutMs() : Integer.valueOf(NetworkProbeLimits.NODE_DEFAULT_TIMEOUT_MS));
        workflow.put("threads", execution != null && execution.workers() != null
                ? execution.workers() : Integer.valueOf(NetworkProbeLimits.NODE_DEFAULT_THREADS));
        workflow.put("probeServices", Boolean.TRUE);
        if (scan.name() != null && !scan.name().isBlank()) workflow.put("name", scan.name().trim());
        return workflow;
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    @FunctionalInterface
    private interface TaskOperation {
        Map<String, Object> apply(String sessionId, String taskId) throws Exception;
    }
}
