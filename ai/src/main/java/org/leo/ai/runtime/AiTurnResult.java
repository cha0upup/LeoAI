package org.leo.ai.runtime;

import dev.langchain4j.model.chat.response.ChatResponse;

/** 统一执行引擎产生的成功结果。 */
public record AiTurnResult(String output,
                           ChatResponse response,
                           boolean userInputRequested,
                           boolean streamRecovered) {

    public AiTurnResult {
        output = output != null ? output : "";
    }
}
