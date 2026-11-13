package org.leo.web.application.ai;

import org.leo.ai.channel.AiModelConfigService;
import org.leo.core.entity.AiModelConfig;
import org.leo.web.application.UseCaseException;
import org.leo.web.application.UseCaseException.Kind;
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
                throw new UseCaseException(Kind.NOT_FOUND, "AI 模型不存在或已删除，configId: " + configId);
            }
            throw new UseCaseException(Kind.NOT_FOUND, "未配置激活的 AI 模型，请先在设置中添加并激活一条");
        } catch (IllegalArgumentException | IllegalStateException error) {
            throw new UseCaseException(Kind.NOT_FOUND, error.getMessage());
        }
    }

    public AiModelConfig optional(Integer configId) {
        return configId == null ? null : require(configId);
    }
}
