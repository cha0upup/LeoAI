package org.leo.web.controller.puppetnode.service;

import org.leo.web.util.ControllerUtil;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;

/**
 * SUID/SGID/Capabilities 枚举控制器
 */
@RestController
@RequestMapping("/puppet-node/suid-caps")
public class SuidCapabilityController {

    @RequestMapping(value = "/suid", method = RequestMethod.POST)
    public HashMap<String, Object> listSuid(@RequestBody HashMap<String, Object> params) {
        return ControllerUtil.handlePuppetCall(params, "枚举 SUID 失败", node -> node.listSuidFiles());
    }

    @RequestMapping(value = "/sgid", method = RequestMethod.POST)
    public HashMap<String, Object> listSgid(@RequestBody HashMap<String, Object> params) {
        return ControllerUtil.handlePuppetCall(params, "枚举 SGID 失败", node -> node.listSgidFiles());
    }

    @RequestMapping(value = "/capabilities", method = RequestMethod.POST)
    public HashMap<String, Object> listCapabilities(@RequestBody HashMap<String, Object> params) {
        return ControllerUtil.handlePuppetCall(params, "枚举 Capabilities 失败", node -> node.listFileCapabilities());
    }

    @RequestMapping(value = "/all", method = RequestMethod.POST)
    public HashMap<String, Object> listAll(@RequestBody HashMap<String, Object> params) {
        return ControllerUtil.handlePuppetCall(params, "枚举失败", node -> node.listAllSuidCaps());
    }
}
