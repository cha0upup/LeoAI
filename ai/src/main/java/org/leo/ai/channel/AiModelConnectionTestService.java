package org.leo.ai.channel;

import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.leo.ai.service.AiErrorClassifier;
import org.leo.core.entity.AiModelConfig;
import org.springframework.stereotype.Service;

/** 用一次最小文本请求验证已保存的模型连接，不写入能力目录。 */
@Service
public class AiModelConnectionTestService {

    private static final int PREVIEW_LIMIT = 100;

    private final AiModelConfigService configService;
    private final DynamicModelProvider dynamicModelProvider;
    private final AiErrorClassifier errorClassifier;
    private final AiModelFailoverService failoverService;

    public AiModelConnectionTestService(AiModelConfigService configService,
                                        DynamicModelProvider dynamicModelProvider,
                                        AiErrorClassifier errorClassifier,
                                        AiModelFailoverService failoverService) {
        this.configService = configService;
        this.dynamicModelProvider = dynamicModelProvider;
        this.errorClassifier = errorClassifier;
        this.failoverService = failoverService;
    }

    /** 不存在的模型不发请求，也不更新健康状态。 */
    public Result testConnection(Integer id) {
        AiModelConfig config = configService.findById(id);
        if (config == null) return null;

        long start = System.currentTimeMillis();
        String protocol = DynamicModelProvider.resolveProtocol(config);
        String effectiveBaseUrl = ModelEndpoint.apiRoot(config);
        try {
            ChatResponse response = dynamicModelProvider.buildProbeRuntime(config, false).chatModel()
                    .chat(ChatRequest.builder().messages(new UserMessage("请只回复 OK。")).build());
            failoverService.recordSuccess(config.getId());
            long latency = System.currentTimeMillis() - start;
            String text = response != null && response.aiMessage() != null ? response.aiMessage().text() : null;
            String preview = text != null && text.length() > PREVIEW_LIMIT
                    ? text.substring(0, PREVIEW_LIMIT) + "..." : text;
            return new Result(latency, config.getModel(), config.getProviderKey(), protocol,
                    effectiveBaseUrl, preview, null);
        } catch (Exception error) {
            long latency = System.currentTimeMillis() - start;
            var failure = errorClassifier.classify(error);
            failoverService.recordFailure(config.getId(), failure);
            return new Result(latency, config.getModel(), config.getProviderKey(), protocol,
                    effectiveBaseUrl, null, new Failure(failure.category(), failure.message()));
        }
    }

    public record Result(long latencyMs, String model, String providerKey, String protocol, String effectiveBaseUrl,
                         String responsePreview, Failure failure) {
        public boolean success() { return failure == null; }
    }

    public record Failure(String category, String message) {
    }
}
