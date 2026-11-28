package org.leo.web.controller.platform.admin;

import org.leo.ai.channel.AiModelCapabilityProbeService;
import org.leo.ai.channel.AiModelConfigService;
import org.leo.ai.channel.AiModelConnectionTestService;
import org.leo.ai.channel.AiModelDiscoveryService;
import org.leo.ai.channel.AiModelFailoverService;
import org.leo.ai.channel.DynamicModelProvider;
import org.leo.ai.channel.ModelRequestOptions;
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

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * AI 供应商模型配置管理。
 *
 * <p>模型从所属供应商继承连接配置，任意时刻只允许一条 {@code is_active=1}。
 */
@RestController
@RequestMapping("/platform/admin/ai-models")
public class AiModelConfigController {

    private final AiModelConfigService configService;
    private final AiModelConnectionTestService connectionTestService;
    private final AiModelFailoverService failoverService;
    private final AiModelCapabilityProbeService capabilityProbeService;
    private final AiModelDiscoveryService discoveryService;

    public AiModelConfigController(AiModelConfigService configService,
                                   AiModelConnectionTestService connectionTestService,
                                   AiModelFailoverService failoverService,
                                   AiModelCapabilityProbeService capabilityProbeService,
                                   AiModelDiscoveryService discoveryService) {
        this.configService = configService;
        this.connectionTestService = connectionTestService;
        this.failoverService = failoverService;
        this.capabilityProbeService = capabilityProbeService;
        this.discoveryService = discoveryService;
    }

    @RequestMapping(method = RequestMethod.GET)
    public HashMap<String, Object> list() {
        return ApiResponse.success(configService.listCatalog().stream()
                .map(AiModelConfigController::toView)
                .toList());
    }

    @RequestMapping(method = RequestMethod.POST)
    public HashMap<String, Object> create(@RequestBody AiModelConfig body) {
        try {
            return ApiResponse.success(toView(configService.create(body)));
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        }
    }

    /** Local draft evaluation: no vendor requests, writes, credentials or request headers in the response. */
    @RequestMapping(value = "/request-preview", method = RequestMethod.POST)
    public HashMap<String, Object> requestPreview(@RequestBody AiModelConfig body) {
        try {
            return ApiResponse.success(configService.previewRequest(body));
        } catch (IllegalArgumentException error) {
            return ApiResponse.badRequest(error.getMessage());
        }
    }

    @RequestMapping(value = "/{id}", method = RequestMethod.PUT)
    public HashMap<String, Object> update(@PathVariable("id") Integer id,
                                          @RequestBody AiModelConfig patch) {
        try {
            AiModelConfigService.CatalogEntry saved = configService.update(id, patch);
            if (saved == null) {
                return ApiResponse.notFound("模型配置不存在，id: " + id);
            }
            return ApiResponse.success(toView(saved));
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        }
    }

    @RequestMapping(value = "/{id}", method = RequestMethod.DELETE)
    public HashMap<String, Object> delete(@PathVariable("id") Integer id) {
        if (!configService.deleteById(id)) {
            return ApiResponse.notFound("模型配置不存在，id: " + id);
        }
        return ApiResponse.success();
    }

