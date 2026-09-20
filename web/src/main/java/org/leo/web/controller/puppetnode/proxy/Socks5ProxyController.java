package org.leo.web.controller.puppetnode.proxy;

import org.leo.core.puppet.capability.Socks5ProxyCapable;
import org.leo.web.util.ControllerUtil;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/puppet-node/proxy")
public class Socks5ProxyController {

    /**
     * 启动SOCKS5代理服务器
     */
    @PostMapping("/start")
    public HashMap<String, Object> start(@RequestBody HashMap<String, Object> params) {
        return ProxyControllerSupport.call("启动SOCKS5代理失败", Map.of(), () -> {
            int port = ProxyControllerSupport.requirePort(params, "port");
            String bindAddr = ProxyControllerSupport.optionalBindAddress(params);
            Socks5ProxyCapable socks5ProxyNode = ControllerUtil.requireCapability(params, Socks5ProxyCapable.class);
            return socks5ProxyNode.startSocks5Proxy(port, bindAddr);
        });
    }

    /**
     * 停止SOCKS5代理服务器
     */
    @PostMapping("/stop")
    public HashMap<String, Object> stop(@RequestBody HashMap<String, Object> params) {
        return ProxyControllerSupport.call("停止SOCKS5代理失败", Map.of(), () -> {
            Socks5ProxyCapable socks5ProxyNode = ControllerUtil.requireCapability(params, Socks5ProxyCapable.class);
            return socks5ProxyNode.stopSocks5Proxy();
        });
    }

    /**
     * 获取SOCKS5代理统计信息
     */
    @PostMapping("/statistics")
    public HashMap<String, Object> getStatistics(@RequestBody HashMap<String, Object> params) {
        return ProxyControllerSupport.statistics(
                "获取统计信息失败", "SOCKS5代理未启动", () -> {
                    Socks5ProxyCapable node =
                            ControllerUtil.requireCapability(params, Socks5ProxyCapable.class);
                    return node.getSocks5ProxyStatistics();
                });
    }

    /**
     * 查询当前会话的SOCKS5代理状态和端口号
     */
    @PostMapping("/status")
    public HashMap<String, Object> getStatus(@RequestBody HashMap<String, Object> params) {
        return ProxyControllerSupport.call("查询代理状态失败", Map.of(), () -> {
            Socks5ProxyCapable socks5ProxyNode = ControllerUtil.requireCapability(params, Socks5ProxyCapable.class);
            return socks5ProxyNode.getSocks5ProxyStatus();
        });
    }
}
