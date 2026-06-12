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
@RequestMapping("/puppet-node/forward")
public class LocalForwardController {

    /**
     * 启动本地端口转发规则
     * Body: { puppetId, localPort, targetHost, targetPort }
     */
    @RequestMapping(value = "/start", method = RequestMethod.POST)
    public HashMap<String, Object> start(@RequestBody HashMap<String, Object> params) {
        try {
            Number localPort = (Number) params.get("localPort");
            String targetHost = (String) params.get("targetHost");
            Number targetPort = (Number) params.get("targetPort");

            if (localPort == null || targetHost == null || targetPort == null) {
                return ApiResponse.badRequest("localPort、targetHost、targetPort 均为必填项");
            }

            JavaPuppetNode node = ControllerUtil.getPuppetNode(params);
            Map<String, Object> result = node.startLocalForward(
                    localPort.intValue(), targetHost.trim(), targetPort.intValue());
            return ApiResponse.success(result != null ? result : new HashMap<String, Object>());
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        } catch (Exception e) {
            return ApiResponse.error("启动本地端口转发失败: " + e.getMessage());
        }
    }

    /**
     * 停止指定本地端口的转发规则
     * Body: { puppetId, localPort }
     */
    @RequestMapping(value = "/stop", method = RequestMethod.POST)
    public HashMap<String, Object> stop(@RequestBody HashMap<String, Object> params) {
        try {
            Number localPort = (Number) params.get("localPort");
            if (localPort == null) {
                return ApiResponse.badRequest("localPort 为必填项");
            }
            JavaPuppetNode node = ControllerUtil.getPuppetNode(params);
            Map<String, Object> result = node.stopLocalForward(localPort.intValue());
            return ApiResponse.success(result != null ? result : new HashMap<String, Object>());
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        } catch (Exception e) {
            return ApiResponse.error("停止本地端口转发失败: " + e.getMessage());
        }
    }

    /**
     * 停止所有本地端口转发规则
     */
    @RequestMapping(value = "/stop-all", method = RequestMethod.POST)
    public HashMap<String, Object> stopAll(@RequestBody HashMap<String, Object> params) {
        try {
            JavaPuppetNode node = ControllerUtil.getPuppetNode(params);
            Map<String, Object> result = node.stopAllLocalForwards();
            return ApiResponse.success(result != null ? result : new HashMap<String, Object>());
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        } catch (Exception e) {
            return ApiResponse.error("停止所有本地端口转发失败: " + e.getMessage());
        }
    }

    /**
     * 列出所有本地端口转发规则
     */
    @RequestMapping(value = "/list", method = RequestMethod.POST)
    public HashMap<String, Object> list(@RequestBody HashMap<String, Object> params) {
        try {
            JavaPuppetNode node = ControllerUtil.getPuppetNode(params);
            List<Map<String, Object>> rules = node.listLocalForwards();
            return ApiResponse.success(rules != null ? rules : new ArrayList<Map<String, Object>>());
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        } catch (Exception e) {
            return ApiResponse.error("获取转发规则列表失败: " + e.getMessage());
        }
    }

    /**
     * 获取指定本地端口转发的统计信息
     * Body: { puppetId, localPort }
     */
    @RequestMapping(value = "/statistics", method = RequestMethod.POST)
    public HashMap<String, Object> getStatistics(@RequestBody HashMap<String, Object> params) {
        try {
            Number localPort = (Number) params.get("localPort");
            if (localPort == null) {
                return ApiResponse.badRequest("localPort 为必填项");
            }
            JavaPuppetNode node = ControllerUtil.getPuppetNode(params);
            Socks5ProxyStatistics.StatisticsSnapshot snapshot =
                    node.getLocalForwardStatistics(localPort.intValue());
            if (snapshot == null) {
                return ApiResponse.error("该端口转发未启动或不存在");
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
            return ApiResponse.error("获取转发统计信息失败: " + e.getMessage());
        }
    }
}
