package org.leo.web.controller.puppetnode.scan;


import org.leo.core.puppet.capability.ScanCapable;
import org.leo.core.util.ApiResponse;
import org.leo.web.util.ControllerUtil;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@RestController
@RequestMapping("/puppet-node/port-scan")
public class PortScanController {

    private static final int MAX_PORTS = 4096;
    private static final int MAX_TARGETS = 128;
    private static final int MAX_THREADS = 64;

    /**
     * 启动端口扫描任务。可以提交单个 scanHost，也可以提交 scanHosts 创建一个统一多目标任务。
     */
    @RequestMapping(value = "/start-scan", method = RequestMethod.POST)
    public HashMap<String, Object> startScan(@RequestBody HashMap<String, Object> params) {
        try {
            List<String> scanHosts = getScanHosts(params);
            int[] scanPorts = getPorts(params.get("scanPorts"));
            int scanTimeout = getIntInRange(params.get("scanTimeout"), "scanTimeout", 1, 300000);
            int threadsNum = getIntInRange(params.get("threadsNum"), "threadsNum", 1, MAX_THREADS);
            boolean probeServices = getBoolean(params.get("probeServices"), true, "probeServices");
            return ControllerUtil.handleCapabilityCall(params, ScanCapable.class, "启动端口扫描失败",
                    node -> scanHosts.size() == 1
                            ? node.startScanPort(scanHosts.get(0), scanPorts, scanTimeout, threadsNum, probeServices)
                            : node.startScanPort(scanHosts, scanPorts, scanTimeout, threadsNum, probeServices));
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        }
    }

    /**
     * 查询端口扫描结果
     *
     * @param params 请求参数，包含：
     *               - sessionId: 会话ID（必需）
     *               - taskId: 扫描任务ID（必需）
     * @return 扫描任务信息和结果
     */
    @RequestMapping(value = "/query-result", method = RequestMethod.POST)
    public HashMap<String, Object> queryResult(@RequestBody HashMap<String, Object> params) {
        try {
            String taskId = ControllerUtil.getRequiredStringParam(params, "taskId");
            return ControllerUtil.handleCapabilityCall(params, ScanCapable.class, "查询端口扫描结果失败", node -> node.queryScanPortResult(taskId));
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        }
    }

    /**
     * 暂停端口扫描
     *
     * @param params 请求参数，包含：
     *               - sessionId: 会话ID（必需）
     *               - taskId: 扫描任务ID（必需）
     * @return 操作结果
     */
    @RequestMapping(value = "/pause-scan", method = RequestMethod.POST)
    public HashMap<String, Object> pauseScan(@RequestBody HashMap<String, Object> params) {
        try {
            String taskId = ControllerUtil.getRequiredStringParam(params, "taskId");
            return ControllerUtil.handleCapabilityCall(params, ScanCapable.class, "暂停端口扫描失败", node -> node.pauseScanPort(taskId));
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        }
    }

    /**
     * 继续端口扫描
     *
     * @param params 请求参数，包含：
     *               - sessionId: 会话ID（必需）
     *               - taskId: 扫描任务ID（必需）
     * @return 操作结果
     */
    @RequestMapping(value = "/resume-scan", method = RequestMethod.POST)
    public HashMap<String, Object> resumeScan(@RequestBody HashMap<String, Object> params) {
        try {
            String taskId = ControllerUtil.getRequiredStringParam(params, "taskId");
            return ControllerUtil.handleCapabilityCall(params, ScanCapable.class, "继续端口扫描失败", node -> node.resumeScanPort(taskId));
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        }
    }

    /**
     * 终止端口扫描
     *
     * @param params 请求参数，包含：
     *               - sessionId: 会话ID（必需）
     *               - taskId: 扫描任务ID（必需）
     * @return 操作结果
     */
    @RequestMapping(value = "/stop-scan", method = RequestMethod.POST)
    public HashMap<String, Object> stopScan(@RequestBody HashMap<String, Object> params) {
        try {
            String taskId = ControllerUtil.getRequiredStringParam(params, "taskId");
            return ControllerUtil.handleCapabilityCall(params, ScanCapable.class, "终止端口扫描失败", node -> node.stopScanPort(taskId));
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        }
    }

    private int[] getPorts(Object value) {
        if (!(value instanceof List<?> ports) || ports.isEmpty()) {
            throw new IllegalArgumentException("scanPorts必须是非空端口数组");
        }
        Set<Integer> unique = new LinkedHashSet<>();
        for (int i = 0; i < ports.size(); i++) {
            unique.add(getIntInRange(ports.get(i), "scanPorts[" + i + "]", 1, 65535));
        }
        if (unique.size() > MAX_PORTS) {
            throw new IllegalArgumentException("scanPorts不能超过" + MAX_PORTS + "个");
        }
        int[] parsed = new int[unique.size()];
        int index = 0;
        for (Integer port : unique) parsed[index++] = port.intValue();
        return parsed;
    }

    private List<String> getScanHosts(HashMap<String, Object> params) {
        Object value = params.get("scanHosts");
        List<String> hosts = new ArrayList<>();
        if (value instanceof List<?> values) {
            for (Object item : values) {
                if (item == null) continue;
                String host = String.valueOf(item).trim();
                if (host.isEmpty()) continue;
                validateScanHost(host);
                if (!hosts.contains(host)) hosts.add(host);
            }
        } else if (value != null) {
            throw new IllegalArgumentException("scanHosts必须是主机地址数组");
        }
        if (hosts.isEmpty()) {
            String scanHost = ControllerUtil.getRequiredStringParam(params, "scanHost").trim();
            validateScanHost(scanHost);
            hosts.add(scanHost);
        }
        if (hosts.size() > MAX_TARGETS) {
            throw new IllegalArgumentException("scanHosts不能超过" + MAX_TARGETS + "个");
        }
        return hosts;
    }

    private boolean getBoolean(Object value, boolean defaultValue, String fieldName) {
        if (value == null) return defaultValue;
        if (value instanceof Boolean bool) return bool.booleanValue();
        String text = String.valueOf(value).trim();
        if ("true".equalsIgnoreCase(text)) return true;
        if ("false".equalsIgnoreCase(text)) return false;
        throw new IllegalArgumentException(fieldName + "必须是布尔值");
    }

    private void validateScanHost(String host) {
        if (host.length() > 253 || host.indexOf('\r') >= 0 || host.indexOf('\n') >= 0) {
            throw new IllegalArgumentException("scanHost格式无效");
        }
        for (int i = 0; i < host.length(); i++) {
            if (Character.isWhitespace(host.charAt(i))) {
                throw new IllegalArgumentException("scanHost不能包含空白字符");
            }
        }
    }

    private int getIntInRange(Object value, String fieldName, int min, int max) {
        if (value == null) {
            throw new IllegalArgumentException(fieldName + "不能为空");
        }
        final int parsed;
        try {
            parsed = value instanceof Number number
                    ? number.intValue()
                    : Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(fieldName + "必须是整数", e);
        }
        if (parsed < min || parsed > max) {
            throw new IllegalArgumentException(fieldName + "必须在" + min + "到" + max + "之间");
        }
        return parsed;
    }
}
