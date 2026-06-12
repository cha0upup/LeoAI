package org.leo.web.controller.puppetnode.service;

import org.leo.core.util.ApiResponse;
import org.leo.web.util.ControllerUtil;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;

/**
 * Docker 容器管理控制器
 */
@RestController
@RequestMapping("/puppet-node/docker")
public class DockerContainerController {

    @RequestMapping(value = "/list-containers", method = RequestMethod.POST)
    public HashMap<String, Object> listContainers(@RequestBody HashMap<String, Object> params) {
        boolean all = !params.containsKey("all") || ControllerUtil.getBool(params, "all");
        return ControllerUtil.handlePuppetCall(params, "获取容器列表失败", node -> node.listDockerContainers(all));
    }

    @RequestMapping(value = "/list-images", method = RequestMethod.POST)
    public HashMap<String, Object> listImages(@RequestBody HashMap<String, Object> params) {
        return ControllerUtil.handlePuppetCall(params, "获取镜像列表失败", node -> node.listDockerImages());
    }

    @RequestMapping(value = "/inspect", method = RequestMethod.POST)
    public HashMap<String, Object> inspect(@RequestBody HashMap<String, Object> params) {
        String containerId = ControllerUtil.getStr(params, "containerId");
        if (containerId == null) return ApiResponse.badRequest("containerId 参数必填");
        return ControllerUtil.handlePuppetCall(params, "容器详情获取失败", node -> node.inspectDockerContainer(containerId));
    }

    @RequestMapping(value = "/logs", method = RequestMethod.POST)
    public HashMap<String, Object> logs(@RequestBody HashMap<String, Object> params) {
        String containerId = ControllerUtil.getStr(params, "containerId");
        if (containerId == null) return ApiResponse.badRequest("containerId 参数必填");
        int tail = ControllerUtil.getInt(params, "tail", 100);
        return ControllerUtil.handlePuppetCall(params, "获取容器日志失败", node -> node.getDockerContainerLogs(containerId, tail));
    }

    @RequestMapping(value = "/list-networks", method = RequestMethod.POST)
    public HashMap<String, Object> listNetworks(@RequestBody HashMap<String, Object> params) {
        return ControllerUtil.handlePuppetCall(params, "获取网络列表失败", node -> node.listDockerNetworks());
    }

    @RequestMapping(value = "/info", method = RequestMethod.POST)
    public HashMap<String, Object> info(@RequestBody HashMap<String, Object> params) {
        return ControllerUtil.handlePuppetCall(params, "获取 Docker 信息失败", node -> node.getDockerInfo());
    }

    @RequestMapping(value = "/exec", method = RequestMethod.POST)
    public HashMap<String, Object> exec(@RequestBody HashMap<String, Object> params) {
        String containerId = ControllerUtil.getStr(params, "containerId");
        String cmd = ControllerUtil.getStr(params, "cmd");
        if (containerId == null) return ApiResponse.badRequest("containerId 参数必填");
        if (cmd == null) return ApiResponse.badRequest("cmd 参数必填");
        return ControllerUtil.handlePuppetCall(params, "容器命令执行失败", node -> node.execInDockerContainer(containerId, cmd));
    }

    @RequestMapping(value = "/start", method = RequestMethod.POST)
    public HashMap<String, Object> start(@RequestBody HashMap<String, Object> params) {
        String containerId = ControllerUtil.getStr(params, "containerId");
        if (containerId == null) return ApiResponse.badRequest("containerId 参数必填");
        return ControllerUtil.handlePuppetCall(params, "启动容器失败", node -> node.startDockerContainer(containerId));
    }

    @RequestMapping(value = "/stop", method = RequestMethod.POST)
    public HashMap<String, Object> stop(@RequestBody HashMap<String, Object> params) {
        String containerId = ControllerUtil.getStr(params, "containerId");
        if (containerId == null) return ApiResponse.badRequest("containerId 参数必填");
        int timeout = ControllerUtil.getInt(params, "timeout", 10);
        return ControllerUtil.handlePuppetCall(params, "停止容器失败", node -> node.stopDockerContainer(containerId, timeout));
    }

    @RequestMapping(value = "/restart", method = RequestMethod.POST)
    public HashMap<String, Object> restart(@RequestBody HashMap<String, Object> params) {
        String containerId = ControllerUtil.getStr(params, "containerId");
        if (containerId == null) return ApiResponse.badRequest("containerId 参数必填");
        int timeout = ControllerUtil.getInt(params, "timeout", 10);
        return ControllerUtil.handlePuppetCall(params, "重启容器失败", node -> node.restartDockerContainer(containerId, timeout));
    }

    @RequestMapping(value = "/pause", method = RequestMethod.POST)
    public HashMap<String, Object> pause(@RequestBody HashMap<String, Object> params) {
        String containerId = ControllerUtil.getStr(params, "containerId");
        if (containerId == null) return ApiResponse.badRequest("containerId 参数必填");
        return ControllerUtil.handlePuppetCall(params, "暂停容器失败", node -> node.pauseDockerContainer(containerId));
    }

    @RequestMapping(value = "/unpause", method = RequestMethod.POST)
    public HashMap<String, Object> unpause(@RequestBody HashMap<String, Object> params) {
        String containerId = ControllerUtil.getStr(params, "containerId");
        if (containerId == null) return ApiResponse.badRequest("containerId 参数必填");
        return ControllerUtil.handlePuppetCall(params, "恢复容器失败", node -> node.unpauseDockerContainer(containerId));
    }

    @RequestMapping(value = "/remove-container", method = RequestMethod.POST)
    public HashMap<String, Object> removeContainer(@RequestBody HashMap<String, Object> params) {
        String containerId = ControllerUtil.getStr(params, "containerId");
        if (containerId == null) return ApiResponse.badRequest("containerId 参数必填");
        boolean force = ControllerUtil.getBool(params, "force");
        return ControllerUtil.handlePuppetCall(params, "删除容器失败", node -> node.removeDockerContainer(containerId, force));
    }

    @RequestMapping(value = "/remove-image", method = RequestMethod.POST)
    public HashMap<String, Object> removeImage(@RequestBody HashMap<String, Object> params) {
        String imageId = ControllerUtil.getStr(params, "imageId");
        if (imageId == null) return ApiResponse.badRequest("imageId 参数必填");
        boolean force = ControllerUtil.getBool(params, "force");
        return ControllerUtil.handlePuppetCall(params, "删除镜像失败", node -> node.removeDockerImage(imageId, force));
    }
}
