package org.leo.web.controller.puppetnode.proxy;

import org.leo.core.puppet.capability.LocalForwardCapable;
import org.leo.web.util.ControllerUtil;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/puppet-node/forward")
public class LocalForwardController {

    /**
     * 启动本地端口转发规则
     * Body: { puppetId, localPort, bindAddr?, targetHost, targetPort }
     */
    @PostMapping("/start")
    public HashMap<String, Object> start(@RequestBody HashMap<String, Object> params) {
        return ProxyControllerSupport.call("启动本地端口转发失败", Map.of(), () -> {
            int localPort = ProxyControllerSupport.requirePort(params, "localPort");
            String bindAddr = ProxyControllerSupport.optionalBindAddress(params);
            String targetHost = ProxyControllerSupport.requireText(params, "targetHost");
            int targetPort = ProxyControllerSupport.requirePort(params, "targetPort");
            LocalForwardCapable node = ControllerUtil.requireCapability(params, LocalForwardCapable.class);
            return node.startLocalForward(localPort, bindAddr, targetHost, targetPort);
        });
    }

    /**
     * 停止指定本地端口的转发规则
     * Body: { puppetId, localPort }
     */
    @PostMapping("/stop")
    public HashMap<String, Object> stop(@RequestBody HashMap<String, Object> params) {
        return ProxyControllerSupport.call("停止本地端口转发失败", Map.of(), () -> {
            int localPort = ProxyControllerSupport.requirePort(params, "localPort");
            LocalForwardCapable node = ControllerUtil.requireCapability(params, LocalForwardCapable.class);
            return node.stopLocalForward(localPort);
        });
    }

    /**
     * 停止所有本地端口转发规则
     */
    @PostMapping("/stop-all")
    public HashMap<String, Object> stopAll(@RequestBody HashMap<String, Object> params) {
        return ProxyControllerSupport.call("停止所有本地端口转发失败", Map.of(), () -> {
            LocalForwardCapable node = ControllerUtil.requireCapability(params, LocalForwardCapable.class);
            return node.stopAllLocalForwards();
        });
    }

    /**
     * 列出所有本地端口转发规则
     */
    @PostMapping("/list")
    public HashMap<String, Object> list(@RequestBody HashMap<String, Object> params) {
        return ProxyControllerSupport.call("获取转发规则列表失败", List.of(), () -> {
            LocalForwardCapable node = ControllerUtil.requireCapability(params, LocalForwardCapable.class);
            return node.listLocalForwards();
        });
    }

    /**
     * 获取指定本地端口转发的统计信息
     * Body: { puppetId, localPort }
     */
    @PostMapping("/statistics")
    public HashMap<String, Object> getStatistics(@RequestBody HashMap<String, Object> params) {
        return ProxyControllerSupport.statistics(
                "获取转发统计信息失败", "该端口转发未启动或不存在", () -> {
                    int localPort = ProxyControllerSupport.requirePort(params, "localPort");
                    LocalForwardCapable node =
                            ControllerUtil.requireCapability(params, LocalForwardCapable.class);
                    return node.getLocalForwardStatistics(localPort);
                });
    }
}
