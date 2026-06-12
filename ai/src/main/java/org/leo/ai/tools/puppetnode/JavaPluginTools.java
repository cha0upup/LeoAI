package org.leo.ai.tools.puppetnode;

import org.leo.ai.agent.AiToolContext;
import org.leo.ai.util.PuppetNodeSessionUtils;
import org.leo.core.entity.Plugin;
import org.leo.core.puppet.impl.JavaPuppetNode;
import org.leo.service.puppetnode.plugin.JavaPluginService;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Map;

@Component
public class JavaPluginTools {

    private final JavaPluginService javaPluginService;

    public JavaPluginTools(JavaPluginService javaPluginService) {
        this.javaPluginService = javaPluginService;
    }

    @Tool("获取当前平台侧已加载的 Java 插件列表。适用于先查看可调用插件、pluginId、插件类型、参数示例和备注，再决定是否调用插件。这里查看的是平台侧插件元数据，不是 puppet 侧文件或 class。")
    public ArrayList<Plugin> getJavaPlugins() {
        return javaPluginService.getAllPlugins();
    }

    @Tool("根据 pluginId 获取平台侧指定 Java 插件详情。适用于在调用前确认插件名称、插件类型、paramsDemo、版本和备注。")
    public Plugin getJavaPlugin(String pluginId) {
        return javaPluginService.getRequiredPlugin(pluginId);
    }

    @Tool("按 pluginType 获取平台侧 Java 插件列表。适用于插件较多时按类型筛选候选插件。")
    public ArrayList<Plugin> getJavaPluginsByType(String pluginType) {
        return javaPluginService.getPluginsByType(pluginType);
    }

    @Tool("调用指定 Java 插件。插件本身由平台侧加载和管理，但会绑定当前 session 对应的 puppet 目标执行。sessionId 和 pluginId 必填；pluginParamJson 传 JSON 对象字符串，例如 {\"key\":\"value\"}。适用于让 AI 直接复用平台已加载插件能力处理目标会话。")
    public Map<String, Object> invokeJavaPlugin(String pluginId, String pluginParamJson) throws Exception {
        String sessionId = AiToolContext.requireSessionId();
        JavaPuppetNode javaPuppetNode = PuppetNodeSessionUtils.getJavaPuppetNode(sessionId);
        return javaPluginService.invokePlugin(javaPuppetNode, pluginId, pluginParamJson);
    }
}
