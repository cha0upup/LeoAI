package org.leo.service.puppetnode.plugin;

import org.leo.core.entity.Plugin;
import org.leo.core.manager.PluginManager;
import org.leo.core.puppet.impl.JavaPuppetNode;
import org.leo.core.util.json.JsonUtil;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

@Service
public class JavaPluginService {

    /** 字节码插件类型：bytecode 是 JVM .class 字节码，发给 PluginComponent 加载执行。 */
    public static final String PLUGIN_TYPE_JAVA = "java";

    private static final String COMPONENT_PLUGIN = "PluginComponent";
    private static final String RESULT_PLUGIN_PARAM = "pluginParam";
    private static final String RESULT_PLUGIN_BYTECODE = "pluginBytecode";

    public Map<String, Object> invokePlugin(JavaPuppetNode javaPuppetNode, String pluginId, String pluginParamJson) throws Exception {
        if (javaPuppetNode == null) {
            throw new IllegalArgumentException("puppetNode不能为空");
        }
        Plugin plugin = getRequiredPlugin(pluginId);
        String type = plugin.getPluginType();

        Map<String, Object> results;
        if (type == null || PLUGIN_TYPE_JAVA.equalsIgnoreCase(type)) {
            // Java 字节码插件：发 bytecode + 业务参数给 PluginComponent
            Map<String, Object> payload = buildInvokePayload(plugin, pluginParamJson);
            results = javaPuppetNode.invokeComponent(COMPONENT_PLUGIN, payload);
        } else {
            // 脚本插件：bytecode 字段保存 UTF-8 脚本文本，走 node.execScript()
            // 与 AI 工具 ScriptTools.execScript() 完全相同的路径，复用 ExecScriptService
            // 实例与组件加载缓存，避免组件未加载导致响应解码为空。
            // 注：当前 ExecScriptComponent 不支持参数注入，pluginParamJson 暂不使用。
            byte[] bytecode = plugin.getBytecode();
            if (bytecode == null || bytecode.length == 0) {
                throw new IllegalArgumentException("脚本内容为空: " + plugin.getPluginId());
            }
            String script = new String(bytecode, StandardCharsets.UTF_8);
            results = javaPuppetNode.execScript(type, script);
        }
        if (results == null) {
            throw new IllegalStateException("组件调用返回结果为空");
        }
        return results;
    }

    public Plugin getRequiredPlugin(String pluginId) {
        String normalizedPluginId = requireNonBlank(pluginId, "pluginId不能为空");
        Plugin plugin = PluginManager.getInstance().getPluginById(normalizedPluginId);
        if (plugin == null) {
            throw new IllegalArgumentException("插件不存在: " + normalizedPluginId);
        }
        return plugin;
    }

    public ArrayList<Plugin> getAllPlugins() {
        return PluginManager.getInstance().getPluginAsList();
    }

    public ArrayList<Plugin> getPluginsByType(String pluginType) {
        return PluginManager.getInstance().getPluginAsListByType(requireNonBlank(pluginType, "pluginType不能为空"));
    }

    public Map<String, Object> parsePluginParam(String pluginParamJson) {
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

    private Map<String, Object> buildInvokePayload(Plugin plugin, String pluginParamJson) {
        HashMap<String, Object> payload = new HashMap<>();
        payload.put(RESULT_PLUGIN_PARAM, parsePluginParam(pluginParamJson));
        payload.put(RESULT_PLUGIN_BYTECODE, plugin.getBytecode());
        return payload;
    }

    private String requireNonBlank(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }
}
