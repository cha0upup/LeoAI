package org.leo.service.plugin;

import org.leo.core.entity.Plugin;
import org.leo.core.manager.PluginManager;
import org.leo.core.puppet.AbstractPuppetNode;
import org.leo.core.puppet.capability.JavaPluginCapable;
import org.leo.core.puppet.capability.PluginCapable;
import org.leo.core.runtime.PuppetRuntime;
import org.leo.core.util.json.JsonUtil;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/** Runtime-neutral entry point for platform-managed plugin execution. */
@Service
public class PluginExecutionService {

    private static final String JAVA_PLUGIN_TYPE = "java";
    private static final String JAVA_PLUGIN_COMPONENT = "PluginComponent";

    public Map<String, Object> invokePlugin(AbstractPuppetNode node, String pluginId,
                                            Object rawParams) throws Exception {
        if (node == null) throw new IllegalArgumentException("puppetNode不能为空");
        Plugin plugin = getRequiredPlugin(pluginId);
        String runtime = plugin.resolveRuntime();
        if (!node.getRuntime().getValue().equalsIgnoreCase(runtime)) {
            throw new IllegalArgumentException("插件运行时与当前节点不匹配: " + runtime);
        }

        Map<String, Object> params = parsePluginParams(rawParams);
        if (node.getRuntime() == PuppetRuntime.PHP) {
            if (!(node instanceof PluginCapable capable)) {
                throw new IllegalArgumentException("当前 PHP 节点缺少 plugin capability");
            }
            byte[] source = requirePayload(plugin, "PHP 插件源码为空: " + pluginId);
            return requireResult(capable.invokePlugin(pluginId,
                    new String(source, StandardCharsets.UTF_8), params));
        }
        if (!(node instanceof JavaPluginCapable capable)) {
            throw new IllegalArgumentException("当前节点缺少 javaPlugin capability");
        }
        return invokeJavaPlugin(capable, plugin, params);
    }

    public Map<String, Object> invokeJavaPlugin(JavaPluginCapable node, String pluginId,
                                                Object rawParams) throws Exception {
        if (node == null) throw new IllegalArgumentException("puppetNode不能为空");
        Plugin plugin = getRequiredPlugin(pluginId);
        if (!"java".equalsIgnoreCase(plugin.resolveRuntime())) {
            throw new IllegalArgumentException("插件运行时与当前节点不匹配: "
                    + plugin.resolveRuntime());
        }
        return invokeJavaPlugin(node, plugin, parsePluginParams(rawParams));
    }

    private Map<String, Object> invokeJavaPlugin(JavaPluginCapable node, Plugin plugin,
                                                 Map<String, Object> params) throws Exception {
        String type = plugin.getPluginType();
        Map<String, Object> result;
        if (type == null || JAVA_PLUGIN_TYPE.equalsIgnoreCase(type)) {
            HashMap<String, Object> payload = new HashMap<>();
            payload.put("pluginParam", params);
            payload.put("pluginBytecode", requirePayload(plugin,
                    "插件字节码为空: " + plugin.getPluginId()));
            result = node.invokeComponent(JAVA_PLUGIN_COMPONENT, payload);
        } else {
            result = node.execScript(type,
                    new String(requirePayload(plugin,
                            "脚本内容为空: " + plugin.getPluginId()), StandardCharsets.UTF_8));
        }
        return requireResult(result);
    }

    public Plugin getRequiredPlugin(String pluginId) {
        String normalized = requireNonBlank(pluginId, "pluginId不能为空");
        Plugin plugin = PluginManager.getInstance().getPluginById(normalized);
        if (plugin == null) throw new IllegalArgumentException("插件不存在: " + normalized);
        return plugin;
    }

    public List<Plugin> getAllPlugins() {
        return PluginManager.getInstance().getPluginAsList();
    }

    public List<Plugin> getPluginsByType(String pluginType) {
        return PluginManager.getInstance().getPluginAsListByType(
                requireNonBlank(pluginType, "pluginType不能为空"));
    }

    public Map<String, Object> parsePluginParams(Object raw) {
        if (raw == null || String.valueOf(raw).isBlank()) return new LinkedHashMap<>();
        if (raw instanceof Map<?, ?> map) {
            LinkedHashMap<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, value) -> result.put(String.valueOf(key), value));
            return result;
        }
        Object parsed = JsonUtil.fromJsonString(String.valueOf(raw), HashMap.class);
        if (!(parsed instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("pluginParam必须是JSON对象");
        }
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
    }

    private byte[] requirePayload(Plugin plugin, String message) {
        byte[] payload = plugin.getBytecode();
        if (payload == null || payload.length == 0) throw new IllegalArgumentException(message);
        return payload;
    }

    private Map<String, Object> requireResult(Map<String, Object> result) {
        if (result == null) throw new IllegalStateException("组件调用返回结果为空");
        return result;
    }

    private String requireNonBlank(String value, String message) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(message);
        return value.trim();
    }
}
