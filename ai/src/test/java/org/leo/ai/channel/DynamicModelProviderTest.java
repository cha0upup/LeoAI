package org.leo.ai.channel;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.sun.net.httpserver.HttpServer;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.leo.core.entity.AiModelConfig;
import org.leo.core.entity.ProviderCapabilities;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DynamicModelProviderTest {
    @ParameterizedTest
    @CsvSource({
            "deepseek-flash,,max,thinking,enabled,max_tokens,true",
            "deepseek-flash,0,,thinking,disabled,max_tokens,false",
            "glm-5.3,,low,thinking,enabled,max_tokens,true",
            "mimo-v2.5,,,thinking,enabled,max_completion_tokens,true",
            "mimo-v2.5-pro,0,,thinking,disabled,max_completion_tokens,false",
            "qwen3.8-max,,,enable_thinking,true,max_tokens,true",
            "qwen3.8-max,0,,enable_thinking,false,max_tokens,false"
    })
    void currentModelsUseDocumentedParametersAndPreserveReasoning(String model, Integer thinking, String effort,
            String parameter, String expectedValue, String outputParameter, boolean reasoning) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<JSONObject> body = new AtomicReference<>();
        server.createContext("/v1/chat/completions", exchange -> {
            body.set(JSON.parseObject(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            byte[] bytes = ("{\"id\":\"test\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\","
                    + "\"content\":\"OK\"},\"finish_reason\":\"stop\"}]}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            AiModelConfig config = config("http://127.0.0.1:" + server.getAddress().getPort() + "/v1", "chat_completions");
            config.setModel(model);
            config.setThinkingEnabled(thinking);
            config.setReasoningEffort(effort);
            config.setMaxOutputTokens(256);
            AiModelConfigService configs = mock(AiModelConfigService.class);
            when(configs.capabilitiesForModel(config)).thenReturn(reasoningCaps());
            var runtime = provider(configs).buildRuntime(config);
            assertEquals(reasoning, runtime.doReasoning());
            runtime.chatModel().chat(ChatRequest.builder().messages(
                    UserMessage.from("First"), AiMessage.builder().text("Previous").thinking("previous reasoning").build(),
                    UserMessage.from("Next")).build());
            JSONObject sent = body.get();
            assertEquals(expectedValue, parameter.equals("thinking")
                    ? sent.getJSONObject(parameter).getString("type") : sent.getString(parameter));
            assertEquals(256, sent.getIntValue(outputParameter));
            assertFalse(sent.containsKey(outputParameter.equals("max_tokens") ? "max_completion_tokens" : "max_tokens"));
            assertEquals(effort, sent.getString("reasoning_effort"));
            assertEquals(reasoning ? "previous reasoning" : null,
                    sent.getJSONArray("messages").getJSONObject(1).getString("reasoning_content"));
        } finally {
            server.stop(0);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"glm-5.3", "glm-5.3-flash", "gemini-3.8-flash"})
    void mandatoryThinkingModelsRejectDisableAndProbeAtLowEffort(String model) {
        AiModelConfig config = config("https://example.test/v1", "chat_completions");
        config.setModel(model);
        config.setThinkingEnabled(0);
        AiModelConfigService configs = mock(AiModelConfigService.class);
        when(configs.capabilitiesForModel(config)).thenReturn(reasoningCaps());
        DynamicModelProvider provider = provider(configs);
        assertThrows(IllegalArgumentException.class, () -> provider.buildRuntime(config));
        var probe = provider.buildProbeRuntime(config, false);
        assertTrue(probe.doReasoning());
        assertEquals("low", probe.reasoningEffort());
        assertEquals(1024, probe.maxTokens());
    }

    private static ProviderCapabilities reasoningCaps() {
        return new ProviderCapabilities(true, "recognized", "system", 1_000_000, 128_000,
                true, true, true, true, true, false, true);
    }

    @Test
    void contextChangesInvalidateRuntimeAndSnapshotUsesTheSameBudget() {
        AiModelConfigService configs = mock(AiModelConfigService.class);
        AiModelConfig config = config("https://example.test/v1", "chat_completions");
        DynamicModelProvider provider = provider(configs);
        when(configs.capabilitiesForModel(config)).thenReturn(caps(65_536, true));
        String first = provider.plannedRuntimeCacheKey(config);
        when(configs.capabilitiesForModel(config)).thenReturn(caps(8_192, true));
        String second = provider.plannedRuntimeCacheKey(config);
        assertNotEquals(first, second);
        var runtime = provider.buildRuntime(config);
        assertEquals(8_192, runtime.contextWindowTokens());
        assertEquals(second, DynamicModelProvider.runtimeCacheKey(config, runtime));
    }

    @ParameterizedTest
    @ValueSource(strings = {"responses", "chat_completions"})
    void probeIgnoresNegativeCapabilitiesAndSendsHeadersWithBoundedOutput(String protocol) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<String> path = new AtomicReference<>();
        AtomicReference<String> auth = new AtomicReference<>();
        AtomicReference<String> header = new AtomicReference<>();
        AtomicReference<JSONObject> body = new AtomicReference<>();
        server.createContext("/", exchange -> {
            path.set(exchange.getRequestURI().getPath());
            auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            header.set(exchange.getRequestHeaders().getFirst("X-Gateway-Key"));
            body.set(JSON.parseObject(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            String json = protocol.equals("responses")
                    ? "{\"id\":\"resp_test\",\"object\":\"response\",\"model\":\"test-model\",\"status\":\"completed\",\"output\":[{\"type\":\"message\",\"role\":\"assistant\",\"content\":[{\"type\":\"output_text\",\"text\":\"OK\"}]}]}"
                    : "{\"id\":\"test\",\"model\":\"test-model\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"OK\"},\"finish_reason\":\"stop\"}]}";
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            AiModelConfigService configs = mock(AiModelConfigService.class);
            AiModelConfig config = config("http://127.0.0.1:" + server.getAddress().getPort() + "/v1", protocol);
            config.setHeadersJson("{\"X-Gateway-Key\":\"gateway-test\",\"authorization\":\"Bearer override-test\"}");
            config.setMaxOutputTokens(100_000);
            when(configs.capabilitiesForModel(config)).thenReturn(caps(8192, false));
            DynamicModelProvider provider = provider(configs);
            assertThrows(IllegalArgumentException.class, () -> provider.buildRuntime(config));
            var runtime = provider.buildProbeRuntime(config, false);
            assertEquals("OK", runtime.chatModel().chat(ChatRequest.builder().messages(UserMessage.from("OK")).build()).aiMessage().text());
            assertEquals("/v1/" + (protocol.equals("responses") ? "responses" : "chat/completions"), path.get());
            assertEquals("gateway-test", header.get());
            assertEquals("Bearer override-test", auth.get());
            assertEquals(256, body.get().getIntValue(protocol.equals("responses") ? "max_output_tokens" : "max_tokens"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void responsesStreamingAlsoCarriesCustomHeaders() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<String> header = new AtomicReference<>();
        server.createContext("/v1/responses", exchange -> {
            header.set(exchange.getRequestHeaders().getFirst("X-Gateway-Key"));
            exchange.getRequestBody().readAllBytes();
            String sse = "event: response.output_text.delta\ndata: {\"type\":\"response.output_text.delta\",\"delta\":\"OK\"}\n\n"
                    + "event: response.completed\ndata: {\"type\":\"response.completed\",\"response\":{\"id\":\"resp_test\",\"model\":\"test-model\",\"status\":\"completed\",\"output\":[{\"type\":\"message\",\"role\":\"assistant\",\"content\":[{\"type\":\"output_text\",\"text\":\"OK\"}]}]}}\n\n";
            byte[] bytes = sse.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            AiModelConfig config = config("http://127.0.0.1:" + server.getAddress().getPort() + "/v1", "responses");
            config.setHeadersJson("{\"X-Gateway-Key\":\"stream-test\"}");
            AiModelConfigService configs = mock(AiModelConfigService.class);
            when(configs.capabilitiesForModel(config)).thenReturn(caps(8192, true));
            CompletableFuture<ChatResponse> result = new CompletableFuture<>();
            provider(configs).buildProbeRuntime(config, false).streamingModel().chat(
                    ChatRequest.builder().messages(UserMessage.from("OK")).build(), new StreamingChatResponseHandler() {
                        public void onCompleteResponse(ChatResponse response) { result.complete(response); }
                        public void onError(Throwable error) { result.completeExceptionally(error); }
                    });
            assertEquals("OK", result.get(5, TimeUnit.SECONDS).aiMessage().text());
            assertEquals("stream-test", header.get());
        } finally {
            server.stop(0);
        }
    }

    private static DynamicModelProvider provider(AiModelConfigService configs) {
        return new DynamicModelProvider(configs, new DelegatingStreamingChatModel(), new DelegatingChatModel());
    }

    private static AiModelConfig config(String base, String protocol) {
        AiModelConfig config = new AiModelConfig();
        config.setId(1);
        config.setProviderKey("custom");
        config.setBaseUrl(base);
        config.setProtocol(protocol);
        config.setCompletionsPath(DynamicModelProvider.defaultPathForProtocol(protocol));
        config.setApiKey("test-key");
        config.setModel("test-model");
        return config;
    }

    private static ProviderCapabilities caps(int context, boolean streaming) {
        return new ProviderCapabilities(true, "recognized", "manual", context, 4096,
                true, false, streaming, true, false, false, true);
    }
}
