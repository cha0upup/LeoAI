package org.leo.web.controller.puppetnode.scan;


import org.leo.core.puppet.impl.JavaPuppetNode;
import org.leo.core.util.ApiResponse;
import org.leo.web.util.AuditLogUtil;
import org.leo.web.util.ControllerUtil;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/puppet-node/host-reachable")
public class HostIsReachableController {
    @RequestMapping(value = "/scan", method = RequestMethod.POST)
    public HashMap<String, Object> scanReachableHost(@RequestBody HashMap<String, Object> params) {
        JavaPuppetNode javaPuppetNode = null;
        String scanHostsStr = null;
        try {
            javaPuppetNode = ControllerUtil.getPuppetNode(params);

            // 获取必需参数
            ArrayList<String> scanHostsList = (ArrayList<String>) params.get("scanHosts");
            if (scanHostsList == null || scanHostsList.isEmpty()) {
                throw new IllegalArgumentException("scanHosts参数不能为空");
            }
            // 构建主机列表字符串用于日志
            scanHostsStr = scanHostsList.toString();

            // 获取超时时间，默认3000毫秒
            Object timeoutObj = params.get("scanTimeout");
            int scanTimeout = (timeoutObj instanceof Integer) ? (Integer) timeoutObj : 3000;
            // 调用组件
            Map<String, Object> results = javaPuppetNode.scanReachableHost(scanHostsList,scanTimeout);
            if (results == null) {
                AuditLogUtil.logFailure(javaPuppetNode, "HOST_REACHABLE_SCAN", "主机可达性检测", scanHostsStr, params,
                        "组件调用返回结果为空", AuditLogUtil.getClientIp());
                return ApiResponse.error("主机可达性检测失败: 组件调用返回结果为空");
            }
            // 检查返回码
            Object code = results.get("code");
            if (code != null && !Integer.valueOf(200).equals(code)) {
                String errorMsg = (String) results.get("msg");
                return ApiResponse.error("主机可达性检测失败: " + errorMsg);
            }
            return ApiResponse.success(results);

        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        } catch (Exception e) {
            return ApiResponse.error("主机可达性检测失败: " + e.getMessage());
        }
    }
}
