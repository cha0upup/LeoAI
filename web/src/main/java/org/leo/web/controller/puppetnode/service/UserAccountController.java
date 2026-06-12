package org.leo.web.controller.puppetnode.service;

import org.leo.core.util.ApiResponse;
import org.leo.web.util.ControllerUtil;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;

/**
 * 操作系统用户与组管理控制器
 */
@RestController
@RequestMapping("/puppet-node/user-account")
public class UserAccountController {

    @RequestMapping(value = "/list-users", method = RequestMethod.POST)
    public HashMap<String, Object> listUsers(@RequestBody HashMap<String, Object> params) {
        return ControllerUtil.handlePuppetCall(params, "获取用户列表失败", node -> node.listUsers());
    }

    @RequestMapping(value = "/list-groups", method = RequestMethod.POST)
    public HashMap<String, Object> listGroups(@RequestBody HashMap<String, Object> params) {
        return ControllerUtil.handlePuppetCall(params, "获取组列表失败", node -> node.listGroups());
    }

    @RequestMapping(value = "/query-user", method = RequestMethod.POST)
    public HashMap<String, Object> queryUser(@RequestBody HashMap<String, Object> params) {
        String username = ControllerUtil.getStr(params, "username");
        if (username == null) return ApiResponse.badRequest("username 参数必填");
        return ControllerUtil.handlePuppetCall(params, "查询用户失败", node -> node.queryUser(username));
    }

    @RequestMapping(value = "/query-group", method = RequestMethod.POST)
    public HashMap<String, Object> queryGroup(@RequestBody HashMap<String, Object> params) {
        String groupName = ControllerUtil.getStr(params, "groupName");
        if (groupName == null) return ApiResponse.badRequest("groupName 参数必填");
        return ControllerUtil.handlePuppetCall(params, "查询组失败", node -> node.queryGroup(groupName));
    }

    @RequestMapping(value = "/whoami", method = RequestMethod.POST)
    public HashMap<String, Object> whoami(@RequestBody HashMap<String, Object> params) {
        return ControllerUtil.handlePuppetCall(params, "获取当前用户信息失败", node -> node.whoami());
    }
}
