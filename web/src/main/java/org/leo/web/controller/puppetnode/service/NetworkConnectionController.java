package org.leo.web.controller.puppetnode.service;

import org.leo.web.util.ControllerUtil;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;

/**
 * 网络连接实时采集控制器
 */
@RestController
@RequestMapping("/puppet-node/network-connection")
public class NetworkConnectionController {

    @RequestMapping(value = "/list", method = RequestMethod.POST)
    public HashMap<String, Object> list(@RequestBody HashMap<String, Object> params) {
        String state = ControllerUtil.getStr(params, "state");
        String protocol = ControllerUtil.getStr(params, "protocol");
        String port = ControllerUtil.getStr(params, "port");
        String pid = ControllerUtil.getStr(params, "pid");
        String process = ControllerUtil.getStr(params, "process");
        String remoteIp = ControllerUtil.getStr(params, "remoteIp");
        boolean listeningOnly = ControllerUtil.getBool(params, "listeningOnly");
        int maxEntries = ControllerUtil.getInt(params, "maxEntries", 2000);
        return ControllerUtil.handlePuppetCall(params, "获取网络连接失败",
                node -> node.listNetworkConnections(state, protocol, port, pid, process, remoteIp, listeningOnly, maxEntries));
    }

    @RequestMapping(value = "/summary", method = RequestMethod.POST)
    public HashMap<String, Object> summary(@RequestBody HashMap<String, Object> params) {
        return ControllerUtil.handlePuppetCall(params, "获取网络连接统计失败", node -> node.networkConnectionSummary());
    }
}
