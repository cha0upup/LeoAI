package org.leo.web.controller.platform.admin;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.leo.ai.channel.AiModelConfigService;
import org.leo.ai.channel.DynamicModelProvider;
import org.leo.ai.service.AiErrorClassifier;
import org.leo.core.entity.AiModelCapability;
import org.leo.core.entity.AiModelConfig;
import org.leo.core.entity.AiProvider;
import org.leo.core.entity.ProviderCapabilities;
import org.leo.core.util.ApiResponse;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 模型配置管理（ccswitch 风格的单层条目 CRUD）。
 *
 * <p>每条记录 = name + baseUrl + apiKey + model + 可选 thinking/maxTokens。
 * 任意时刻只允许一条 {@code is_active=1}。激活时热切换底层模型。
 */
@RestController
@RequestMapping("/platform/admin/ai-models")
public class AiModelConfigController {

    private final AiModelConfigService configService;
    private final DynamicModelProvider dynamicModelProvider;
    private final AiErrorClassifier aiErrorClassifier;

    public AiModelConfigController(AiModelConfigService configService,
                                   DynamicModelProvider dynamicModelProvider,
                                   AiErrorClassifier aiErrorClassifier) {
        this.configService = configService;
        this.dynamicModelProvider = dynamicModelProvider;
        this.aiErrorClassifier = aiErrorClassifier;
    }

    @RequestMapping(method = RequestMethod.GET)
    public HashMap<String, Object> list() {
        List<HashMap<String, Object>> view = configService.listAll().stream()
                .map(this::toView)
                .toList();
        return ApiResponse.success(view);
    }

    @RequestMapping(value = "/{id}", method = RequestMethod.GET)
    public HashMap<String, Object> get(@PathVariable("id") Integer id) {
        AiModelConfig row = configService.findById(id);
        if (row == null) {
            return ApiResponse.notFound("模型配置不存在，id: " + id);
        }
        return ApiResponse.success(toView(row));
    }

    @RequestMapping(method = RequestMethod.POST)
    public HashMap<String, Object> create(@RequestBody AiModelConfig body) {
        try {
            AiModelConfig saved = configService.create(body);
            return ApiResponse.success(toView(saved));
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        }
    }

    @RequestMapping(value = "/{id}", method = RequestMethod.PUT)
    public HashMap<String, Object> update(@PathVariable("id") Integer id,
                                          @RequestBody AiModelConfig patch) {
        AiModelConfig existing = configService.findById(id);
        if (existing == null) {
            return ApiResponse.notFound("模型配置不存在，id: " + id);
        }
        try {
            AiModelConfig saved = configService.update(id, patch);
            return ApiResponse.success(toView(saved));
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        }
    }

    @RequestMapping(value = "/{id}", method = RequestMethod.DELETE)
    public HashMap<String, Object> delete(@PathVariable("id") Integer id) {
        AiModelConfig existing = configService.findById(id);
        if (existing == null) {
            return ApiResponse.notFound("模型配置不存在，id: " + id);
        }
        configService.deleteById(id);
        return ApiResponse.success();
    }

    @RequestMapping(value = "/{id}/activate", method = RequestMethod.POST)
    public HashMap<String, Object> activate(@PathVariable("id") Integer id) {
        try {
            AiModelConfig saved = configService.activate(id);
            if (saved == null) {
                return ApiResponse.notFound("模型配置不存在，id: " + id);
            }
            return ApiResponse.success(toView(saved));
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        }
    }

    /**
     * 简单连接测试：按通道协议发一次最短请求。
     */
    @RequestMapping(value = "/{id}/test-connection", method = RequestMethod.POST)
    public HashMap<String, Object> testConnection(@PathVariable("id") Integer id) {
        AiModelConfig config = configService.findById(id);
        if (config == null) {
            return ApiResponse.notFound("模型配置不存在，id: " + id);
        }
        long start = System.currentTimeMillis();
        String protocol = DynamicModelProvider.resolveProtocol(config);
        String effectiveBaseUrl = DynamicModelProvider.resolveEffectiveBaseUrl(config);
        try {
            ChatResponse response = testConnectionWithRuntime(config);
            long latency = System.currentTimeMillis() - start;
            String text = response != null && response.aiMessage() != null
                    ? response.aiMessage().text() : null;
            LinkedHashMap<String, Object> result = new LinkedHashMap<>();
            result.put("success", true);
            result.put("latencyMs", latency);
            result.put("model", config.getModel());
            result.put("providerKey", config.getProviderKey());
            result.put("protocol", protocol);
            result.put("effectiveBaseUrl", effectiveBaseUrl);
            result.put("responsePreview", text != null && text.length() > 100
                    ? text.substring(0, 100) + "..." : text);
            return ApiResponse.success(result);
        } catch (Exception e) {
            long latency = System.currentTimeMillis() - start;
            AiErrorClassifier.Classification classification = aiErrorClassifier.classify(e);
            LinkedHashMap<String, Object> result = new LinkedHashMap<>();
            result.put("success", false);
            result.put("latencyMs", latency);
            result.put("model", config.getModel());
            result.put("providerKey", config.getProviderKey());
            result.put("protocol", protocol);
            result.put("effectiveBaseUrl", effectiveBaseUrl);
            result.put("category", classification.category());
            result.put("message", classification.message());
            return ApiResponse.success(result);
        }
    }

