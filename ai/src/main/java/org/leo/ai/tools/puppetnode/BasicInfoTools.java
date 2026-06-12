package org.leo.ai.tools.puppetnode;

import org.leo.ai.agent.AiToolContext;
import org.leo.ai.util.PuppetNodeSessionUtils;
import org.leo.core.puppet.impl.JavaPuppetNode;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class BasicInfoTools {
    private static final String BASIC_INFO_CACHE_KEY = "basic-info";

    @Tool("获取目标机器的基础信息。适用于初始侦察、识别操作系统、当前用户、主机环境和部署线索。")
    @SuppressWarnings("unchecked")
    public Map<String, Object> getBasicInfo() throws Exception {
        String sessionId = AiToolContext.requireSessionId();
        Object cached = PuppetNodeSessionUtils.getAiContextValue(sessionId, BASIC_INFO_CACHE_KEY);
        if (cached instanceof Map<?, ?> cachedMap) {
            return (Map<String, Object>) cachedMap;
        }

        JavaPuppetNode node = PuppetNodeSessionUtils.getJavaPuppetNode(sessionId);
        Map<String, Object> results = node.getBasicInfo();
        if (results != null) {
            PuppetNodeSessionUtils.putAiContextValue(sessionId, BASIC_INFO_CACHE_KEY, results);
        }
        return results;
    }
}
