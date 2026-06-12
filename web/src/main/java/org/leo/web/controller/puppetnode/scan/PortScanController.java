package org.leo.web.controller.puppetnode.scan;


import org.leo.core.util.ApiResponse;
import org.leo.web.util.ControllerUtil;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.HashMap;

@RestController
@RequestMapping("/puppet-node/port-scan")
public class PortScanController {

    @RequestMapping(value = "/start-scan", method = RequestMethod.POST)
    public HashMap<String, Object> startScan(@RequestBody HashMap<String, Object> params) {
        try {
            String scanHost = ControllerUtil.getRequiredStringParam(params, "scanHost");
            ArrayList<Object> scanPortsList = (ArrayList<Object>) params.get("scanPorts");
            int[] scanPorts = new int[scanPortsList.size()];
            for (int i = 0; i < scanPortsList.size(); i++) {
                scanPorts[i] = (int) scanPortsList.get(i);
            }
            int scanTimeout = (int) params.get("scanTimeout");
            int threadsNum = (int) params.get("threadsNum");
            return ControllerUtil.handlePuppetCall(params, "启动端口扫描失败",
                    node -> node.startScanPort(scanHost, scanPorts, scanTimeout, threadsNum));
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
            return ControllerUtil.handlePuppetCall(params, "查询端口扫描结果失败", node -> node.queryScanPortResult(taskId));
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
            return ControllerUtil.handlePuppetCall(params, "暂停端口扫描失败", node -> node.pauseScanPort(taskId));
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
            return ControllerUtil.handlePuppetCall(params, "继续端口扫描失败", node -> node.resumeScanPort(taskId));
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
            return ControllerUtil.handlePuppetCall(params, "终止端口扫描失败", node -> node.stopScanPort(taskId));
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        }
    }
}
