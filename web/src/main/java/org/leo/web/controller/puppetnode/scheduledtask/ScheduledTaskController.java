package org.leo.web.controller.puppetnode.scheduledtask;

import org.leo.core.util.ApiResponse;
import org.leo.web.util.ControllerUtil;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;

/**
 * 操作系统计划任务管理控制器
 */
@RestController
@RequestMapping("/puppet-node/scheduled-task")
public class ScheduledTaskController {

    @RequestMapping(value = "/list", method = RequestMethod.POST)
    public HashMap<String, Object> list(@RequestBody HashMap<String, Object> params) {
        return ControllerUtil.handlePuppetCall(params, "获取计划任务失败", node -> node.listScheduledTasks());
    }

    @RequestMapping(value = "/query", method = RequestMethod.POST)
    public HashMap<String, Object> query(@RequestBody HashMap<String, Object> params) {
        String taskName = ControllerUtil.getStr(params, "taskName");
        if (taskName == null) return ApiResponse.badRequest("taskName 参数必填");
        return ControllerUtil.handlePuppetCall(params, "查询计划任务失败", node -> node.queryScheduledTask(taskName));
    }

    @RequestMapping(value = "/create", method = RequestMethod.POST)
    public HashMap<String, Object> create(@RequestBody HashMap<String, Object> params) {
        String command = ControllerUtil.getStr(params, "command");
        if (command == null) return ApiResponse.badRequest("command 参数必填");

        String cronExpr = ControllerUtil.getStr(params, "cronExpression");
        if (cronExpr != null) {
            return ControllerUtil.handlePuppetCall(params, "创建计划任务失败",
                    node -> node.createScheduledTaskLinux(cronExpr, command));
        }

        String taskName = ControllerUtil.getStr(params, "taskName");
        String schedule = ControllerUtil.getStr(params, "schedule");
        if (taskName == null) return ApiResponse.badRequest("taskName 参数必填");
        if (schedule == null) return ApiResponse.badRequest("schedule 参数必填");
        String modifier = ControllerUtil.getStr(params, "modifier");
        String startTime = ControllerUtil.getStr(params, "startTime");
        String startDate = ControllerUtil.getStr(params, "startDate");
        String runAs = ControllerUtil.getStr(params, "runAs");
        boolean force = !params.containsKey("force") || ControllerUtil.getBool(params, "force");
        return ControllerUtil.handlePuppetCall(params, "创建计划任务失败",
                node -> node.createScheduledTaskWindows(taskName, command, schedule, modifier, startTime, startDate, runAs, force));
    }

    @RequestMapping(value = "/delete", method = RequestMethod.POST)
    public HashMap<String, Object> delete(@RequestBody HashMap<String, Object> params) {
        String taskName = ControllerUtil.getStr(params, "taskName");
        if (taskName == null) return ApiResponse.badRequest("taskName 参数必填");
        return ControllerUtil.handlePuppetCall(params, "删除计划任务失败", node -> node.deleteScheduledTask(taskName));
    }

    @RequestMapping(value = "/run", method = RequestMethod.POST)
    public HashMap<String, Object> run(@RequestBody HashMap<String, Object> params) {
        String taskName = ControllerUtil.getStr(params, "taskName");
        if (taskName == null) return ApiResponse.badRequest("taskName 参数必填");
        return ControllerUtil.handlePuppetCall(params, "运行计划任务失败", node -> node.runScheduledTask(taskName));
    }

    @RequestMapping(value = "/toggle", method = RequestMethod.POST)
    public HashMap<String, Object> toggle(@RequestBody HashMap<String, Object> params) {
        String taskName = ControllerUtil.getStr(params, "taskName");
        if (taskName == null) return ApiResponse.badRequest("taskName 参数必填");
        boolean enable = !params.containsKey("enable") || ControllerUtil.getBool(params, "enable");
        return ControllerUtil.handlePuppetCall(params, "操作失败",
                node -> enable ? node.enableScheduledTask(taskName) : node.disableScheduledTask(taskName));
    }
}
