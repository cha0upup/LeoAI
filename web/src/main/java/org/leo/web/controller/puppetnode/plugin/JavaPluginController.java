package org.leo.web.controller.puppetnode.plugin;


import org.leo.core.puppet.impl.JavaPuppetNode;
import org.leo.service.puppetnode.plugin.JavaPluginService;
import org.leo.core.util.ApiResponse;
import org.leo.web.util.AuditLogUtil;
import org.leo.web.util.ControllerUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/puppet-node/java-plugin")
public class JavaPluginController {

    private static final String PARAM_PLUGIN_ID = "pluginId";
    private static final String PARAM_PLUGIN_PARAM = "pluginParam";

    @Autowired
    private JavaPluginService javaPluginService;

    /**
     * 调用Java插件
     */
    @RequestMapping(value = "/invoke", method = RequestMethod.POST)
    public HashMap<String, Object> invokePlugin(@RequestBody HashMap<String, Object> params) throws Exception {
        JavaPuppetNode javaPuppetNode = null;
        String pluginId = null;
        try {

            pluginId = ControllerUtil.getRequiredStringParam(params, PARAM_PLUGIN_ID);
            javaPuppetNode = ControllerUtil.getPuppetNode(params);
            String pluginParamStr = ControllerUtil.getOptionalStringParam(params, PARAM_PLUGIN_PARAM);
            Map<String, Object> results = javaPluginService.invokePlugin(javaPuppetNode, pluginId, pluginParamStr);

            AuditLogUtil.logSuccess(javaPuppetNode, "PLUGIN_INVOKE", "调用Java插件", pluginId, params,
                    ApiResponse.CODE_SUCCESS, "调用插件成功", AuditLogUtil.getClientIp());
            return ApiResponse.success(results);
        } catch (IllegalArgumentException e) {
            AuditLogUtil.logFailure(javaPuppetNode, "PLUGIN_INVOKE", "调用Java插件", pluginId, params,
                    e.getMessage(), AuditLogUtil.getClientIp());
            if (e.getMessage() != null && e.getMessage().startsWith("插件不存在")) {
                return ApiResponse.notFound(e.getMessage());
            }
            return ApiResponse.badRequest(e.getMessage());
        } catch (Exception e) {
            AuditLogUtil.logFailure(javaPuppetNode, "PLUGIN_INVOKE", "调用Java插件", pluginId, params,
                    e.getMessage(), AuditLogUtil.getClientIp());
            return ApiResponse.error("调用插件失败: " + e.getMessage());
        }
    }

}
