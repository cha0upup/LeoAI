package org.leo.ai.channel;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.leo.core.entity.AiModelConfig;
import org.leo.core.entity.AiProvider;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Uses the configured API root and credentials for model discovery, including private gateways. */
@Service
public class AiModelDiscoveryService {
    private static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;

    public List<String> fetch(AiProvider provider) {
        AiModelConfig endpoint = new AiModelConfig();
        endpoint.setProviderKey(provider.getProviderKey());
        endpoint.setBaseUrl(provider.getBaseUrl());
        endpoint.setProtocol(provider.getProtocol());
        endpoint.setCompletionsPath(provider.getCompletionsPath());
        String url = ModelEndpoint.modelsUrl(endpoint);
        var headers = ModelEndpoint.headers(provider.getHeadersJson());
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
            connection.setRequestMethod("GET");
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(10_000);
            connection.setReadTimeout(15_000);
            if (provider.getApiKey() != null && !provider.getApiKey().isBlank()) {
                connection.setRequestProperty("Authorization", "Bearer " + provider.getApiKey().trim());
            }
            connection.setRequestProperty("Accept", "application/json");
            for (var entry : headers.entrySet()) connection.setRequestProperty(entry.getKey(), entry.getValue());
            int status = connection.getResponseCode();
            if (status != 200) throw new IllegalArgumentException("服务商返回 HTTP " + status + "，请检查地址和认证配置");
            try (InputStream stream = connection.getInputStream()) {
                byte[] response = stream.readNBytes(MAX_RESPONSE_BYTES + 1);
                if (response.length > MAX_RESPONSE_BYTES) throw new IllegalArgumentException("模型列表响应超过 2 MiB");
                return parse(new String(response, StandardCharsets.UTF_8));
            }
        } catch (IllegalArgumentException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalArgumentException("获取模型列表失败，请检查供应商地址、认证配置和网络连接");
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static List<String> parse(String json) {
        Object parsed;
        try {
            parsed = JSON.parse(json);
        } catch (RuntimeException error) {
            throw new IllegalArgumentException("服务商未返回有效的模型列表 JSON");
        }
        if (!(parsed instanceof JSONObject object) || !(object.get("data") instanceof JSONArray data)) {
            throw new IllegalArgumentException("模型列表必须包含 data 数组");
        }
        List<String> ids = new ArrayList<>();
        for (Object item : data) {
            if (!(item instanceof JSONObject model) || !(model.get("id") instanceof String id) || id.isBlank()) {
                throw new IllegalArgumentException("模型列表项必须包含非空字符串 id");
            }
            ids.add(id.trim());
        }
        return ids.stream().distinct().sorted(String::compareToIgnoreCase).toList();
    }
}
