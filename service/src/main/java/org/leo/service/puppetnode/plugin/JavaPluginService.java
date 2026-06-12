package org.leo.service.puppetnode.plugin;

import org.leo.core.entity.Plugin;
import org.leo.core.manager.PluginManager;
import org.leo.core.puppet.impl.JavaPuppetNode;
import org.leo.core.util.json.JsonUtil;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

@Service
public class JavaPluginService {

    private static final String COMPONENT_PLUGIN = "PluginComponent";
    private static final String RESULT_PLUGIN_PARAM = "pluginParam";
    private static final String RESULT_PLUGIN_BYTECODE = "pluginBytecode";

    public Map<String, Object> invokePlugin(JavaPuppetNode javaPuppetNode, String pluginId, String pluginParamJson) throws Exception {
        if (javaPuppetNode == null) {
            throw new IllegalArgumentException("puppetNode不能为空");
        }
        Plugin plugin = getRequiredPlugin(pluginId);
        Map<String, Object> payload = buildInvokePayload(plugin, pluginParamJson);
        Map<String, Object> results = javaPuppetNode.invokeComponent(COMPONENT_PLUGIN, payload);
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
