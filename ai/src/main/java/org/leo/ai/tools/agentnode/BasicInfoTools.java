package org.leo.ai.tools.agentnode;

import org.leo.ai.agent.AiToolContext;
import org.leo.ai.util.AgentNodeSessionUtils;
import org.leo.core.agent.capability.BasicInfoCapable;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@org.leo.ai.agent.AiToolPolicy(kind = org.leo.ai.agent.AiToolKind.QUERY,
        operation = org.leo.ai.agent.AiToolOperation.READ_ONLY,
        parallelizable = true)
public class BasicInfoTools {
    private static final String BASIC_INFO_CACHE_KEY = "basic-info";

    @Tool("获取目标机器的基础信息。适用于初始侦察、识别操作系统、当前用户、主机环境和部署线索。")
    @SuppressWarnings("unchecked")
    public Map<String, Object> getBasicInfo() throws Exception {
        String sessionId = AiToolContext.requireSessionId();
        Object cached = AgentNodeSessionUtils.getAiContextValue(sessionId, BASIC_INFO_CACHE_KEY);
        if (cached instanceof Map<?, ?> cachedMap) {
            return (Map<String, Object>) cachedMap;
        }

        BasicInfoCapable node = AgentNodeSessionUtils.requireCapability(sessionId, BasicInfoCapable.class);
        Map<String, Object> results = node.getBasicInfo();
        if (results != null) {
            AgentNodeSessionUtils.putAiContextValue(sessionId, BASIC_INFO_CACHE_KEY, results);
        }
        return results;
    }
}
