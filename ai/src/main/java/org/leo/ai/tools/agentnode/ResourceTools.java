package org.leo.ai.tools.agentnode;

import org.leo.ai.agent.AiToolContext;
import org.leo.ai.util.AgentNodeSessionUtils;
import org.leo.core.agent.capability.ResourceCapable;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
@org.leo.ai.agent.AiToolPolicy(kind = org.leo.ai.agent.AiToolKind.QUERY,
        operation = org.leo.ai.agent.AiToolOperation.READ_ONLY,
        parallelizable = true)
public class ResourceTools {

    private static final String[] SPRING_BOOT_RESOURCES = {
            "application.yml", "application.yaml", "application.properties",
            "bootstrap.yml", "bootstrap.yaml", "bootstrap.properties"
    };

    @Tool("批量读取 agent 侧 classpath 资源。resourcePaths 可传一个或多个路径；"
            + "springBootDefaults=true 时读取 Spring Boot 常见 application/bootstrap 配置。不是平台侧 skills 目录工具。")
    public Map<String, Object> readResources(
            @P(value = "可选 classpath 资源路径数组", required = false)
            String[] resourcePaths,
            @P(value = "是否使用 Spring Boot 常见配置路径预设", required = false)
            Boolean springBootDefaults) throws Exception {
        String[] effectivePaths = Boolean.TRUE.equals(springBootDefaults)
                ? SPRING_BOOT_RESOURCES : resourcePaths;
        if (effectivePaths == null || effectivePaths.length == 0) {
            throw new IllegalArgumentException("resourcePaths 不能为空，或将 springBootDefaults 设为 true");
        }
        return readResourceCandidates(effectivePaths);
    }

    private Map<String, Object> getResource(String resourcePath) throws Exception {
        String sessionId = AiToolContext.requireSessionId();
        String cacheKey = "resource:" + resourcePath;
        Object cached = AgentNodeSessionUtils.getAiContextValue(sessionId, cacheKey);
        if (cached instanceof Map<?, ?> cachedMap) {
            return copyStringKeyMap(cachedMap);
        }

        ResourceCapable node = AgentNodeSessionUtils.requireCapability(sessionId, ResourceCapable.class);
        Map<String, Object> results = node.getResource(resourcePath);
        if (results != null) {
            AgentNodeSessionUtils.putAiContextValue(sessionId, cacheKey, results);
        }
        return results;
    }

    @Tool("获取已加载类的字节码并反编译为 Java 源码。用于分析业务逻辑、检查内存马、审计自定义实现。按会话缓存。")
    public Map<String, Object> getClassBytecode(String className) throws Exception {
        String sessionId = AiToolContext.requireSessionId();
        String cacheKey = "class-bytecode:" + className;
        Object cached = AgentNodeSessionUtils.getAiContextValue(sessionId, cacheKey);
        if (cached instanceof Map<?, ?> cachedMap) {
            return copyStringKeyMap(cachedMap);
        }
        ResourceCapable node = AgentNodeSessionUtils.requireCapability(sessionId, ResourceCapable.class);
        Map<String, Object> results = node.getClassBytecode(className);
        if (results != null) {
            AgentNodeSessionUtils.putAiContextValue(sessionId, cacheKey, results);
        }
        return results;
    }

    private Map<String, Object> readResourceCandidates(String[] resourcePaths) throws Exception {
        HashMap<String, Object> result = new HashMap<>();
        List<Map<String, Object>> matches = new ArrayList<>();
        List<String> attempted = new ArrayList<>();

        if (resourcePaths != null) {
            for (String resourcePath : resourcePaths) {
                if (resourcePath == null || resourcePath.isBlank()) {
                    continue;
                }
                String normalizedPath = resourcePath.trim();
                attempted.add(normalizedPath);
                Map<String, Object> resource = getResource(normalizedPath);
                if (looksReadable(resource)) {
                    HashMap<String, Object> item = new HashMap<>();
                    item.put("resourcePath", normalizedPath);
                    item.put("resource", resource);
                    item.put("text", extractUtf8Text(resource));
                    matches.add(item);
                }
            }
        }

        result.put("attempted", attempted);
        result.put("matches", matches);
        result.put("count", matches.size());
        return result;
    }

    private boolean looksReadable(Map<String, Object> resource) {
        if (resource == null || resource.isEmpty()) {
            return false;
        }
        Object code = resource.get("code");
        return Integer.valueOf(200).equals(code) || resource.containsKey("data");
    }

    private String extractUtf8Text(Map<String, Object> resource) {
        if (resource == null) {
            return null;
        }
        Object data = resource.get("data");
        if (data instanceof byte[] bytes) {
            return new String(bytes, StandardCharsets.UTF_8);
        }
        return data == null ? null : String.valueOf(data);
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
