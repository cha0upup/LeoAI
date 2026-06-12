package org.leo.web.controller.puppetnode.proxy;


import org.leo.core.engine.socks5.Socks5ProxyServer;
import org.leo.core.engine.socks5.Socks5ProxyStatistics;
import org.leo.core.puppet.impl.JavaPuppetNode;
import org.leo.core.util.ApiResponse;
import org.leo.web.util.ControllerUtil;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/puppet-node/proxy")
public class Socks5ProxyController {

    // 消息常量
    private static final String MSG_PORT_INVALID = "port必须是数字类型";

    /**
     * 启动SOCKS5代理服务器
     */
    @RequestMapping(value = "/start", method = RequestMethod.POST)
    public HashMap<String, Object> start(@RequestBody HashMap<String, Object> params) throws Exception {
        try {
            Number port = (Number) params.get("port");
            if (port == null) {
                return ApiResponse.badRequest(MSG_PORT_INVALID);
            }

            JavaPuppetNode javaPuppetNode = ControllerUtil.getPuppetNode(params);
            Map<String, Object> result = javaPuppetNode.startSocks5Proxy(port.intValue());

            return ApiResponse.success(result != null ? result : new HashMap<String, Object>());
        } catch (IllegalArgumentException e) {

            return ApiResponse.badRequest(e.getMessage());
        } catch (Exception e) {
            return ApiResponse.error("启动SOCKS5代理失败: " + e.getMessage());
        }
    }

    /**
     * 停止SOCKS5代理服务器
     */
    @RequestMapping(value = "/stop", method = RequestMethod.POST)
    public HashMap<String, Object> stop(@RequestBody HashMap<String, Object> params) {
        try {
            JavaPuppetNode javaPuppetNode = ControllerUtil.getPuppetNode(params);
            Map<String, Object> result = javaPuppetNode.stopSocks5Proxy();
            return ApiResponse.success(result != null ? result : new HashMap<String, Object>());
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        } catch (Exception e) {
            return ApiResponse.error("停止SOCKS5代理失败: " + e.getMessage());
        }
    }

    /**
     * 获取SOCKS5代理统计信息
     */
    @RequestMapping(value = "/statistics", method = RequestMethod.POST)
    public HashMap<String, Object> getStatistics(@RequestBody HashMap<String, Object> params) {
        try {
            JavaPuppetNode javaPuppetNode = ControllerUtil.getPuppetNode(params);

            Socks5ProxyStatistics.StatisticsSnapshot snapshot = javaPuppetNode.getSocks5ProxyStatistics();
            if (snapshot == null) {
                return ApiResponse.error("SOCKS5代理未启动");
            }

            // 转换为Map格式，便于JSON序列化
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

            // 连接列表
            java.util.List<HashMap<String, Object>> connections = new java.util.ArrayList<HashMap<String, Object>>();
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
            return ApiResponse.error("获取统计信息失败: " + e.getMessage());
        }
    }

    /**
     * 查询当前会话的SOCKS5代理状态和端口号
     */
    @RequestMapping(value = "/status", method = RequestMethod.POST)
    public HashMap<String, Object> getStatus(@RequestBody HashMap<String, Object> params) {
        try {
            JavaPuppetNode javaPuppetNode = ControllerUtil.getPuppetNode(params);
            Socks5ProxyServer proxyServer = javaPuppetNode.getSocks5ProxyServer();
            HashMap<String, Object> data = new HashMap<String, Object>();
            if (proxyServer == null) {
                data.put("enabled", false);
                data.put("port", null);
            } else {
                data.put("enabled", proxyServer.isRunning());
                data.put("port", proxyServer.getListenPort());
            }

            return ApiResponse.success(data);
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        } catch (Exception e) {
            return ApiResponse.error("查询代理状态失败: " + e.getMessage());
        }
    }
}