    private ChatResponse testConnectionWithRuntime(AiModelConfig config) {
        return dynamicModelProvider.buildRuntime(config).chatModel().chat(ChatRequest.builder()
                .messages(List.of(new UserMessage("请只回复 OK。")))
                .build());
    }

    /**
     * 返回内置服务商预设列表，前端用于快速填充 baseUrl 和推荐模型。
     */
    @RequestMapping(value = "/providers", method = RequestMethod.GET)
    public HashMap<String, Object> providers() {
        List<Map<String, Object>> list = new ArrayList<>();
        list.add(provider("OpenAI (Responses API)", "openai", "https://api.openai.com/v1",
                DynamicModelProvider.PROTOCOL_RESPONSES,
                Arrays.asList("gpt-5.5", "gpt-5.4")));
        list.add(provider("DeepSeek", "deepseek", "https://api.deepseek.com",
                DynamicModelProvider.PROTOCOL_CHAT_COMPLETIONS,
                Arrays.asList("deepseek-v4-pro", "deepseek-v4-flash")));
        list.add(provider("通义千问 (Qwen)", "qwen", "https://dashscope.aliyuncs.com/compatible-mode",
                DynamicModelProvider.PROTOCOL_CHAT_COMPLETIONS,
                Arrays.asList("qwen3-max", "qwen3-coder")));
        list.add(provider("智谱 (GLM)", "zhipu", "https://open.bigmodel.cn/api/paas",
                DynamicModelProvider.PROTOCOL_CHAT_COMPLETIONS,
                Arrays.asList("glm-5.2", "glm-5.1")));
        list.add(provider("Gemini (OpenAI compat)", "gemini", "https://generativelanguage.googleapis.com/v1beta/openai",
                DynamicModelProvider.PROTOCOL_CHAT_COMPLETIONS,
                Arrays.asList("gemini-2.5-pro", "gemini-2.5-flash")));
        list.add(provider("小米 MiMo", "mimo", "https://api.mimo.ai/v1",
                DynamicModelProvider.PROTOCOL_CHAT_COMPLETIONS,
                Arrays.asList("mimo-v2.5-pro", "mimo-v2.5-flash")));
        list.add(provider("OpenRouter", "openrouter", "https://openrouter.ai/api",
                DynamicModelProvider.PROTOCOL_CHAT_COMPLETIONS,
                Arrays.asList("openai/gpt-5.5", "deepseek/deepseek-v4-pro", "zhipu/glm-5.2")));
        list.add(provider("Ollama (本地)", "ollama", "http://localhost:11434",
                DynamicModelProvider.PROTOCOL_CHAT_COMPLETIONS,
                Arrays.asList("qwen3", "deepseek-v4-flash")));
        list.add(provider("自定义", "custom", "", DynamicModelProvider.PROTOCOL_CHAT_COMPLETIONS, new ArrayList<>()));
        return ApiResponse.success(list);
    }

    @RequestMapping(value = "/capabilities", method = RequestMethod.GET)
    public HashMap<String, Object> capabilities() {
        List<HashMap<String, Object>> view = configService.listModelCapabilities().stream()
                .map(AiModelConfigController::capabilityToView)
                .toList();
        return ApiResponse.success(view);
    }

    @RequestMapping(value = "/capabilities", method = RequestMethod.POST)
    public HashMap<String, Object> createCapability(@RequestBody AiModelCapability body) {
        try {
            return ApiResponse.success(capabilityToView(configService.createCapability(body)));
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        }
    }

