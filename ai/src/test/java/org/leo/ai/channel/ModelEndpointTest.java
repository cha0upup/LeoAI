package org.leo.ai.channel;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.leo.core.entity.AiModelConfig;

import static org.junit.jupiter.api.Assertions.*;

class ModelEndpointTest {
    @ParameterizedTest
    @CsvSource({
            "https://api.xiaomimimo.com/v1,/chat/completions,chat_completions,https://api.xiaomimimo.com/v1",
            "https://api.deepseek.com,/chat/completions,chat_completions,https://api.deepseek.com",
            "https://dashscope.aliyuncs.com/compatible-mode/v1,/chat/completions,chat_completions,https://dashscope.aliyuncs.com/compatible-mode/v1",
            "https://open.bigmodel.cn/api/paas/v4,/chat/completions,chat_completions,https://open.bigmodel.cn/api/paas/v4",
            "https://generativelanguage.googleapis.com/v1beta/openai,/chat/completions,chat_completions,https://generativelanguage.googleapis.com/v1beta/openai",
            "http://localhost:11434/v1,/chat/completions,chat_completions,http://localhost:11434/v1",
            "https://gateway.test/proxy/,/v2/responses,responses,https://gateway.test/proxy/v2",
            "https://api.openai.com/v1,/responses,responses,https://api.openai.com/v1"
    })
    void resolvesOneRootForInferenceAndDiscovery(String base, String path, String protocol, String root) {
        AiModelConfig config = config(base, path, protocol);
        assertEquals(root, ModelEndpoint.apiRoot(config));
        assertEquals(root + "/models", ModelEndpoint.modelsUrl(config));
    }

    @ParameterizedTest
    @ValueSource(strings = {"file:///tmp/model", "https://user:secret@example.test/v1", "https://example.test/v1?key=secret",
            "https://example.test/v1/responses", "https://example.test/v1/chat/completions"})
    void rejectsAmbiguousOrNonHttpEndpoints(String base) {
        assertThrows(IllegalArgumentException.class, () -> ModelEndpoint.apiRoot(config(base, "/responses", "responses")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"[]", "{bad}", "{\"X-Key\":12}", "{\"X-Key\":\"a\\r\\nInjected: x\"}", "{\"Host\":\"other.test\"}"})
    void rejectsInvalidHeadersInsteadOfSilentlyIgnoringThem(String headers) {
        assertThrows(IllegalArgumentException.class, () -> ModelEndpoint.headers(headers));
    }

    @ParameterizedTest
    @ValueSource(strings = {"response", "compatible", "openai_compatible", "chat-completions", "unknown"})
    void rejectsUnsupportedProtocolsInsteadOfGuessing(String protocol) {
        assertThrows(IllegalArgumentException.class,
                () -> ModelEndpoint.apiRoot(config("https://example.test/v1", null, protocol)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"responses", "/chat/completions", "/../responses", "/responses?key=value"})
    void rejectsInvalidOrMismatchedPaths(String path) {
        assertThrows(IllegalArgumentException.class,
                () -> ModelEndpoint.apiRoot(config("https://example.test/v1", path, "responses")));
    }

    private static AiModelConfig config(String base, String path, String protocol) {
        AiModelConfig config = new AiModelConfig();
        config.setBaseUrl(base);
        config.setCompletionsPath(path);
        config.setProtocol(protocol);
        return config;
    }
}
