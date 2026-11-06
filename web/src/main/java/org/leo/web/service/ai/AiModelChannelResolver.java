package org.leo.web.service.ai;

import org.leo.ai.channel.AiModelConfigService;
import org.leo.core.entity.AiModelConfig;
import org.leo.web.exception.ApiException;
import org.springframework.stereotype.Service;

/** Shared validation and lookup boundary for AI model channels. */
@Service
public class AiModelChannelResolver {

    private final AiModelConfigService modelConfigService;

    public AiModelChannelResolver(AiModelConfigService modelConfigService) {
        this.modelConfigService = modelConfigService;
    }

    public AiModelConfig require(Integer configId) {
        try {
            AiModelConfig config = modelConfigService.resolve(configId);
            if (config != null) return config;
            if (configId != null) {
                throw ApiException.notFound("AI 模型不存在或已删除，configId: " + configId);
            }
            throw ApiException.notFound("未配置激活的 AI 模型，请先在设置中添加并激活一条");
        } catch (IllegalArgumentException | IllegalStateException error) {
            throw ApiException.notFound(error.getMessage());
        }
    }

    public AiModelConfig optional(Integer configId) {
        return configId == null ? null : require(configId);
    }
}
