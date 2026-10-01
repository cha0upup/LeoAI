package org.leo.ai.tools.agentnode;

import org.leo.ai.agent.AiToolContext;
import org.leo.ai.util.AgentNodeSessionUtils;
import org.leo.core.agent.capability.CredentialHarvestCapable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.P;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 凭据采集工具（精简版）
 * <p>
 * 对外仅暴露 1 个 @Tool 方法：
 * <ul>
 *   <li>{@link #harvestAll} — 一键采集 JVM 运行时全部凭据（DataSource/SystemProps/EnvVars/JNDI/SpringEnv）</li>
 * </ul>
 * <p>
 * 返回结果按来源分类，AI 从中按需提取即可。结果缓存，重复调用直接返回。
 */
@Component
@org.leo.ai.agent.AiToolPolicy(kind = org.leo.ai.agent.AiToolKind.QUERY,
        operation = org.leo.ai.agent.AiToolOperation.READ_ONLY,
        parallelizable = true)
public class CredentialHarvestTools {

    private static final Logger log = LoggerFactory.getLogger(CredentialHarvestTools.class);

    private static final String CACHE_KEY_ALL = "credential-harvest:all";

    @Tool("一键采集 agent 侧 JVM 运行时的所有凭据信息。返回按来源分类：\n"
            + "• dataSources — Spring DataSource Bean（JDBC URL/用户名/密码，支持 HikariCP/Druid/DBCP/Tomcat JDBC）\n"
            + "• systemProperties — System Properties 中含敏感关键字的条目\n"
            + "• envVars — 环境变量中含敏感关键字的条目\n"
            + "• jndiDataSources — JNDI 绑定的 DataSource（java:comp/env/jdbc 等）\n"
            + "• springEnv — Spring Environment PropertySource 中的敏感配置（含占位符解析后的实际值）\n"
            + "可选传入 filter 关键字进一步过滤。结果会缓存，重复调用直接返回。")
    public Map<String, Object> harvestAll(
            @P(value = "过滤关键字，用于缩小搜索范围", required = false) String filter) throws Exception {
        String sessionId = AiToolContext.requireSessionId();
        CredentialHarvestCapable node = AgentNodeSessionUtils.requireCapability(sessionId, CredentialHarvestCapable.class);
        String cacheKey = CACHE_KEY_ALL + (filter != null && !filter.isBlank() ? ":" + filter : "");
        Object cached = AgentNodeSessionUtils.getAiContextValue(sessionId, cacheKey);
        if (cached instanceof Map<?, ?> cachedMap) {
            return copyStringKeyMap(cachedMap);
        }

        Map<String, Object> results = node.harvestCredentials(filter);

        if (results != null && isSuccess(results)) {
            AgentNodeSessionUtils.putAiContextValue(sessionId, cacheKey, results);
        }
        return results;
    }

    private boolean isSuccess(Map<String, Object> results) {
        if (results == null) return false;
        Object code = results.get("code");
        return Integer.valueOf(200).equals(code);
    }

    private Map<String, Object> copyStringKeyMap(Map<?, ?> source) {
        Map<String, Object> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            if (key instanceof String stringKey) {
                copy.put(stringKey, value);
            }
        });
        return copy;
    }
}
