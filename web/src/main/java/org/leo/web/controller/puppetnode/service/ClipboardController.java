package org.leo.web.controller.puppetnode.service;

import org.leo.core.util.ApiResponse;
import org.leo.web.util.ControllerUtil;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;

/**
 * 剪贴板操作控制器
 */
@RestController
@RequestMapping("/puppet-node/clipboard")
public class ClipboardController {

    @RequestMapping(value = "/read", method = RequestMethod.POST)
    public HashMap<String, Object> read(@RequestBody HashMap<String, Object> params) {
        return ControllerUtil.handlePuppetCall(params, "读取剪贴板失败", node -> node.readClipboard());
    }

    @RequestMapping(value = "/write", method = RequestMethod.POST)
    public HashMap<String, Object> write(@RequestBody HashMap<String, Object> params) {
        String content = ControllerUtil.getStr(params, "content");
        if (content == null) return ApiResponse.badRequest("content 参数必填");
        return ControllerUtil.handlePuppetCall(params, "写入剪贴板失败", node -> node.writeClipboard(content));
    }

    @RequestMapping(value = "/monitor", method = RequestMethod.POST)
    public HashMap<String, Object> monitor(@RequestBody HashMap<String, Object> params) {
        int duration = ControllerUtil.getInt(params, "duration", 10);
        int interval = ControllerUtil.getInt(params, "interval", 1);
        return ControllerUtil.handlePuppetCall(params, "监控剪贴板失败", node -> node.monitorClipboard(duration, interval));
    }
}
