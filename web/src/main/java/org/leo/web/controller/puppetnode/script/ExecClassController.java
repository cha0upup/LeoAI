package org.leo.web.controller.puppetnode.script;

import org.leo.core.puppet.impl.JavaPuppetNode;
import org.leo.core.util.ApiResponse;
import org.leo.core.util.json.JsonUtil;
import org.leo.web.util.AuditLogUtil;
import org.leo.web.util.ControllerUtil;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/**
 * Java 字节码临时执行控制器：直接发起 puppet 侧 PluginComponent 调用，不持久化为插件。
 *
 * <p>底层走 PluginComponent，等价于走 JavaPluginService.invokePlugin() 但不需要先保存到 PluginManager。
 * 与 {@link ExecScriptController} 互补：脚本临时执行 vs class 临时执行。
 */
@RestController
@RequestMapping("/puppet-node")
public class ExecClassController {

    private static final String COMPONENT_PLUGIN = "PluginComponent";
    private static final String PARAM_BYTECODE_BASE64 = "bytecodeBase64";
    private static final String PARAM_PLUGIN_PARAM = "pluginParam";
    private static final String PAYLOAD_PLUGIN_PARAM = "pluginParam";
    private static final String PAYLOAD_PLUGIN_BYTECODE = "pluginBytecode";

    @RequestMapping(value = "/exec-class", method = RequestMethod.POST)
    public HashMap<String, Object> execClass(@RequestBody HashMap<String, Object> params) {
        JavaPuppetNode node = null;
        try {
            node = ControllerUtil.getPuppetNode(params);
            String bytecodeBase64 = ControllerUtil.getRequiredStringParam(params, PARAM_BYTECODE_BASE64);
            String pluginParamJson = ControllerUtil.getOptionalStringParam(params, PARAM_PLUGIN_PARAM);

            byte[] bytecode;
            try {
                bytecode = Base64.getDecoder().decode(bytecodeBase64);
            } catch (IllegalArgumentException e) {
                return ApiResponse.badRequest("bytecodeBase64 解码失败: " + e.getMessage());
            }

            HashMap<String, Object> payload = new HashMap<>();
            payload.put(PAYLOAD_PLUGIN_PARAM, parsePluginParam(pluginParamJson));
            payload.put(PAYLOAD_PLUGIN_BYTECODE, bytecode);

            Map<String, Object> result = node.invokeComponent(COMPONENT_PLUGIN, payload);
            AuditLogUtil.logSuccess(node, "EXEC_CLASS", "临时执行字节码", null, params,
                    ApiResponse.CODE_SUCCESS, "字节码执行成功", AuditLogUtil.getClientIp());
            return ApiResponse.success(result != null ? result : new HashMap<>());
        } catch (IllegalArgumentException e) {
            AuditLogUtil.logFailure(node, "EXEC_CLASS", "临时执行字节码", null, params,
                    e.getMessage(), AuditLogUtil.getClientIp());
            return ApiResponse.badRequest(e.getMessage());
        } catch (Exception e) {
            AuditLogUtil.logFailure(node, "EXEC_CLASS", "临时执行字节码", null, params,
                    e.getMessage(), AuditLogUtil.getClientIp());
            return ApiResponse.error("字节码执行失败: " + e.getMessage());
        }
    }

    /** 与 JavaPluginService.parsePluginParam 行为保持一致：null/空白返回空 Map；非 JSON 对象抛出。 */
    @SuppressWarnings("unchecked")
    private Map<String, Object> parsePluginParam(String pluginParamJson) {
        HashMap<String, Object> pluginParam = new HashMap<>();
        if (pluginParamJson == null || pluginParamJson.isBlank()) {
            return pluginParam;
        }
        Object parsed = JsonUtil.fromJsonString(pluginParamJson, HashMap.class);
        if (!(parsed instanceof Map<?, ?> parsedMap)) {
            throw new IllegalArgumentException("pluginParam必须是JSON对象");
        }
        for (Map.Entry<?, ?> entry : parsedMap.entrySet()) {
            pluginParam.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return pluginParam;
    }
}
