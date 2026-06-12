package org.leo.web.controller.platform.admin;

import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.leo.ai.channel.AiModelConfigService;
import org.leo.ai.channel.DynamicModelProvider;
import org.leo.ai.service.AiErrorClassifier;
import org.leo.core.entity.AiModelConfig;
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
                .map(AiModelConfigController::toView)
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
        AiModelConfig saved = configService.activate(id);
        if (saved == null) {
            return ApiResponse.notFound("模型配置不存在，id: " + id);
        }
        return ApiResponse.success(toView(saved));
    }

    /**
     * 简单连接测试：用该配置发一次最短 chat 请求。
     */
    @RequestMapping(value = "/{id}/test-connection", method = RequestMethod.POST)
    public HashMap<String, Object> testConnection(@PathVariable("id") Integer id) {
        AiModelConfig config = configService.findById(id);
        if (config == null) {
            return ApiResponse.notFound("模型配置不存在，id: " + id);
        }
        long start = System.currentTimeMillis();
        try {
            String effectiveBaseUrl = DynamicModelProvider.resolveEffectiveBaseUrl(config);
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
            ChatResponse response = chatModel.chat(ChatRequest.builder()
                    .messages(List.of(new UserMessage("请只回复 OK。")))
                    .build());
            long latency = System.currentTimeMillis() - start;
            String text = response != null && response.aiMessage() != null
                    ? response.aiMessage().text() : null;
            LinkedHashMap<String, Object> result = new LinkedHashMap<>();
            result.put("success", true);
            result.put("latencyMs", latency);
            result.put("model", config.getModel());
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

    private static HashMap<String, Object> toView(AiModelConfig c) {
        HashMap<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.getId());
        m.put("name", c.getName());
        m.put("baseUrl", c.getBaseUrl());
        m.put("model", c.getModel());
        m.put("completionsPath", c.getCompletionsPath());
        m.put("isActive", c.getIsActive());
        m.put("maxOutputTokens", c.getMaxOutputTokens());
        m.put("thinkingEnabled", c.getThinkingEnabled());
        m.put("contextWindowTokens", c.getContextWindowTokens());
        m.put("createTime", c.getCreateTime());
        m.put("updateTime", c.getUpdateTime());
        m.put("remark", c.getRemark());
        String key = c.getApiKey();
        m.put("apiKeyConfigured", key != null && !key.isEmpty());
        return m;
    }
}
