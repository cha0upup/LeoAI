package org.leo.web.controller.platform.admin;

import org.leo.ai.channel.AiModelConfigService;
import org.leo.core.entity.AiProvider;
import org.leo.core.entity.AiProviderSummary;
import org.leo.core.util.ApiResponse;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;

@RestController
@RequestMapping("/platform/admin/ai-providers")
public class AiProviderController {

    private final AiModelConfigService configService;

    public AiProviderController(AiModelConfigService configService) {
        this.configService = configService;
    }

    @RequestMapping(method = RequestMethod.GET)
    public HashMap<String, Object> list() {
        return ApiResponse.success(configService.listProviderSummaries());
    }

    @RequestMapping(method = RequestMethod.POST)
    public HashMap<String, Object> create(@RequestBody AiProvider body) {
        try {
            return ApiResponse.success(toView(configService.createProvider(body)));
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

    private static AiProviderSummary toView(AiProvider p) {
        String headers = p.getHeadersJson();
        String key = p.getApiKey();
        return new AiProviderSummary(p.getId(), p.getName(), p.getProviderKey(), p.getBaseUrl(), p.getProtocol(),
                p.getCompletionsPath(), p.getEnabled(), p.getCreateTime(), p.getUpdateTime(), p.getRemark(),
                headers != null && !headers.isBlank(), key != null && !key.isEmpty());
    }
}
