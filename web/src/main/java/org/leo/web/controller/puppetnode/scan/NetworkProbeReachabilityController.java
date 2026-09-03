package org.leo.web.controller.puppetnode.scan;


import org.leo.core.puppet.capability.NetworkProbeCapable;
import org.leo.core.util.ApiResponse;
import org.leo.web.exception.ApiException;
import org.leo.web.util.ControllerUtil;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/puppet-node/network-probe")
public class NetworkProbeReachabilityController {
    private static final int PROBE_PORTS_PER_HOST = 3;
    private static final int MAX_TARGETS = 128;

    @RequestMapping(value = "/reachability", method = RequestMethod.POST)
    public HashMap<String, Object> reachability(@RequestBody HashMap<String, Object> params) {
        try {
            NetworkProbeCapable scanNode = ControllerUtil.requireCapability(params, NetworkProbeCapable.class);

            // 获取必需参数
            ArrayList<String> scanHostsList = getScanHosts(params.get("scanHosts"));
            if (scanHostsList.isEmpty()) {
                throw new IllegalArgumentException("scanHosts参数不能为空");
            }
            // 获取超时时间，默认3000毫秒
            Object timeoutObj = params.get("scanTimeout");
            int scanTimeout = timeoutObj == null ? 3000 : parsePositiveInt(timeoutObj, "scanTimeout");
            List<Map<String, Object>> targets = new ArrayList<>();
            int[] probePorts = {80, 443, 22};
            for (String host : scanHostsList) {
                for (int port : probePorts) {
                    Map<String, Object> target = new HashMap<>();
                    target.put("host", host); target.put("port", port); target.put("protocol", "tcp");
                    targets.add(target);
                }
            }
            if (targets.size() > MAX_TARGETS) {
                throw new IllegalArgumentException("主机数量不能超过" + (MAX_TARGETS / PROBE_PORTS_PER_HOST) + "个");
            }
            Map<String, Object> plan = new HashMap<>();
            plan.put("targets", targets); plan.put("stages", List.of("tcp-connect"));
            plan.put("limits", Map.of("timeout", scanTimeout, "threads", Math.min(32, targets.size())));
            Map<String, Object> started = scanNode.startNetworkProbe(plan);
            String taskId = started == null ? null : String.valueOf(started.get("taskId"));
            if (taskId == null || taskId.isBlank()) return ApiResponse.error("主机可达性检测未返回任务 ID");
            long deadline = System.currentTimeMillis() + Math.min(300000L, scanTimeout * 3L + 5000L);
            Map<String, Object> componentResponse;
            Map<String, Object> snapshot;
            do {
                componentResponse = scanNode.queryNetworkProbe(taskId);
                snapshot = componentResponse == null
                        ? null : asMap(componentResponse.get("result"));
                if (snapshot == null) {
                    String message = componentResponse == null ? "查询结果为空"
                            : String.valueOf(componentResponse.getOrDefault("msg", "查询结果为空"));
                    return ApiResponse.error("主机可达性检测失败: " + message);
                }
                if (!"RUNNING".equals(String.valueOf(snapshot.get("status")))) break;
                Thread.sleep(50L);
            } while (System.currentTimeMillis() < deadline);
            if (snapshot != null && "RUNNING".equals(String.valueOf(snapshot.get("status")))) {
                try {
                    scanNode.stopNetworkProbe(taskId);
                } catch (Exception ignored) {
                    // The worker may finish between the final query and stop.
                }
                componentResponse = scanNode.queryNetworkProbe(taskId);
                snapshot = componentResponse == null ? null : asMap(componentResponse.get("result"));
            }
            if (snapshot == null) return ApiResponse.error("主机可达性检测失败: 查询结果为空");
            List<String> reachable = new ArrayList<>();
            List<String> unreachable = new ArrayList<>(scanHostsList);
            Object observationsValue = snapshot.get("observations");
            if (observationsValue instanceof List<?> observations) {
                for (Object value : observations) {
                    if (!(value instanceof Map<?, ?> observation) || !"open".equals(observation.get("state"))) continue;
                    String host = String.valueOf(observation.get("host"));
                    if (!reachable.contains(host)) reachable.add(host);
                    unreachable.remove(host);
                }
            }
            Map<String, Object> results = new HashMap<>();
            results.put("code", 200); results.put("reachableHostList", reachable);
            results.put("unreachableHostList", unreachable); results.put("totalCount", scanHostsList.size());
            results.put("reachableCount", reachable.size()); results.put("unreachableCount", unreachable.size());
            return ApiResponse.success(results);

        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            return ApiResponse.error("主机可达性检测失败: " + e.getMessage());
        }
    }

    private ArrayList<String> getScanHosts(Object value) {
        if (!(value instanceof java.util.List<?> values)) {
            throw new IllegalArgumentException("scanHosts必须是字符串数组");
        }
        Set<String> uniqueHosts = new LinkedHashSet<>();
        for (Object item : values) {
            if (!(item instanceof String host) || host.isBlank()) {
                throw new IllegalArgumentException("scanHosts必须是非空字符串数组");
            }
            uniqueHosts.add(host.trim());
        }
        if (uniqueHosts.size() > MAX_TARGETS / PROBE_PORTS_PER_HOST) {
            throw new IllegalArgumentException("scanHosts不能超过" + (MAX_TARGETS / PROBE_PORTS_PER_HOST) + "个");
        }
        return new ArrayList<>(uniqueHosts);
    }

    private int parsePositiveInt(Object value, String fieldName) {
        final int parsed;
        try {
            parsed = value instanceof Number number
                    ? number.intValue()
                    : Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(fieldName + "必须是正整数", e);
        }
        if (parsed < 1 || parsed > 300000) {
            throw new IllegalArgumentException(fieldName + "必须在1到300000毫秒之间");
        }
        return parsed;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> ? (Map<String, Object>) value : null;
    }
}
