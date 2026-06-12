package org.leo.web.controller.puppetnode.service;

import org.leo.core.util.ApiResponse;
import org.leo.web.util.ControllerUtil;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;

/**
 * 已安装软件枚举控制器
 */
@RestController
@RequestMapping("/puppet-node/installed-software")
public class InstalledSoftwareController {

    @RequestMapping(value = "/list-all", method = RequestMethod.POST)
    public HashMap<String, Object> listAll(@RequestBody HashMap<String, Object> params) {
        return ControllerUtil.handlePuppetCall(params, "获取软件列表失败", node -> node.listAllSoftware());
    }

    @RequestMapping(value = "/list-system", method = RequestMethod.POST)
    public HashMap<String, Object> listSystem(@RequestBody HashMap<String, Object> params) {
        return ControllerUtil.handlePuppetCall(params, "获取系统软件列表失败", node -> node.listSystemSoftware());
    }

    @RequestMapping(value = "/list-user", method = RequestMethod.POST)
    public HashMap<String, Object> listUser(@RequestBody HashMap<String, Object> params) {
        return ControllerUtil.handlePuppetCall(params, "获取用户软件列表失败", node -> node.listUserSoftware());
    }

    @RequestMapping(value = "/search", method = RequestMethod.POST)
    public HashMap<String, Object> search(@RequestBody HashMap<String, Object> params) {
        String keyword = ControllerUtil.getStr(params, "keyword");
        if (keyword == null) return ApiResponse.badRequest("keyword 参数必填");
        return ControllerUtil.handlePuppetCall(params, "搜索软件失败", node -> node.searchSoftware(keyword));
    }
}
