package org.leo.ai.service;

import dev.langchain4j.exception.AuthenticationException;
import dev.langchain4j.exception.HttpException;
import dev.langchain4j.exception.InternalServerException;
import dev.langchain4j.exception.InvalidRequestException;
import dev.langchain4j.exception.ModelNotFoundException;
import dev.langchain4j.exception.RateLimitException;
import dev.langchain4j.exception.TimeoutException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class AiErrorClassifierTest {

    private final AiErrorClassifier classifier = new AiErrorClassifier();

    @ParameterizedTest
    @CsvSource({
            "HTTP 503 Service Unavailable,server_error",
            "status code: 502,server_error",
            "500 upstream failure,server_error",
            "HTTP/1.1 504 Gateway Timeout,server_error",
            "'sdk response:\nstatus:503\nretry',server_error",
            "sdk request 503,unknown",
            "status:5030,unknown",
            "upstreamstatus:503,unknown"
    })
    void classifiesServerStatusesWithoutConfusingUnrelatedNumbers(String message, String category) {
        assertEquals(category, classifier.classify(message).category());
    }

    @ParameterizedTest
    @CsvSource({"401,auth", "403,auth", "408,timeout", "429,rate_limit", "503,server_error", "504,server_error"})
    void usesHttpStatusWhenResponseBodyDoesNotDescribeTheFailure(int status, String category) {
        var error = new RuntimeException("SDK failed", new HttpException(status, "provider detail"));
        var result = classifier.classify(error);
        assertEquals(category, result.category());
        assertEquals("provider detail", result.rawMessage());
    }

    @ParameterizedTest
    @MethodSource("typedFailures")
    void usesSdkExceptionTypeWhenMessageDoesNotDescribeTheFailure(Throwable error, String category) {
        var result = classifier.classify(new RuntimeException("SDK failed", error));
        assertEquals(category, result.category());
        assertEquals("provider detail", result.rawMessage());
    }

    private static Stream<Arguments> typedFailures() {
        return Stream.of(
                Arguments.of(new AuthenticationException("provider detail"), "auth"),
                Arguments.of(new RateLimitException("provider detail"), "rate_limit"),
                Arguments.of(new TimeoutException("provider detail"), "timeout"),
                Arguments.of(new InternalServerException("provider detail"), "server_error"));
    }

    @Test
    void prefersStructuredFailureOverMisleadingProviderText() {
        var error = new RateLimitException(new HttpException(429, "Service unavailable"));
        assertEquals(AiErrorClassifier.CATEGORY_RATE_LIMIT, classifier.classify(error).category());
    }

    @Test
    void keepsGatewayTimeoutInTheServerErrorCategory() {
        var error = new TimeoutException(new HttpException(504, "time limit"));
        assertEquals(AiErrorClassifier.CATEGORY_SERVER_ERROR, classifier.classify(error).category());
    }

    @Test
    void ambiguousSdkErrorsStillUseProviderDetails() {
        var badThinkingRequest = new InvalidRequestException(
                new HttpException(400, "reasoning_content must be passed back in thinking mode"));
        var unsupportedEndpoint = new ModelNotFoundException(new HttpException(404, "Responses API unsupported"));
        assertEquals(AiErrorClassifier.CATEGORY_THINKING_MODE, classifier.classify(badThinkingRequest).category());
        assertEquals(AiErrorClassifier.CATEGORY_UNSUPPORTED_PARAMETER, classifier.classify(unsupportedEndpoint).category());
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
