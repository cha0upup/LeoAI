package org.leo.ai.channel;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 代理 ChatModel：将所有调用委托给内部 volatile 引用。
 *
 * <p>用于辅助服务（摘要生成、情报提取等一次性调用）。
 * 当 Provider 配置变更时，通过 {@link #setDelegate(ChatModel)} 热切换底层模型。
 */
public class DelegatingChatModel implements ChatModel {

    private static final Logger log = LoggerFactory.getLogger(DelegatingChatModel.class);

    private volatile ChatModel delegate;

    public DelegatingChatModel(ChatModel initial) {
        this.delegate = initial;
    }

    public void setDelegate(ChatModel newDelegate) {
        if (newDelegate == null) {
            throw new IllegalArgumentException("delegate 不能为 null");
        }
        this.delegate = newDelegate;
        log.info("ChatModel 已热切换: {}", newDelegate.getClass().getSimpleName());
    }

    public ChatModel getDelegate() {
        return delegate;
    }

    @Override
    public ChatResponse chat(ChatRequest chatRequest) {
        ChatModel current = delegate;
        if (current == null) {
            throw new IllegalStateException("AI 模型未初始化，请先配置并激活 Provider");
        }
        return current.chat(chatRequest);
    }
}
