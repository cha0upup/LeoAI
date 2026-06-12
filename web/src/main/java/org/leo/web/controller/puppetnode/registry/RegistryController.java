package org.leo.web.controller.puppetnode.registry;

import org.leo.core.util.ApiResponse;
import org.leo.web.util.ControllerUtil;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;

/**
 * Windows 注册表管理控制器
 * 前端 API 入口，对应 RegistryService
 */
@RestController
@RequestMapping("/puppet-node/registry")
public class RegistryController {

    /**
     * 查询注册表键
     */
    @RequestMapping(value = "/query", method = RequestMethod.POST)
    public HashMap<String, Object> query(@RequestBody HashMap<String, Object> params) {
        String keyPath = ControllerUtil.getStr(params, "keyPath");
        if (keyPath == null) {
            return ApiResponse.badRequest("keyPath 参数必填");
        }
        boolean recursive = ControllerUtil.getBool(params, "recursive");
        return ControllerUtil.handlePuppetCall(params, "查询注册表失败", node -> node.queryRegistry(keyPath, recursive));
    }

    /**
     * 搜索注册表
     */
    @RequestMapping(value = "/search", method = RequestMethod.POST)
    public HashMap<String, Object> search(@RequestBody HashMap<String, Object> params) {
        String keyPath = ControllerUtil.getStr(params, "keyPath");
        if (keyPath == null) keyPath = "HKLM";
        String pattern = ControllerUtil.getStr(params, "pattern");
        if (pattern == null) {
            return ApiResponse.badRequest("pattern 参数必填");
        }
        String searchTarget = ControllerUtil.getStr(params, "searchTarget");
        if (searchTarget == null) searchTarget = "d";
        int maxResults = ControllerUtil.getInt(params, "maxResults", 50);
        String finalKeyPath = keyPath;
        String finalSearchTarget = searchTarget;
        return ControllerUtil.handlePuppetCall(params, "搜索注册表失败",
                node -> node.searchRegistry(finalKeyPath, pattern, finalSearchTarget, maxResults));
    }

    /**
     * 创建/修改注册表值
     */
    @RequestMapping(value = "/add", method = RequestMethod.POST)
    public HashMap<String, Object> add(@RequestBody HashMap<String, Object> params) {
        String keyPath = ControllerUtil.getStr(params, "keyPath");
        if (keyPath == null) {
            return ApiResponse.badRequest("keyPath 参数必填");
        }
        String valueName = ControllerUtil.getStr(params, "valueName");
        String valueType = ControllerUtil.getStr(params, "valueType");
        if (valueType == null) valueType = "REG_SZ";
        String valueData = ControllerUtil.getStr(params, "valueData");
        if (valueData == null) valueData = "";
        boolean force = !params.containsKey("force") || ControllerUtil.getBool(params, "force");
        String finalValueType = valueType;
        String finalValueData = valueData;
        return ControllerUtil.handlePuppetCall(params, "添加注册表值失败",
                node -> node.addRegistry(keyPath, valueName, finalValueType, finalValueData, force));
    }

    /**
     * 删除注册表键或值
     */
    @RequestMapping(value = "/delete", method = RequestMethod.POST)
    public HashMap<String, Object> delete(@RequestBody HashMap<String, Object> params) {
        String keyPath = ControllerUtil.getStr(params, "keyPath");
        if (keyPath == null) {
            return ApiResponse.badRequest("keyPath 参数必填");
        }
        String valueName = ControllerUtil.getStr(params, "valueName");
        boolean force = !params.containsKey("force") || ControllerUtil.getBool(params, "force");
        return ControllerUtil.handlePuppetCall(params, "删除注册表失败",
                node -> node.deleteRegistry(keyPath, valueName, force));
    }

    /**
     * 导出注册表
     */
    @RequestMapping(value = "/export", method = RequestMethod.POST)
    public HashMap<String, Object> export(@RequestBody HashMap<String, Object> params) {
        String keyPath = ControllerUtil.getStr(params, "keyPath");
        if (keyPath == null) {
            return ApiResponse.badRequest("keyPath 参数必填");
        }
        return ControllerUtil.handlePuppetCall(params, "导出注册表失败", node -> node.exportRegistry(keyPath));
    }
}
