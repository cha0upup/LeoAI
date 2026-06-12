package org.leo.ai.channel;

import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 代理 StreamingChatModel：将所有调用委托给内部 volatile 引用。
 *
 * <p>当 Provider 配置变更时，通过 {@link #setDelegate(StreamingChatModel)} 热切换底层模型，
 * 已构建的 AiServices Agent 无需重建。
 */
public class DelegatingStreamingChatModel implements StreamingChatModel {

    private static final Logger log = LoggerFactory.getLogger(DelegatingStreamingChatModel.class);

    private volatile StreamingChatModel delegate;

    public DelegatingStreamingChatModel(StreamingChatModel initial) {
        this.delegate = initial;
    }

    public void setDelegate(StreamingChatModel newDelegate) {
        if (newDelegate == null) {
            throw new IllegalArgumentException("delegate 不能为 null");
        }
        this.delegate = newDelegate;
        log.info("StreamingChatModel 已热切换: {}", newDelegate.getClass().getSimpleName());
    }

    public StreamingChatModel getDelegate() {
        return delegate;
    }

    @Override
    public void chat(ChatRequest chatRequest, StreamingChatResponseHandler handler) {
        StreamingChatModel current = delegate;
        if (current == null) {
            throw new IllegalStateException("AI 模型未初始化，请先配置并激活 Provider");
        }
        current.chat(chatRequest, handler);
    }
}
