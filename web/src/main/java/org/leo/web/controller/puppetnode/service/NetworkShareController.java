package org.leo.web.controller.puppetnode.service;

import org.leo.web.util.ControllerUtil;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;

/**
 * 操作系统网络共享管理控制器
 */
@RestController
@RequestMapping("/puppet-node/network-share")
public class NetworkShareController {

    @RequestMapping(value = "/list-shares", method = RequestMethod.POST)
    public HashMap<String, Object> listShares(@RequestBody HashMap<String, Object> params) {
        return ControllerUtil.handlePuppetCall(params, "获取网络共享列表失败",
                node -> node.listNetworkShares());
    }

    @RequestMapping(value = "/list-mounts", method = RequestMethod.POST)
    public HashMap<String, Object> listMounts(@RequestBody HashMap<String, Object> params) {
        return ControllerUtil.handlePuppetCall(params, "获取挂载列表失败",
                node -> node.listNetworkMounts());
    }

    @RequestMapping(value = "/query-share", method = RequestMethod.POST)
    public HashMap<String, Object> queryShare(@RequestBody HashMap<String, Object> params) {
        return ControllerUtil.handlePuppetCall(params, "查询共享详情失败",
                node -> node.queryNetworkShare(ControllerUtil.getStr(params, "shareName")));
    }

    @RequestMapping(value = "/connect", method = RequestMethod.POST)
    public HashMap<String, Object> connect(@RequestBody HashMap<String, Object> params) {
        return ControllerUtil.handlePuppetCall(params, "连接共享失败",
                node -> node.connectNetworkShare(
                        ControllerUtil.getStr(params, "remotePath"),
                        ControllerUtil.getStr(params, "localDrive"),
                        ControllerUtil.getStr(params, "mountPoint"),
                        ControllerUtil.getStr(params, "username"),
                        ControllerUtil.getStr(params, "password")));
    }

    @RequestMapping(value = "/disconnect", method = RequestMethod.POST)
    public HashMap<String, Object> disconnect(@RequestBody HashMap<String, Object> params) {
        return ControllerUtil.handlePuppetCall(params, "断开共享失败",
                node -> node.disconnectNetworkShare(ControllerUtil.getStr(params, "target")));
    }
}
