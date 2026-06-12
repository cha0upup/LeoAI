package org.leo.ai.tools.puppetnode;

import org.leo.ai.agent.AiToolContext;
import org.leo.ai.util.PuppetNodeSessionUtils;
import org.leo.core.puppet.impl.JavaPuppetNode;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class ResourceTools {

    @Tool("读取 puppet 侧 classpath 资源（如 jar 内的 application.yml）。不是平台侧 skills 目录工具。按会话缓存。")
    public Map<String, Object> getResource(String resourcePath) throws Exception {
        String sessionId = AiToolContext.requireSessionId();
        String cacheKey = "resource:" + resourcePath;
        Object cached = PuppetNodeSessionUtils.getAiContextValue(sessionId, cacheKey);
        if (cached instanceof Map<?, ?> cachedMap) {
            return (Map<String, Object>) cachedMap;
        }

        JavaPuppetNode node = PuppetNodeSessionUtils.getJavaPuppetNode(sessionId);
        Map<String, Object> results = node.getResource(resourcePath);
        if (results != null) {
            PuppetNodeSessionUtils.putAiContextValue(sessionId, cacheKey, results);
        }
        return results;
    }

    @Tool("读取 Spring Boot 常见配置资源（application.yml/yaml/properties、bootstrap.yml/yaml/properties）。")
    public Map<String, Object> readSpringBootConfigResources() throws Exception {
        String sessionId = AiToolContext.requireSessionId();
        String cacheKey = "spring-boot-config-resources";
        Object cached = PuppetNodeSessionUtils.getAiContextValue(sessionId, cacheKey);
        if (cached instanceof Map<?, ?> cachedMap) {
            return (Map<String, Object>) cachedMap;
        }

        String[] candidates = new String[]{
                "application.yml",
                "application.yaml",
                "application.properties",
                "bootstrap.yml",
                "bootstrap.yaml",
                "bootstrap.properties"
        };
        Map<String, Object> result = readResourceCandidates(candidates);
        Object count = result.get("count");
        if (count instanceof Integer c && c > 0) {
            PuppetNodeSessionUtils.putAiContextValue(sessionId, cacheKey, result);
        }
        return result;
    }

    @Tool("获取已加载类的字节码并反编译为 Java 源码。用于分析业务逻辑、检查内存马、审计自定义实现。按会话缓存。")
    public Map<String, Object> getClassBytecode(String className) throws Exception {
        String sessionId = AiToolContext.requireSessionId();
        String cacheKey = "class-bytecode:" + className;
        Object cached = PuppetNodeSessionUtils.getAiContextValue(sessionId, cacheKey);
        if (cached instanceof Map<?, ?> cachedMap) {
            return (Map<String, Object>) cachedMap;
        }
        JavaPuppetNode node = PuppetNodeSessionUtils.getJavaPuppetNode(sessionId);
        Map<String, Object> results = node.getClassBytecode(className);
        if (results != null) {
            PuppetNodeSessionUtils.putAiContextValue(sessionId, cacheKey, results);
        }
        return results;
    }

    @Tool("批量尝试读取多个 classpath 资源路径，返回成功读取的结果。")
    public Map<String, Object> readResourceCandidates(String[] resourcePaths) throws Exception {
        String sessionId = AiToolContext.requireSessionId();
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
}
