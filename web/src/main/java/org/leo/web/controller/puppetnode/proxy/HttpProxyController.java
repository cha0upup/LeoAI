package org.leo.web.controller.puppetnode.proxy;

import org.leo.core.engine.socks5.Socks5ProxyStatistics;
import org.leo.core.puppet.impl.JavaPuppetNode;
import org.leo.core.util.ApiResponse;
import org.leo.web.util.ControllerUtil;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/puppet-node/proxy/http")
public class HttpProxyController {

    private static final String MSG_PORT_INVALID = "port必须是数字类型";

    /**
     * 启动 HTTP 代理服务器
     */
    @RequestMapping(value = "/start", method = RequestMethod.POST)
    public HashMap<String, Object> start(@RequestBody HashMap<String, Object> params) {
        try {
            Number port = (Number) params.get("port");
            if (port == null) {
                return ApiResponse.badRequest(MSG_PORT_INVALID);
            }
            JavaPuppetNode node = ControllerUtil.getPuppetNode(params);
            Map<String, Object> result = node.startHttpProxy(port.intValue());
            return ApiResponse.success(result != null ? result : new HashMap<String, Object>());
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        } catch (Exception e) {
            return ApiResponse.error("启动HTTP代理失败: " + e.getMessage());
        }
    }

    /**
     * 停止 HTTP 代理服务器
     */
    @RequestMapping(value = "/stop", method = RequestMethod.POST)
    public HashMap<String, Object> stop(@RequestBody HashMap<String, Object> params) {
        try {
            JavaPuppetNode node = ControllerUtil.getPuppetNode(params);
            Map<String, Object> result = node.stopHttpProxy();
            return ApiResponse.success(result != null ? result : new HashMap<String, Object>());
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        } catch (Exception e) {
            return ApiResponse.error("停止HTTP代理失败: " + e.getMessage());
        }
    }

    /**
     * 查询 HTTP 代理运行状态
     */
    @RequestMapping(value = "/status", method = RequestMethod.POST)
    public HashMap<String, Object> getStatus(@RequestBody HashMap<String, Object> params) {
        try {
            JavaPuppetNode node = ControllerUtil.getPuppetNode(params);
            Map<String, Object> result = node.getHttpProxyStatus();
            return ApiResponse.success(result != null ? result : new HashMap<String, Object>());
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        } catch (Exception e) {
            return ApiResponse.error("查询HTTP代理状态失败: " + e.getMessage());
        }
    }

    /**
     * 获取 HTTP 代理统计信息
     */
    @RequestMapping(value = "/statistics", method = RequestMethod.POST)
    public HashMap<String, Object> getStatistics(@RequestBody HashMap<String, Object> params) {
        try {
            JavaPuppetNode node = ControllerUtil.getPuppetNode(params);
            Socks5ProxyStatistics.StatisticsSnapshot snapshot = node.getHttpProxyStatistics();
            if (snapshot == null) {
                return ApiResponse.error("HTTP代理未启动");
            }

            HashMap<String, Object> data = new HashMap<String, Object>();
            data.put("port", snapshot.port);
            data.put("activeConnections", snapshot.activeConnections);
            data.put("totalConnections", snapshot.totalConnections);
            data.put("uploadBytes", snapshot.uploadBytes);
            data.put("downloadBytes", snapshot.downloadBytes);
            data.put("uploadRate", snapshot.uploadRate);
            data.put("downloadRate", snapshot.downloadRate);
            data.put("startTime", snapshot.startTime);
            data.put("uptime", snapshot.uptime);

            List<HashMap<String, Object>> connections = new ArrayList<HashMap<String, Object>>();
            if (snapshot.connections != null) {
                for (Socks5ProxyStatistics.ConnectionInfo conn : snapshot.connections) {
                    HashMap<String, Object> connMap = new HashMap<String, Object>();
                    connMap.put("connId", conn.connId);
                    connMap.put("targetHost", conn.targetHost);
                    connMap.put("targetPort", conn.targetPort);
                    connMap.put("clientIp", conn.clientIp);
                    connMap.put("connectTime", conn.connectTime);
                    connMap.put("uptime", conn.getUptime());
                    connMap.put("uploadBytes", conn.uploadBytes);
                    connMap.put("downloadBytes", conn.downloadBytes);
                    connections.add(connMap);
                }
            }
            data.put("connections", connections);

            return ApiResponse.success(data);
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        } catch (Exception e) {
            return ApiResponse.error("获取HTTP代理统计信息失败: " + e.getMessage());
        }
    }
}
