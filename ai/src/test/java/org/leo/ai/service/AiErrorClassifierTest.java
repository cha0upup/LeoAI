package org.leo.ai.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AiErrorClassifierTest {

    private final AiErrorClassifier classifier = new AiErrorClassifier();

    @Test
    void classifiesThinkingModeCompatibilityErrors_openai() {
        // DeepSeek / OpenAI-compatible protocol
        AiErrorClassifier.Classification result = classifier.classify(
                "The `reasoning_content` in the thinking mode must be passed back to the API.");

        assertEquals(AiErrorClassifier.CATEGORY_THINKING_MODE, result.category());
        assertEquals("当前接口不接受 Thinking 工具调用历史，请关闭 Thinking 或切换模型", result.message());
    }

    @Test
    void classifiesMalformedModelResponses() {
        AiErrorClassifier.Classification result = classifier.classify(
                "ChatCompletion$Choice.message() is null");

        assertEquals(AiErrorClassifier.CATEGORY_MALFORMED_RESPONSE, result.category());
        assertEquals("模型返回了不完整的响应，请重试，或调整当前模型/推理配置", result.message());
    }

    @Test
    void exposesStructuredMetadata() {
        AiErrorClassifier.Classification result = classifier.classify("HTTP 401 Unauthorized");

        assertEquals(AiErrorClassifier.CATEGORY_AUTH, result.toMap().get("category"));
        assertEquals("认证失败，请检查 API Key 或网关鉴权配置", result.toMap().get("message"));
        assertEquals("HTTP 401 Unauthorized", result.toMap().get("rawMessage"));
        assertEquals(2, result.actions().size());
    }
}
