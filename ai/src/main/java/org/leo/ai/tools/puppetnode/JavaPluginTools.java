package org.leo.ai.tools.puppetnode;

import org.leo.ai.agent.AiToolContext;
import org.leo.ai.util.PuppetNodeSessionUtils;
import org.leo.core.entity.Plugin;
import org.leo.core.puppet.AbstractPuppetNode;
import org.leo.service.plugin.PluginExecutionService;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
@org.leo.ai.agent.AiToolPolicy(kind = org.leo.ai.agent.AiToolKind.COMMAND,
        operation = org.leo.ai.agent.AiToolOperation.WRITE)
public class JavaPluginTools {

    private final PluginExecutionService pluginExecutionService;

    public JavaPluginTools(PluginExecutionService pluginExecutionService) {
        this.pluginExecutionService = pluginExecutionService;
    }

    @Tool("列出当前平台侧已加载的插件。pluginType 可选；为空返回全部插件。适用于调用前查看 pluginId、runtime、参数示例和备注。")
    @org.leo.ai.agent.AiToolPolicy(kind = org.leo.ai.agent.AiToolKind.QUERY,
            operation = org.leo.ai.agent.AiToolOperation.READ_ONLY, parallelizable = true)
    public List<Plugin> listJavaPlugins(
            @P(value = "可选 pluginType", required = false) String pluginType) {
        return pluginType == null || pluginType.isBlank()
                ? pluginExecutionService.getAllPlugins()
                : pluginExecutionService.getPluginsByType(pluginType.trim());
    }

    @Tool("根据 pluginId 获取平台侧指定插件详情。适用于在调用前确认插件名称、runtime、插件类型、paramsDemo、版本和备注。")
    @org.leo.ai.agent.AiToolPolicy(kind = org.leo.ai.agent.AiToolKind.QUERY,
            operation = org.leo.ai.agent.AiToolOperation.READ_ONLY, parallelizable = true)
    public Plugin getJavaPlugin(String pluginId) {
        return pluginExecutionService.getRequiredPlugin(pluginId);
    }

    @Tool("调用与当前 puppet runtime 匹配的平台插件。插件由平台侧加载和管理，并绑定当前 session 对应的 puppet 执行。pluginId 必填；pluginParamJson 传 JSON 对象字符串，例如 {\"key\":\"value\"}。支持 Java 与 PHP runtime。")
    public Map<String, Object> invokeJavaPlugin(String pluginId, String pluginParamJson) throws Exception {
        String sessionId = AiToolContext.requireSessionId();
        AbstractPuppetNode node = PuppetNodeSessionUtils.getPuppetNode(sessionId);
        return pluginExecutionService.invokePlugin(node, pluginId, pluginParamJson);
    }
}