    @RequestMapping(value = "/{id}/activate", method = RequestMethod.POST)
    public HashMap<String, Object> activate(@PathVariable("id") Integer id) {
        try {
            AiModelConfigService.CatalogEntry saved = configService.activate(id);
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
        var tested = connectionTestService.testConnection(id);
        if (tested == null) {
            return ApiResponse.notFound("模型配置不存在，id: " + id);
        }
        var result = new LinkedHashMap<String, Object>();
        result.put("success", tested.success());
        result.put("latencyMs", tested.latencyMs());
        result.put("model", tested.model());
        result.put("providerKey", tested.providerKey());
        result.put("protocol", tested.protocol());
        result.put("effectiveBaseUrl", tested.effectiveBaseUrl());
        if (tested.success()) {
            result.put("responsePreview", tested.responsePreview());
        } else {
            result.put("category", tested.failure().category());
            result.put("message", tested.failure().message());
        }
        return ApiResponse.success(result);
    }

    /**
     * 使用当前模型实际发起最小、无副作用的能力探针，并把有明确证据的结果写入能力库。
     * 探测包含文本、流式、虚拟工具调用、JSON 输出和 reasoning；不会执行任何工具。
     */
    @RequestMapping(value = "/{id}/probe-capabilities", method = RequestMethod.POST)
    public HashMap<String, Object> probeCapabilities(@PathVariable("id") Integer id) {
        AiModelConfig config = configService.findById(id);
        if (config == null) {
            return ApiResponse.notFound("模型配置不存在，id: " + id);
        }
        try {
            return ApiResponse.success(capabilityProbeService.probe(config).toMap());
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        }
    }

    /** 按模型 ID 返回进程内的健康与熔断状态，无需读取连接配置。 */
    @RequestMapping(value = "/health", method = RequestMethod.GET)
    public HashMap<String, Object> health() {
        return ApiResponse.success(configService.listModelIds().stream()
                .map(failoverService::snapshot)
                .map(AiModelFailoverService.HealthSnapshot::toMap)
                .toList());
    }

    /** 管理员修复配置后，可手动清除临时健康状态；成功的连接测试也会自动清除。 */
    @RequestMapping(value = "/{id}/health/reset", method = RequestMethod.POST)
    public HashMap<String, Object> resetHealth(@PathVariable("id") Integer id) {
        if (!configService.modelExists(id)) {
            return ApiResponse.notFound("模型配置不存在，id: " + id);
        }
        failoverService.reset(id);
        return ApiResponse.success(failoverService.snapshot(id).toMap());
    }

    /**
     * 返回内置服务商预设列表，前端用于快速填充 baseUrl 和推荐模型。
     */
    @RequestMapping(value = "/providers", method = RequestMethod.GET)
    public HashMap<String, Object> providers() {
        List<String> catalog = configService.listCapabilityModelNames();
        String chat = DynamicModelProvider.PROTOCOL_CHAT_COMPLETIONS;
        return ApiResponse.success(List.of(
                provider("OpenAI (Responses API)", "openai", "https://api.openai.com/v1",
                        DynamicModelProvider.PROTOCOL_RESPONSES, models(catalog, "gpt-")),
                provider("DeepSeek", "deepseek", "https://api.deepseek.com", chat, models(catalog, "deepseek-")),
                provider("Kimi (Moonshot)", "kimi", "https://api.moonshot.cn/v1", chat, models(catalog, "kimi-")),
                provider("通义千问 (Qwen)", "qwen", "https://dashscope.aliyuncs.com/compatible-mode/v1",
                        chat, models(catalog, "qwen")),
                provider("智谱 (GLM)", "zhipu", "https://open.bigmodel.cn/api/paas/v4", chat, models(catalog, "glm-")),
                provider("Gemini (OpenAI compat)", "gemini", "https://generativelanguage.googleapis.com/v1beta/openai",
                        chat, models(catalog, "gemini-")),
                provider("小米 MiMo", "mimo", "https://api.xiaomimimo.com/v1", chat, models(catalog, "mimo-")),
                provider("OpenRouter", "openrouter", "https://openrouter.ai/api/v1", chat, List.of()),
                provider("Ollama (本地)", "ollama", "http://localhost:11434/v1", chat, List.of()),
                provider("自定义", "custom", "", chat, List.of())));
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

    private static List<String> models(List<String> catalog, String prefix) {
        return catalog.stream().filter(name -> name.startsWith(prefix)).toList();
    }

    private static ProviderPreset provider(String label, String key, String baseUrl,
                                           String protocol, List<String> models) {
        return new ProviderPreset(label, key, baseUrl, protocol,
                DynamicModelProvider.defaultPathForProtocol(protocol), models);
    }

    private record ProviderPreset(String label, String key, String baseUrl, String protocol,
                                  String completionsPath, List<String> popularModels) {
    }

    /**
     * 代理调用目标服务商的 GET /v1/models 接口，返回模型 id 列表。
     * 避免前端直接跨域请求第三方 API。
     */
    @RequestMapping(value = "/fetch-models", method = RequestMethod.POST)
    public HashMap<String, Object> fetchModels(@RequestBody FetchModelsRequest body) {
        AiProvider provider = new AiProvider();
        provider.setBaseUrl(body.baseUrl());
        provider.setApiKey(body.apiKey());
        provider.setProviderKey(body.providerKey());
        provider.setProtocol(body.protocol());
        provider.setCompletionsPath(body.completionsPath());
        provider.setHeadersJson(body.headersJson());
        return fetchModelIds(provider);
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
        return fetchModelIds(provider);
    }

    private HashMap<String, Object> fetchModelIds(AiProvider provider) {
        try {
            return ApiResponse.success(discoveryService.fetch(provider));
        } catch (IllegalArgumentException error) {
            return ApiResponse.badRequest(error.getMessage());
        }
    }

    private static HashMap<String, Object> toView(AiModelConfigService.CatalogEntry entry) {
        AiModelConfig c = entry.config();
        ProviderCapabilities capabilities = entry.capabilities();
        HashMap<String, Object> m = new LinkedHashMap<>();
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
        m.put("enabled", c.getEnabled());
        m.put("fallbackModelId", c.getFallbackModelId());
        m.put("fallbackModelName", entry.fallbackModelName());
        m.put("maxOutputTokens", c.getMaxOutputTokens());
        m.put("thinkingEnabled", c.getThinkingEnabled());
        m.put("reasoningEffort", c.getReasoningEffort());
        m.put("contextWindowTokens", c.getContextWindowTokens());
        m.put("temperature", c.getTemperature());
        String headers = c.getHeadersJson();
        m.put("headersConfigured", headers != null && !headers.isBlank());
        m.put("capabilityRecognized", capabilities.recognized());
        m.put("capabilitySource", capabilities.source());
        m.put("capabilityModelName", entry.capabilityModelName());
        m.put("capabilityContextWindowTokens", capabilities.contextWindowTokens());
        m.put("capabilityMaxOutputTokens", capabilities.maxOutputTokens());
        m.put("effectiveContextWindowTokens", ModelRequestOptions.effectiveInputWindow(c, capabilities));
        m.put("effectiveMaxOutputTokens", clampPositive(c.getMaxOutputTokens(), capabilities.maxOutputTokens()));
        m.put("supportsTextGeneration", capabilities.supportsTextGeneration());
        m.put("supportsReasoning", capabilities.supportsReasoning());
        m.put("supportsStreaming", capabilities.supportsStreaming());
        m.put("supportsFunctionCalling", capabilities.supportsFunctionCalling());
        m.put("supportsStructuredOutput", capabilities.supportsStructuredOutput());
        m.put("supportsWebSearch", capabilities.supportsWebSearch());
        m.put("supportsParallelToolCalls", capabilities.supportsParallelToolCalls());
        m.put("requestOptions", ModelRequestOptions.forConfiguration(c, capabilities));
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
        m.put("requestOptions", ModelRequestOptions.forModel(null, c.getModelName()));
        m.put("createTime", c.getCreateTime());
        m.put("updateTime", c.getUpdateTime());
        m.put("remark", c.getRemark());
        return m;
    }

    private static int clampPositive(Integer configured, int limit) {
        return configured != null && configured > 0 ? Math.min(configured, limit) : limit;
    }

    public record FetchModelsRequest(String baseUrl, String apiKey, String providerKey, String protocol,
                                     String completionsPath, String headersJson) {
    }
}
