package org.leo.web.controller.puppetnode.service;

import org.leo.core.util.ApiResponse;
import org.leo.web.util.ControllerUtil;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;

/**
 * 操作系统持久化机制枚举控制器
 */
@RestController
@RequestMapping("/puppet-node/persistence")
public class PersistenceController {

    @RequestMapping(value = "/list", method = RequestMethod.POST)
    public HashMap<String, Object> list(@RequestBody HashMap<String, Object> params) {
        return ControllerUtil.handlePuppetCall(params, "获取持久化条目失败", node -> node.listPersistence());
    }

    @RequestMapping(value = "/query", method = RequestMethod.POST)
    public HashMap<String, Object> query(@RequestBody HashMap<String, Object> params) {
        String name = ControllerUtil.getStr(params, "name");
        String type = ControllerUtil.getStr(params, "type");
        String path = ControllerUtil.getStr(params, "path");
        if (name == null && path == null) return ApiResponse.badRequest("name 或 path 参数必填");
        return ControllerUtil.handlePuppetCall(params, "查询持久化条目失败",
                node -> node.queryPersistence(name, type, path));
    }
}
