package org.leo.web.controller.platform.admin;

import org.leo.ai.channel.AiModelConfigService;
import org.leo.core.entity.AiProvider;
import org.leo.core.util.ApiResponse;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;

@RestController
@RequestMapping("/platform/admin/ai-providers")
public class AiProviderController {

    private final AiModelConfigService configService;

    public AiProviderController(AiModelConfigService configService) {
        this.configService = configService;
    }

    @RequestMapping(method = RequestMethod.GET)
    public HashMap<String, Object> list() {
        List<HashMap<String, Object>> view = configService.listProviders().stream()
                .map(AiProviderController::toView)
                .toList();
        return ApiResponse.success(view);
    }

    @RequestMapping(method = RequestMethod.POST)
    public HashMap<String, Object> create(@RequestBody AiProvider body) {
        try {
            AiProvider saved = body.getModels() == null || body.getModels().isEmpty()
                    ? configService.createProvider(body)
                    : configService.createProviderWithModels(body);
            return ApiResponse.success(toView(saved));
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        }
    }

    @RequestMapping(value = "/{id}", method = RequestMethod.PUT)
    public HashMap<String, Object> update(@PathVariable("id") Integer id, @RequestBody AiProvider body) {
        try {
            AiProvider saved = configService.updateProvider(id, body);
            if (saved == null) {
                return ApiResponse.notFound("供应商不存在，id: " + id);
            }
            return ApiResponse.success(toView(saved));
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        }
    }

    @RequestMapping(value = "/{id}", method = RequestMethod.DELETE)
    public HashMap<String, Object> delete(@PathVariable("id") Integer id) {
        try {
            if (!configService.deleteProvider(id)) {
                return ApiResponse.notFound("供应商不存在，id: " + id);
            }
            return ApiResponse.success();
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        }
    }

    private static HashMap<String, Object> toView(AiProvider p) {
        HashMap<String, Object> m = new LinkedHashMap<>();
        m.put("id", p.getId());
        m.put("name", p.getName());
        m.put("providerKey", p.getProviderKey());
        m.put("baseUrl", p.getBaseUrl());
        m.put("completionsPath", p.getCompletionsPath());
        m.put("headersJson", p.getHeadersJson());
        m.put("enabled", p.getEnabled());
        m.put("createTime", p.getCreateTime());
        m.put("updateTime", p.getUpdateTime());
        m.put("remark", p.getRemark());
        String key = p.getApiKey();
        m.put("apiKeyConfigured", key != null && !key.isEmpty());
        return m;
    }
}
