package org.leo.web.controller.puppetnode.proxy;

import org.leo.core.puppet.capability.HttpProxyCapable;
import org.leo.web.util.ControllerUtil;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/puppet-node/proxy/http")
public class HttpProxyController {

    /**
     * 启动 HTTP 代理服务器
     */
    @PostMapping("/start")
    public HashMap<String, Object> start(@RequestBody HashMap<String, Object> params) {
        return ProxyControllerSupport.call("启动HTTP代理失败", Map.of(), () -> {
            int port = ProxyControllerSupport.requirePort(params, "port");
            String bindAddr = ProxyControllerSupport.optionalBindAddress(params);
            HttpProxyCapable node = ControllerUtil.requireCapability(params, HttpProxyCapable.class);
            return node.startHttpProxy(port, bindAddr);
        });
    }

    /**
     * 停止 HTTP 代理服务器
     */
    @PostMapping("/stop")
    public HashMap<String, Object> stop(@RequestBody HashMap<String, Object> params) {
        return ProxyControllerSupport.call("停止HTTP代理失败", Map.of(), () -> {
            HttpProxyCapable node = ControllerUtil.requireCapability(params, HttpProxyCapable.class);
            return node.stopHttpProxy();
        });
    }

    /**
     * 查询 HTTP 代理运行状态
     */
    @PostMapping("/status")
    public HashMap<String, Object> getStatus(@RequestBody HashMap<String, Object> params) {
        return ProxyControllerSupport.call("查询HTTP代理状态失败", Map.of(), () -> {
            HttpProxyCapable node = ControllerUtil.requireCapability(params, HttpProxyCapable.class);
            return node.getHttpProxyStatus();
        });
    }

    /**
     * 获取 HTTP 代理统计信息
     */
    @PostMapping("/statistics")
    public HashMap<String, Object> getStatistics(@RequestBody HashMap<String, Object> params) {
        return ProxyControllerSupport.statistics(
                "获取HTTP代理统计信息失败", "HTTP代理未启动", () -> {
                    HttpProxyCapable node =
                            ControllerUtil.requireCapability(params, HttpProxyCapable.class);
                    return node.getHttpProxyStatistics();
                });
    }
}
