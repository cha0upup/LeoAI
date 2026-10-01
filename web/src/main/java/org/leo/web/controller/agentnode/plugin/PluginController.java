package org.leo.web.controller.agentnode.plugin;

import org.leo.core.agent.AbstractAgentNode;
import org.leo.core.util.ApiResponse;
import org.leo.service.plugin.PluginExecutionService;
import org.leo.web.util.ControllerUtil;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/** Runtime-neutral saved-plugin invocation endpoint. */
@RestController
@RequestMapping("/agent-node/plugin")
public final class PluginController {

    private final PluginExecutionService pluginExecutionService;

    public PluginController(PluginExecutionService pluginExecutionService) {
        this.pluginExecutionService = pluginExecutionService;
    }

    @PostMapping("/invoke")
    public HashMap<String, Object> invoke(@RequestBody HashMap<String, Object> params) {
        try {
            String pluginId = ControllerUtil.getRequiredStringParam(params, "pluginId");
            AbstractAgentNode node = ControllerUtil.getAbstractAgentNode(params);
            Map<String, Object> result = pluginExecutionService.invokePlugin(
                    node, pluginId, params.get("pluginParam"));
            return ApiResponse.success(result);
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        } catch (Exception e) {
            return ApiResponse.error("调用插件失败: " + e.getMessage());
        }
    }

}