    @RequestMapping(value = "/capabilities/{modelName}", method = RequestMethod.PUT)
    public HashMap<String, Object> updateCapability(@PathVariable("modelName") String modelName,
                                                   @RequestBody AiModelCapability body) {
        try {
            AiModelCapability saved = configService.updateCapability(modelName, body);
            if (saved == null) {
                return ApiResponse.notFound("模型能力不存在: " + modelName);
            }
            return ApiResponse.success(capabilityToView(saved));
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        }
    }

    @RequestMapping(value = "/capabilities/{modelName}", method = RequestMethod.DELETE)
    public HashMap<String, Object> deleteCapability(@PathVariable("modelName") String modelName) {
        if (!configService.deleteCapability(modelName)) {
            return ApiResponse.notFound("模型能力不存在: " + modelName);
        }
        return ApiResponse.success();
    }

    private static Map<String, Object> provider(String label, String key, String baseUrl,
                                                String protocol, List<String> models) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("label", label);
        m.put("key", key);
        m.put("baseUrl", baseUrl);
        m.put("protocol", protocol);
        m.put("completionsPath", DynamicModelProvider.defaultPathForProtocol(protocol));
        m.put("popularModels", models);
        return m;
    }

    /**
     * 代理调用目标服务商的 GET /v1/models 接口，返回模型 id 列表。
     * 避免前端直接跨域请求第三方 API。
     */
    @RequestMapping(value = "/fetch-models", method = RequestMethod.POST)
    public HashMap<String, Object> fetchModels(@RequestBody Map<String, String> body) {
        String baseUrl = body.getOrDefault("baseUrl", "").trim();
        String apiKey  = body.getOrDefault("apiKey", "").trim();
        return fetchModelIds(baseUrl, apiKey);
    }

    /**
     * 使用已保存供应商凭据拉取模型列表，避免 API Key 回传前端。
     */
    @RequestMapping(value = "/providers/{providerId}/fetch-models", method = RequestMethod.POST)
    public HashMap<String, Object> fetchProviderModels(@PathVariable("providerId") Integer providerId) {
        AiProvider provider = configService.findProviderById(providerId);
        if (provider == null) {
            return ApiResponse.notFound("供应商不存在，id: " + providerId);
        }
        if (provider.getBaseUrl() == null || provider.getBaseUrl().isBlank()
                || provider.getApiKey() == null || provider.getApiKey().isBlank()) {
            return ApiResponse.badRequest("供应商 Base URL 或 API Key 未配置");
        }
        return fetchModelIds(provider.getBaseUrl(), provider.getApiKey());
    }

    private HashMap<String, Object> fetchModelIds(String baseUrl, String apiKey) {
        baseUrl = baseUrl == null ? "" : baseUrl.trim();
        apiKey = apiKey == null ? "" : apiKey.trim();
        if (baseUrl.isEmpty() || apiKey.isEmpty()) {
            return ApiResponse.badRequest("baseUrl 和 apiKey 不能为空");
        }
        String modelsPath = inferModelsPath(baseUrl);
        String url = baseUrl.replaceAll("/+$", "") + modelsPath;
        try {
            HttpURLConnection conn = (HttpURLConnection)
                    URI.create(url).toURL().openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Authorization", "Bearer " + apiKey);
            conn.setRequestProperty("Accept", "application/json");
            conn.setConnectTimeout(10_000);
            conn.setReadTimeout(15_000);
            int status = conn.getResponseCode();
            if (status != 200) {
                return ApiResponse.badRequest("服务商返回 HTTP " + status + "，请检查 baseUrl / apiKey");
            }
            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
            }
            String json = sb.toString();
            List<String> ids = parseModelIds(json);
            ids.sort(String::compareToIgnoreCase);
            return ApiResponse.success(ids);
        } catch (Exception e) {
            return ApiResponse.badRequest("请求失败: " + e.getMessage());
        }
    }

    private static String inferModelsPath(String baseUrl) {
        String normalized = baseUrl == null ? "" : baseUrl.replaceAll("/+$", "");
        if (normalized.endsWith("/v1")) {
            return "/models";
        }
        return "/v1/models";
    }

    private static List<String> parseModelIds(String json) {
        List<String> ids = new ArrayList<>();
        Object parsed = JSON.parse(json);
        if (parsed instanceof JSONObject object) {
            collectModelIds(object.get("data"), ids);
            collectModelIds(object.get("models"), ids);
        } else {
            collectModelIds(parsed, ids);
        }
        return ids;
    }

    private static void collectModelIds(Object value, List<String> ids) {
        if (value instanceof JSONArray array) {
            for (Object item : array) {
                collectModelIds(item, ids);
            }
        } else if (value instanceof JSONObject object) {
            String id = object.getString("id");
            if (id == null || id.isBlank()) id = object.getString("name");
            if (id == null || id.isBlank()) id = object.getString("model");
            if (id != null && !id.isBlank()) ids.add(id.trim());
        } else if (value instanceof String s && !s.isBlank()) {
            ids.add(s.trim());
        }
    }

    private HashMap<String, Object> toView(AiModelConfig c) {
        HashMap<String, Object> m = new LinkedHashMap<>();
        ProviderCapabilities capabilities = configService.capabilitiesForModel(c);
        m.put("id", c.getId());
        m.put("providerId", c.getProviderId());
        m.put("name", c.getName());
        m.put("providerKey", c.getProviderKey());
        m.put("providerName", c.getProviderName());
        m.put("baseUrl", c.getBaseUrl());
        m.put("model", c.getModel());
        m.put("protocol", DynamicModelProvider.resolveProtocol(c));
        m.put("completionsPath", c.getCompletionsPath());
        m.put("isActive", c.getIsActive());
        m.put("isDefault", c.getIsActive());
        m.put("enabled", c.getEnabled());
        m.put("maxOutputTokens", c.getMaxOutputTokens());
        m.put("thinkingEnabled", c.getThinkingEnabled());
        m.put("reasoningEffort", c.getReasoningEffort());
        m.put("contextWindowTokens", c.getContextWindowTokens());
        m.put("temperature", c.getTemperature());
        m.put("headersJson", c.getHeadersJson());
        m.put("capabilityStatus", capabilities.status());
        m.put("capabilityRecognized", capabilities.recognized());
        m.put("capabilitySource", capabilities.source());
        m.put("capabilityModelName", configService.capabilityModelName(c));
        m.put("capabilityContextWindowTokens", capabilities.contextWindowTokens());
        m.put("capabilityMaxOutputTokens", capabilities.maxOutputTokens());
        m.put("effectiveContextWindowTokens", clampPositive(c.getContextWindowTokens(), capabilities.contextWindowTokens()));
        m.put("effectiveMaxOutputTokens", clampPositive(c.getMaxOutputTokens(), capabilities.maxOutputTokens()));
        m.put("supportsTextGeneration", capabilities.supportsTextGeneration());
        m.put("supportsReasoning", capabilities.supportsReasoning());
        m.put("supportsStreaming", capabilities.supportsStreaming());
        m.put("supportsFunctionCalling", capabilities.supportsFunctionCalling());
        m.put("supportsStructuredOutput", capabilities.supportsStructuredOutput());
        m.put("supportsWebSearch", capabilities.supportsWebSearch());
        m.put("supportsParallelToolCalls", capabilities.supportsParallelToolCalls());
        m.put("defaultMaxOutputTokens", capabilities.defaultMaxOutputTokens());
        m.put("createTime", c.getCreateTime());
        m.put("updateTime", c.getUpdateTime());
        m.put("remark", c.getRemark());
        String key = c.getApiKey();
        m.put("apiKeyConfigured", key != null && !key.isEmpty());
        return m;
    }

    private static HashMap<String, Object> capabilityToView(AiModelCapability c) {
        HashMap<String, Object> m = new LinkedHashMap<>();
        m.put("modelName", c.getModelName());
        m.put("source", c.getSource());
        m.put("contextWindowTokens", c.getContextWindowTokens());
        m.put("maxOutputTokens", c.getMaxOutputTokens());
        m.put("supportsTextGeneration", c.getSupportsTextGeneration());
        m.put("supportsReasoning", c.getSupportsReasoning());
        m.put("supportsStreaming", c.getSupportsStreaming());
        m.put("supportsFunctionCalling", c.getSupportsFunctionCalling());
        m.put("supportsStructuredOutput", c.getSupportsStructuredOutput());
        m.put("supportsWebSearch", c.getSupportsWebSearch());
        m.put("supportsParallelToolCalls", c.getSupportsParallelToolCalls());
        m.put("createTime", c.getCreateTime());
        m.put("updateTime", c.getUpdateTime());
        m.put("remark", c.getRemark());
        return m;
    }

    private static int clampPositive(Integer configured, int limit) {
        return configured != null && configured > 0 ? Math.min(configured, limit) : limit;
    }
}
