package org.leo.ai.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class AiErrorClassifierTest {

    private final AiErrorClassifier classifier = new AiErrorClassifier();

    @ParameterizedTest
    @ValueSource(strings = {
            "HTTP 503 Service Unavailable", "status code: 502", "500 upstream failure", "HTTP/1.1 504 Gateway Timeout"
    })
    void classifiesServerFailuresAsTransient(String message) {
        assertEquals(AiErrorClassifier.CATEGORY_SERVER_ERROR, classifier.classify(message).category());
    }

    @Test
    void usesHttpStatusWhenResponseBodyDoesNotDescribeTheFailure() {
        var error = new RuntimeException("SDK failed",
                new dev.langchain4j.exception.HttpException(503, "maintenance"));
        assertEquals(AiErrorClassifier.CATEGORY_SERVER_ERROR, classifier.classify(error).category());
    }

    @Test
    void classifiesThinkingModeCompatibilityErrors_openai() {
        // DeepSeek / OpenAI-compatible protocol
        AiErrorClassifier.Classification result = classifier.classify(
                "The `reasoning_content` in the thinking mode must be passed back to the API.");

        assertEquals(AiErrorClassifier.CATEGORY_THINKING_MODE, result.category());
        assertEquals("当前接口不接受 Thinking 工具调用历史，请关闭 Thinking 或切换模型", result.message());
    }

    @ParameterizedTest
    @ValueSource(strings = {"ChatCompletion$Choice.message() is null", "No value present"})
    void classifiesMalformedModelResponses(String message) {
        AiErrorClassifier.Classification result = classifier.classify(message);

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

    @Test
    void classifiesToolCallingProtocolFailuresForChannelSwitchGuidance() {
        AiErrorClassifier.Classification result = classifier.classify(
                "tool_calls format is unsupported by this endpoint");

        assertEquals(AiErrorClassifier.CATEGORY_TOOL_CALLING, result.category());
        assertEquals(
                "当前接口的工具调用格式不可用，请切换到支持 tool calling 的通道",
                result.message());
        assertEquals("switch_tool_channel", result.actions().get(0).code());
    }

    @ParameterizedTest
    @CsvSource(value = {"outer,null,outer", "outer,' ',outer", "'',null,RuntimeException", "null,null,RuntimeException"},
            nullValues = "null")
    void preservesTheDeepestNonBlankMessageAndOuterTypeFallback(String outerMessage, String innerMessage, String expected) {
        var error = new RuntimeException(outerMessage, new IllegalStateException(innerMessage));

        assertEquals(expected, classifier.classify(error).rawMessage());
    }

    @Test
    void cyclicCausesStillProduceAClassification() {
        var first = new RuntimeException("connection reset");
        var second = new IllegalStateException("request timed out");
        first.initCause(second);
        second.initCause(first);

        AiErrorClassifier.Classification result = assertTimeoutPreemptively(Duration.ofSeconds(2),
                () -> classifier.classify(first));

        assertEquals(AiErrorClassifier.CATEGORY_TIMEOUT, result.category());
        assertEquals("request timed out", result.rawMessage());
    }

    @Test
    void missingFailureKeepsTheUnknownErrorContract() {
        AiErrorClassifier.Classification result = classifier.classify((Throwable) null);

        assertEquals(AiErrorClassifier.CATEGORY_UNKNOWN, result.category());
        assertEquals("", result.rawMessage());
        assertEquals("AI 调用失败", result.message());
    }
}
