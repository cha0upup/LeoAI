package org.leo.web.controller.puppetnode.service;

import org.leo.core.util.ApiResponse;
import org.leo.web.util.ControllerUtil;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;

/**
 * 操作系统服务管理控制器
 */
@RestController
@RequestMapping("/puppet-node/service")
public class ServiceController {

    @RequestMapping(value = "/list", method = RequestMethod.POST)
    public HashMap<String, Object> list(@RequestBody HashMap<String, Object> params) {
        return ControllerUtil.handlePuppetCall(params, "获取服务列表失败",
                node -> node.listServices());
    }

    @RequestMapping(value = "/query", method = RequestMethod.POST)
    public HashMap<String, Object> query(@RequestBody HashMap<String, Object> params) {
        String serviceName = ControllerUtil.getStr(params, "serviceName");
        if (serviceName == null) return ApiResponse.badRequest("serviceName 参数必填");
        return ControllerUtil.handlePuppetCall(params, "查询服务失败",
                node -> node.queryService(serviceName));
    }

    @RequestMapping(value = "/start", method = RequestMethod.POST)
    public HashMap<String, Object> start(@RequestBody HashMap<String, Object> params) {
        String serviceName = ControllerUtil.getStr(params, "serviceName");
        if (serviceName == null) return ApiResponse.badRequest("serviceName 参数必填");
        return ControllerUtil.handlePuppetCall(params, "启动服务失败",
                node -> node.startService(serviceName));
    }

    @RequestMapping(value = "/stop", method = RequestMethod.POST)
    public HashMap<String, Object> stop(@RequestBody HashMap<String, Object> params) {
        String serviceName = ControllerUtil.getStr(params, "serviceName");
        if (serviceName == null) return ApiResponse.badRequest("serviceName 参数必填");
        return ControllerUtil.handlePuppetCall(params, "停止服务失败",
                node -> node.stopService(serviceName));
    }

    @RequestMapping(value = "/restart", method = RequestMethod.POST)
    public HashMap<String, Object> restart(@RequestBody HashMap<String, Object> params) {
        String serviceName = ControllerUtil.getStr(params, "serviceName");
        if (serviceName == null) return ApiResponse.badRequest("serviceName 参数必填");
        return ControllerUtil.handlePuppetCall(params, "重启服务失败",
                node -> node.restartService(serviceName));
    }

    @RequestMapping(value = "/toggle-auto-start", method = RequestMethod.POST)
    public HashMap<String, Object> toggleAutoStart(@RequestBody HashMap<String, Object> params) {
        String serviceName = ControllerUtil.getStr(params, "serviceName");
        if (serviceName == null) return ApiResponse.badRequest("serviceName 参数必填");
        boolean enable = !params.containsKey("enable") || ControllerUtil.getBool(params, "enable");
        return ControllerUtil.handlePuppetCall(params, "操作失败",
                node -> enable ? node.enableService(serviceName) : node.disableService(serviceName));
    }

    @RequestMapping(value = "/create", method = RequestMethod.POST)
    public HashMap<String, Object> create(@RequestBody HashMap<String, Object> params) {
        String serviceName = ControllerUtil.getStr(params, "serviceName");
        String binPath = ControllerUtil.getStr(params, "binPath");
        if (serviceName == null) return ApiResponse.badRequest("serviceName 参数必填");
        if (binPath == null) return ApiResponse.badRequest("binPath 参数必填");
        return ControllerUtil.handlePuppetCall(params, "创建服务失败",
                node -> node.createService(serviceName, binPath,
                        ControllerUtil.getStr(params, "displayName"),
                        ControllerUtil.getStr(params, "startType")));
    }

    @RequestMapping(value = "/delete", method = RequestMethod.POST)
    public HashMap<String, Object> delete(@RequestBody HashMap<String, Object> params) {
        String serviceName = ControllerUtil.getStr(params, "serviceName");
        if (serviceName == null) return ApiResponse.badRequest("serviceName 参数必填");
        return ControllerUtil.handlePuppetCall(params, "删除服务失败",
                node -> node.deleteService(serviceName));
    }
}
