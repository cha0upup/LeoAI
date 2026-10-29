package org.leo.ai.testing;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 可复用的确定性 ChatModel，用于将 Agent 行为写成请求/响应脚本。
 */
public final class ScriptedChatModel implements ChatModel {

    private final Deque<Function<ChatRequest, ChatResponse>> steps = new ArrayDeque<>();
    private final List<ChatRequest> requests = new ArrayList<>();

    public ScriptedChatModel then(Function<ChatRequest, ChatResponse> step) {
        steps.addLast(step);
        return this;
    }

    public ScriptedChatModel thenToolCall(String id, String name, String arguments) {
        return then(request -> response(AiMessage.from(
                ToolExecutionRequest.builder()
                        .id(id)
                        .name(name)
                        .arguments(arguments)
                        .build())));
    }

    public ScriptedChatModel thenToolCalls(ToolExecutionRequest... requests) {
        return then(request -> response(new AiMessage(List.of(requests))));
    }

    @Override
    public ChatResponse doChat(ChatRequest request) {
        requests.add(request);
        Function<ChatRequest, ChatResponse> step = steps.pollFirst();
        if (step == null) {
            throw new AssertionError("脚本模型收到未声明的第 " + requests.size() + " 次请求");
        }
        return step.apply(request);
    }

    public List<ChatRequest> requests() {
        return List.copyOf(requests);
    }

    public void assertExhausted() {
        assertTrue(steps.isEmpty(), "仍有 " + steps.size() + " 个模型脚本步骤未执行");
    }

    public static ChatResponse response(AiMessage message) {
        return ChatResponse.builder().aiMessage(message).build();
    }
}
