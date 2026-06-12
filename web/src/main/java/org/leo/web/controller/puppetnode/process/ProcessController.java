package org.leo.web.controller.puppetnode.process;

import org.leo.core.util.ApiResponse;
import org.leo.web.util.ControllerUtil;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;

/**
 * 进程管理控制器
 * 前端 API 入口,对应 ProcessService
 */
@RestController
@RequestMapping("/puppet-node/process")
public class ProcessController {

    /** 列举所有进程 */
    @RequestMapping(value = "/list", method = RequestMethod.POST)
    public HashMap<String, Object> list(@RequestBody HashMap<String, Object> params) {
        return ControllerUtil.handlePuppetCall(params, "获取进程列表失败",
                node -> node.listProcesses());
    }

    /**
     * 按条件查找进程: name 关键字 / 精确 pid / 监听 port
     */
    @RequestMapping(value = "/find", method = RequestMethod.POST)
    public HashMap<String, Object> find(@RequestBody HashMap<String, Object> params) {
        return ControllerUtil.handlePuppetCall(params, "查找进程失败", node -> node.findProcesses(
                ControllerUtil.getStr(params, "name"),
                ControllerUtil.getInt(params, "pid", -1),
                ControllerUtil.getInt(params, "port", -1)));
    }

    /** 终止进程: pid 必填,force 可选 */
    @RequestMapping(value = "/kill", method = RequestMethod.POST)
    public HashMap<String, Object> kill(@RequestBody HashMap<String, Object> params) {
        int pid = ControllerUtil.getInt(params, "pid", -1);
        if (pid <= 0) return ApiResponse.badRequest("pid 参数必填且大于 0");
        boolean force = ControllerUtil.getBool(params, "force");
        return ControllerUtil.handlePuppetCall(params, "终止进程失败",
                node -> node.killProcess(pid, force));
    }
}
