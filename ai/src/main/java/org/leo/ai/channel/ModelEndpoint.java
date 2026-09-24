package org.leo.ai.channel;

import com.alibaba.fastjson.JSON;
import org.leo.core.entity.AiModelConfig;

import java.net.URI;
import java.util.Map;
import java.util.TreeMap;

/** Resolves one API root for inference and model discovery. */
public final class ModelEndpoint {
    private ModelEndpoint() {}

    public static String apiRoot(AiModelConfig config) {
        String base = config.getBaseUrl();
        if (base == null || base.isBlank()) throw new IllegalArgumentException("baseUrl 不能为空");
        URI uri;
        try {
            uri = URI.create(base.trim());
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("baseUrl 格式无效");
        }
        if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                || uri.getHost() == null || uri.getUserInfo() != null
                || uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw new IllegalArgumentException("baseUrl 必须是 HTTP(S) 地址，且不能包含凭据、查询参数或片段");
        }
        base = stripSlash(uri.toString());
        String suffix = DynamicModelProvider.defaultPathForProtocol(config.getProtocol());
        if (base.endsWith("/responses") || base.endsWith("/chat/completions")) {
            throw new IllegalArgumentException("baseUrl 应填写 API 根地址，不包含 /responses 或 /chat/completions");
        }
        String path = config.getCompletionsPath();
        path = path == null || path.isBlank() ? suffix : path.trim();
        if (!path.startsWith("/") || !path.endsWith(suffix) || path.contains("?") || path.contains("#")
                || path.contains(":") || path.contains("//") || path.contains("..")) {
            throw new IllegalArgumentException("请求路径必须以 / 开头并以 " + suffix + " 结尾");
        }
        base += path.substring(0, path.length() - suffix.length());
        return base;
    }

    public static String modelsUrl(AiModelConfig config) {
        return apiRoot(config) + "/models";
    }

    public static Map<String, String> headers(String json) {
        if (json == null || json.isBlank()) return Map.of();
        Object parsed;
        try {
            parsed = JSON.parse(json);
        } catch (RuntimeException error) {
            throw new IllegalArgumentException("自定义请求头必须是 JSON 字符串对象");
        }
        if (!(parsed instanceof Map<?, ?> raw)) {
            throw new IllegalArgumentException("自定义请求头必须是 JSON 字符串对象");
        }
        Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        raw.forEach((key, value) -> {
            if (!(key instanceof String name) || !name.matches("[!#$%&'*+.^_`|~0-9a-zA-Z-]+")
                    || !(value instanceof String text) || text.chars().anyMatch(c -> c < 32 || c == 127)) {
                throw new IllegalArgumentException("自定义请求头名称或值无效");
            }
            if (name.equalsIgnoreCase("Host") || name.equalsIgnoreCase("Content-Length")
                    || name.equalsIgnoreCase("Connection") || name.equalsIgnoreCase("Transfer-Encoding")) {
                throw new IllegalArgumentException("不能覆盖传输层请求头: " + name);
            }
            headers.put(name, text);
        });
        return Map.copyOf(headers);
    }

    private static String stripSlash(String value) {
        while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
        return value;
    }
}
