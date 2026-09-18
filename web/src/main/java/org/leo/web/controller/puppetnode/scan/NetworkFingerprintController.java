package org.leo.web.controller.puppetnode.scan;

import org.leo.core.puppet.capability.NetworkProbeCapable;
import org.leo.core.util.ApiResponse;
import org.leo.service.discovery.NetworkDiscoveryDtos.TargetInput;
import org.leo.web.service.NetworkProbeWorkflowService;
import org.leo.service.discovery.TargetResolver;
import org.leo.web.util.ControllerUtil;
import org.leo.web.exception.ApiException;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.*;

/** Component-only tasks use server-selected assets or one explicit debug URL. */
@RestController
@RequestMapping("/puppet-node/network-probe/workflow/fingerprints")
public class NetworkFingerprintController {
    private final NetworkProbeWorkflowService workflows;
    private final TargetResolver resolver;

    public NetworkFingerprintController(NetworkProbeWorkflowService workflows, TargetResolver resolver) {
        this.workflows = workflows;
        this.resolver = resolver;
    }

    @PostMapping("/start")
    public HashMap<String, Object> start(@RequestBody HashMap<String, Object> params) {
        String sessionId = ControllerUtil.getRequiredStringParam(params, "sessionId").trim();
        ControllerUtil.requireCapability(params, NetworkProbeCapable.class);
        String sourceTaskId = ControllerUtil.getRequiredStringParam(params, "sourceTaskId").trim();
        if (!(params.get("endpointIds") instanceof List<?> values) || values.isEmpty() || values.size() > 256
                || values.stream().anyMatch(value -> !(value instanceof String id) || id.isBlank()))
            return ApiResponse.badRequest("请选择 1–256 个资产");
        List<String> ids = values.stream().map(String::valueOf).toList();
        return ControllerUtil.handleCapabilityCall(params, NetworkProbeCapable.class, "启动组件补扫失败",
                node -> {
                    try { return workflows.startSupplemental(sessionId, node, sourceTaskId, ids, params.get("fingerprint")); }
                    catch (IllegalArgumentException error) { throw ApiException.badRequest(error.getMessage()); }
                });
    }

    @PostMapping("/debug")
    public HashMap<String, Object> debug(@RequestBody HashMap<String, Object> params) {
        String sessionId = ControllerUtil.getRequiredStringParam(params, "sessionId").trim();
        ControllerUtil.requireCapability(params, NetworkProbeCapable.class);
        String target = ControllerUtil.getRequiredStringParam(params, "target").trim();
        URI uri = URI.create(target);
        if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) || uri.getHost() == null
                || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                || target.length() > 2048)
            return ApiResponse.badRequest("请输入 HTTP/HTTPS 应用根地址（不含认证信息、查询串和片段）");
        if (!(params.get("fingerprint") instanceof Map<?, ?> draft) || !(draft.get("rule") instanceof Map<?, ?>))
            return ApiResponse.badRequest("缺少当前草稿规则");
        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("fingerprintId", "debug-draft");
        rule.put("protocol", "http");
        rule.put("name", draft.get("name") == null ? "草稿调试" : String.valueOf(draft.get("name")));
        rule.put("rule", draft.get("rule"));
        var resolved = resolver.resolve(new TargetInput(List.of(target), List.of())).get(0);
        Map<String, Object> endpoint = Map.of("host", resolved.ip(), "port", resolved.port(),
                "protocol", resolved.protocol(), "service", resolved.protocol(), "state", "open");
        Map<String, Object> configured = Map.of("host", resolved.ip(), "port", resolved.port(), "baseUrl", target);
        return ControllerUtil.handleCapabilityCall(params, NetworkProbeCapable.class, "启动指纹调试失败",
                node -> {
                    try { return workflows.startFingerprint(sessionId, node, List.of(endpoint), List.of(configured),
                            List.of(rule), "规则调试 · " + rule.get("name"), "", true); }
                    catch (IllegalArgumentException error) { throw ApiException.badRequest(error.getMessage()); }
                });
    }
}
