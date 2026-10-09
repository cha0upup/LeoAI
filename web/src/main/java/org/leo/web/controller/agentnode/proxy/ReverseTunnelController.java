package org.leo.web.controller.agentnode.proxy;

import org.leo.core.agent.capability.ReverseTunnelCapable;
import org.leo.web.util.ControllerUtil;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 反向隧道控制器
 *
 * 反向隧道：在 agent 端开端口监听，把进入的连接转发到 C2 侧（或 C2 可达的）目标。
 * 类似 ssh -R remoteListenPort:forwardHost:forwardPort
 */
@RestController
@RequestMapping("/agent-node/reverse-tunnel")
public class ReverseTunnelController {

    /**
     * 启动反向隧道
     * Body: { agentId, remoteListenPort, bindAddr?, forwardHost, forwardPort }
     */
    @PostMapping("/start")
    public HashMap<String, Object> start(@RequestBody HashMap<String, Object> params) {
        return ProxyControllerSupport.call("启动反向隧道失败", Map.of(), () -> {
            int remoteListenPort = ProxyControllerSupport.requirePort(params, "remoteListenPort");
            String bindAddr = optionalText(params, "bindAddr");
            String forwardHost = ProxyControllerSupport.requireText(params, "forwardHost");
            int forwardPort = ProxyControllerSupport.requirePort(params, "forwardPort");
            ReverseTunnelCapable node = ControllerUtil.requireCapability(params, ReverseTunnelCapable.class);
            return node.startReverseTunnel(remoteListenPort, bindAddr, forwardHost, forwardPort);
        });
    }

    /**
     * 停止指定反向隧道
     * Body: { agentId, listenId }
     */
    @PostMapping("/stop")
    public HashMap<String, Object> stop(@RequestBody HashMap<String, Object> params) {
        return ProxyControllerSupport.call("停止反向隧道失败", Map.of(), () -> {
            String listenId = ProxyControllerSupport.requireText(params, "listenId");
            ReverseTunnelCapable node = ControllerUtil.requireCapability(params, ReverseTunnelCapable.class);
            return node.stopReverseTunnel(listenId);
        });
    }

    /**
     * 停止所有反向隧道
     */
    @PostMapping("/stop-all")
    public HashMap<String, Object> stopAll(@RequestBody HashMap<String, Object> params) {
        return ProxyControllerSupport.call("停止所有反向隧道失败", Map.of(), () -> {
            ReverseTunnelCapable node = ControllerUtil.requireCapability(params, ReverseTunnelCapable.class);
            return node.stopAllReverseTunnels();
        });
    }

    /**
     * 列出所有反向隧道
     */
    @PostMapping("/list")
    public HashMap<String, Object> list(@RequestBody HashMap<String, Object> params) {
        return ProxyControllerSupport.call("获取反向隧道列表失败", List.of(), () -> {
            ReverseTunnelCapable node = ControllerUtil.requireCapability(params, ReverseTunnelCapable.class);
            return node.listReverseTunnels();
        });
    }

    /**
     * 获取指定反向隧道的统计信息
     * Body: { agentId, listenId }
     */
    @PostMapping("/statistics")
    public HashMap<String, Object> getStatistics(@RequestBody HashMap<String, Object> params) {
        return ProxyControllerSupport.statistics(
                "获取反向隧道统计失败", "该反向隧道未启动或不存在", () -> {
                    String listenId = ProxyControllerSupport.requireText(params, "listenId");
                    ReverseTunnelCapable node =
                            ControllerUtil.requireCapability(params, ReverseTunnelCapable.class);
                    return node.getReverseTunnelStatistics(listenId, ProxyControllerSupport.statisticsQuery(params));
                });
    }

    private String optionalText(Map<String, Object> params, String name) {
        if (params == null || params.get(name) == null) {
            return null;
        }
        String value = String.valueOf(params.get(name)).trim();
        return value.isEmpty() ? null : value;
    }
}
