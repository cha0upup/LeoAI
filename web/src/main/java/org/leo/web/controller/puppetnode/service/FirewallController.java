package org.leo.web.controller.puppetnode.service;

import org.leo.web.util.ControllerUtil;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;

/**
 * 操作系统防火墙管理控制器
 */
@RestController
@RequestMapping("/puppet-node/firewall")
public class FirewallController {

    @RequestMapping(value = "/status", method = RequestMethod.POST)
    public HashMap<String, Object> status(@RequestBody HashMap<String, Object> params) {
        return ControllerUtil.handlePuppetCall(params, "获取防火墙状态失败", node -> node.getFirewallStatus());
    }

    @RequestMapping(value = "/list-rules", method = RequestMethod.POST)
    public HashMap<String, Object> listRules(@RequestBody HashMap<String, Object> params) {
        String direction = ControllerUtil.getStr(params, "direction");
        String profile = ControllerUtil.getStr(params, "profile");
        return ControllerUtil.handlePuppetCall(params, "获取防火墙规则失败", node -> node.listFirewallRules(direction, profile));
    }

    @RequestMapping(value = "/add-rule", method = RequestMethod.POST)
    public HashMap<String, Object> addRule(@RequestBody HashMap<String, Object> params) {
        String ruleName = ControllerUtil.getStr(params, "ruleName");
        String direction = ControllerUtil.getStr(params, "direction");
        String action = ControllerUtil.getStr(params, "action");
        String protocol = ControllerUtil.getStr(params, "protocol");
        String localPort = ControllerUtil.getStr(params, "localPort");
        String remotePort = ControllerUtil.getStr(params, "remotePort");
        String remoteAddress = ControllerUtil.getStr(params, "remoteAddress");
        String rawRule = ControllerUtil.getStr(params, "rawRule");
        return ControllerUtil.handlePuppetCall(params, "添加规则失败",
                node -> node.addFirewallRule(ruleName, direction, action, protocol, localPort, remotePort, remoteAddress, rawRule));
    }

    @RequestMapping(value = "/delete-rule", method = RequestMethod.POST)
    public HashMap<String, Object> deleteRule(@RequestBody HashMap<String, Object> params) {
        String ruleName = ControllerUtil.getStr(params, "ruleName");
        String ruleIndex = ControllerUtil.getStr(params, "ruleIndex");
        String rawRule = ControllerUtil.getStr(params, "rawRule");
        return ControllerUtil.handlePuppetCall(params, "删除规则失败", node -> node.deleteFirewallRule(ruleName, ruleIndex, rawRule));
    }

    @RequestMapping(value = "/toggle", method = RequestMethod.POST)
    public HashMap<String, Object> toggle(@RequestBody HashMap<String, Object> params) {
        boolean enable = !params.containsKey("enable") || ControllerUtil.getBool(params, "enable");
        return ControllerUtil.handlePuppetCall(params, "操作失败", node -> node.toggleFirewall(enable));
    }
}
