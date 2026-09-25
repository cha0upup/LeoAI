package org.leo.ai.agent;

import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.memory.chat.TokenWindowChatMemory;
import dev.langchain4j.model.TokenCountEstimator;
import org.leo.ai.config.AiAgentProperties;
import org.leo.ai.memory.ManagedConversationMemory;
import org.springframework.stereotype.Component;

/**
 * 按实际模型上下文窗口创建对话记忆，避免线程选模后仍沿用全局默认模型预算。
 *
 * <p>从模型窗口中预留系统提示、工具定义和输出空间，并受系统配置上限约束。
 * 小窗口使用 {@link TokenWindowChatMemory}；大窗口使用 {@link MessageWindowChatMemory}
 * 配合 {@link CompressingChatMemory}，避免字符估算误差随上下文增大而累积。
 */
@Component
public class AiChatMemoryProviderFactory {

    static final int MIN_RESERVED_CONTEXT_TOKENS = 20_000;
    static final int BASE_RUNTIME_TOKENS = 12_000;
    static final int MIN_CONTEXT_TOKENS = 1_024;

    private final TokenCountEstimator tokenEstimator;
    private final ContextCompressionService compressionService;
    private final AiAgentProperties agentProperties;
    private final ManagedConversationMemory managedMemory;

    public AiChatMemoryProviderFactory(TokenCountEstimator tokenEstimator,
                                       ContextCompressionService compressionService,
                                       AiAgentProperties agentProperties,
                                       ManagedConversationMemory managedMemory) {
        this.tokenEstimator = tokenEstimator;
        this.compressionService = compressionService;
        this.agentProperties = agentProperties;
        this.managedMemory = managedMemory;
    }

    public ChatMemoryProvider createPuppetProvider(int modelContextWindowTokens,
                                                   int toolSchemaTokens) {
        return create(modelContextWindowTokens,
                toolSchemaTokens,
                agentProperties.getPuppetNode().getMain().getMaxContextTokens());
    }

    public ChatMemoryProvider createPlatformProvider(int modelContextWindowTokens,
                                                     int toolSchemaTokens) {
        return create(modelContextWindowTokens,
                toolSchemaTokens,
                agentProperties.getPlatform().getMain().getMaxContextTokens());
    }

    private ChatMemoryProvider create(int modelContextWindowTokens,
                                      int toolSchemaTokens,
                                      int configuredMaxTokens) {
        int effectiveWindow = effectiveContextWindowTokens(
                modelContextWindowTokens, configuredMaxTokens, toolSchemaTokens);
        return memoryId -> {
            if (effectiveWindow <= 96_000) {
                ChatMemory delegate = managedMemory.initialize(TokenWindowChatMemory.builder()
                        .id(memoryId)
                        .maxTokens(effectiveWindow, tokenEstimator)
                        .build());
                return new ActiveUserPreservingChatMemory(delegate);
            }
            int maxMessages = Math.max(50, effectiveWindow / 2_000);
            var delegate = managedMemory.initialize(MessageWindowChatMemory.builder()
                    .id(memoryId)
                    .maxMessages(maxMessages)
                    .build());
            return new ActiveUserPreservingChatMemory(
                    new CompressingChatMemory(
                            memoryId,
                            delegate,
                            compressionService,
                            effectiveWindow));
        };
    }

    /**
     * 模型窗口是硬上限，系统配置也是上限；两者不能通过 Math.max 被意外放大。
     */
    static int effectiveContextWindowTokens(int modelContextWindowTokens,
                                            int configuredMaxTokens,
                                            int toolSchemaTokens) {
        int modelWindow = modelContextWindowTokens > 0 ? modelContextWindowTokens : 32_768;
        int desiredReserve = Math.max(MIN_RESERVED_CONTEXT_TOKENS,
                BASE_RUNTIME_TOKENS + Math.max(0, toolSchemaTokens));
        int reserve = Math.min(desiredReserve,
                Math.max(0, modelWindow - MIN_CONTEXT_TOKENS));
        int available = Math.max(MIN_CONTEXT_TOKENS, modelWindow - reserve);
        return configuredMaxTokens > 0 ? Math.min(available, configuredMaxTokens) : available;
    }
}
