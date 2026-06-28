package org.leo.web.controller.platform.admin;

import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.http.client.jdk.JdkHttpClientBuilder;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiResponsesChatModel;
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
    private final AiErrorClassifier aiErrorClassifier;

    public AiModelConfigController(AiModelConfigService configService,
                                   AiErrorClassifier aiErrorClassifier) {
        this.configService = configService;
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
        try {
            boolean responsesApi = DynamicModelProvider.useResponsesApi(config);
            ChatResponse response = responsesApi
                    ? testResponsesConnection(config)
                    : testChatCompletionsConnection(config);
            long latency = System.currentTimeMillis() - start;
            String text = response != null && response.aiMessage() != null
                    ? response.aiMessage().text() : null;
            LinkedHashMap<String, Object> result = new LinkedHashMap<>();
            result.put("success", true);
            result.put("latencyMs", latency);
            result.put("model", config.getModel());
            result.put("protocol", responsesApi ? "responses" : "chat_completions");
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
            result.put("category", classification.category());
            result.put("message", classification.message());
            return ApiResponse.success(result);
        }
    }

    private ChatResponse testResponsesConnection(AiModelConfig config) {
        String effectiveBaseUrl = DynamicModelProvider.resolveResponsesBaseUrl(config);
        var chatBuilder = OpenAiResponsesChatModel.builder()
                .httpClientBuilder(new JdkHttpClientBuilder()
                        .connectTimeout(java.time.Duration.ofSeconds(10))
                        .readTimeout(java.time.Duration.ofSeconds(30)))
                .apiKey(config.getApiKey())
                .baseUrl(effectiveBaseUrl)
                .modelName(config.getModel())
                .parallelToolCalls(true)
                .store(false)
                .strictTools(false);
        if (config.getMaxOutputTokens() != null && config.getMaxOutputTokens() > 0) {
            chatBuilder.maxOutputTokens(config.getMaxOutputTokens());
        }
        OpenAiResponsesChatModel chatModel = chatBuilder.build();
        return chatModel.chat(ChatRequest.builder()
                .messages(List.of(new UserMessage("请只回复 OK。")))
                .build());
    }

    private ChatResponse testChatCompletionsConnection(AiModelConfig config) {
        String effectiveBaseUrl = DynamicModelProvider.resolveChatCompletionsBaseUrl(config);
        var chatBuilder = OpenAiChatModel.builder()
                .apiKey(config.getApiKey())
                .baseUrl(effectiveBaseUrl)
                .modelName(config.getModel())
                .parallelToolCalls(true)
                .timeout(java.time.Duration.ofSeconds(30));
        if (config.getMaxOutputTokens() != null && config.getMaxOutputTokens() > 0) {
            chatBuilder.maxTokens(config.getMaxOutputTokens());
        }
        OpenAiChatModel chatModel = chatBuilder.build();
        return chatModel.chat(ChatRequest.builder()
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
                Arrays.asList("gpt-4.1", "gpt-4.1-mini", "gpt-4o", "o3", "o4-mini")));
        list.add(provider("DeepSeek", "deepseek", "https://api.deepseek.com",
                Arrays.asList("deepseek-chat", "deepseek-reasoner", "deepseek-v4-flash")));
        list.add(provider("通义千问 (Qwen)", "qwen", "https://dashscope.aliyuncs.com/compatible-mode",
                Arrays.asList("qwen-max", "qwen-plus", "qwen-turbo", "qwq-plus", "qwen3-235b-a22b")));
        list.add(provider("Moonshot (Kimi)", "moonshot", "https://api.moonshot.cn",
                Arrays.asList("moonshot-v1-8k", "moonshot-v1-32k", "moonshot-v1-128k")));
        list.add(provider("智谱 (GLM)", "zhipu", "https://open.bigmodel.cn/api/paas",
                Arrays.asList("glm-4", "glm-4-air", "glm-4-flash", "glm-z1-flash")));
        list.add(provider("Gemini (OpenAI compat)", "gemini", "https://generativelanguage.googleapis.com/v1beta/openai",
                Arrays.asList("gemini-2.5-pro", "gemini-2.5-flash", "gemini-2.0-flash")));
        list.add(provider("OpenRouter", "openrouter", "https://openrouter.ai/api",
                Arrays.asList("openai/gpt-4o", "anthropic/claude-opus-4-5", "deepseek/deepseek-r1")));
        list.add(provider("Ollama (本地)", "ollama", "http://localhost:11434",
                Arrays.asList("llama3.3", "qwen3", "deepseek-r1")));
        list.add(provider("自定义", "custom", "", new ArrayList<>()));
        return ApiResponse.success(list);
    }

    @RequestMapping(value = "/capabilities", method = RequestMethod.GET)
    public HashMap<String, Object> capabilities() {
        List<HashMap<String, Object>> view = configService.listModelCapabilities().stream()
                .map(AiModelConfigController::capabilityToView)
                .toList();
        return ApiResponse.success(view);
    }

    private static Map<String, Object> provider(String label, String key, String baseUrl, List<String> models) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("label", label);
        m.put("key", key);
        m.put("baseUrl", baseUrl);
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
            // 简单解析 {"data":[{"id":"..."},...]}, 不引入额外 JSON 库
            String json = sb.toString();
            List<String> ids = new ArrayList<>();
            int dataIdx = json.indexOf("\"data\"");
            if (dataIdx >= 0) {
                int arrStart = json.indexOf('[', dataIdx);
                int arrEnd   = json.lastIndexOf(']');
                if (arrStart >= 0 && arrEnd > arrStart) {
                    String arr = json.substring(arrStart, arrEnd + 1);
                    int pos = 0;
                    while ((pos = arr.indexOf("\"id\"", pos)) >= 0) {
                        int colon = arr.indexOf(':', pos);
                        int q1 = arr.indexOf('"', colon + 1);
                        int q2 = arr.indexOf('"', q1 + 1);
                        if (q1 >= 0 && q2 > q1) {
                            ids.add(arr.substring(q1 + 1, q2));
                        }
                        pos = colon + 1;
                    }
                }
            }
            // 按名称排序便于查找
            ids.sort(String::compareToIgnoreCase);
            return ApiResponse.success(ids);
        } catch (Exception e) {
            return ApiResponse.badRequest("请求失败: " + e.getMessage());
        }
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
        int dataIdx = json.indexOf("\"data\"");
        if (dataIdx >= 0) {
            int arrStart = json.indexOf('[', dataIdx);
            int arrEnd   = json.lastIndexOf(']');
            if (arrStart >= 0 && arrEnd > arrStart) {
                String arr = json.substring(arrStart, arrEnd + 1);
                int pos = 0;
                while ((pos = arr.indexOf("\"id\"", pos)) >= 0) {
                    int colon = arr.indexOf(':', pos);
                    int q1 = arr.indexOf('"', colon + 1);
                    int q2 = arr.indexOf('"', q1 + 1);
                    if (q1 >= 0 && q2 > q1) {
                        ids.add(arr.substring(q1 + 1, q2));
                    }
                    pos = colon + 1;
                }
            }
        }
        return ids;
    }

    private HashMap<String, Object> toView(AiModelConfig c) {
        HashMap<String, Object> m = new LinkedHashMap<>();
        ProviderCapabilities capabilities = configService.capabilitiesForModel(c.getModel());
        m.put("id", c.getId());
        m.put("providerId", c.getProviderId());
        m.put("name", c.getName());
        m.put("providerKey", c.getProviderKey());
        m.put("providerName", c.getProviderName());
        m.put("baseUrl", c.getBaseUrl());
        m.put("model", c.getModel());
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
        m.put("providerKey", c.getProviderKey());
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
