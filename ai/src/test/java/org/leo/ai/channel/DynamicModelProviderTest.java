package org.leo.ai.channel;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.leo.core.entity.AiModelConfig;
import org.leo.core.entity.ProviderCapabilities;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DynamicModelProviderTest {
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @ParameterizedTest
    @ValueSource(strings = {"auto", " AUTO ", "", "  "})
    void automaticChatEffortInheritsTheSavedModelSettingInsteadOfForcingMedium(String effort) {
        AiModelConfig config = config("https://example.test/v1", "chat_completions");
        config.setModel("glm-5.3");
        config.setReasoningEffort("high");
        AiModelConfigService configs = mock(AiModelConfigService.class);
        when(configs.capabilitiesForModel(config)).thenReturn(reasoningCaps());
        DynamicModelProvider provider = provider(configs);
        var runtime = provider.buildRuntime(config, effort);
        assertEquals("high", runtime.reasoningEffort());
        assertEquals(DynamicModelProvider.runtimeCacheKey(config, runtime), provider.plannedRuntimeCacheKey(config, effort));
        assertEquals("high", config.getReasoningEffort());
    }

    @ParameterizedTest
    @CsvSource({
            "deepseek-flash,,max,,,max_tokens,true",
            "deepseek-flash,1,max,thinking,enabled,max_tokens,true",
            "deepseek-flash,0,,thinking,disabled,max_tokens,false",
            "glm-5.3,,low,,,max_tokens,true",
            "glm-5.3,1,low,thinking,enabled,max_tokens,true",
            "mimo-v2.6-pro,,,,,max_completion_tokens,true",
            "mimo-v2.6-pro,1,,thinking,enabled,max_completion_tokens,true",
            "mimo-v2.6-flash,0,,thinking,disabled,max_completion_tokens,false",
            "mimo-v2.6-pro-ultraspeed,,,,,max_completion_tokens,true",
            "qwen3.8-max,,,,,max_tokens,true",
            "qwen3.8-max,1,,enable_thinking,true,max_tokens,true",
            "qwen3.8-max,0,,enable_thinking,false,max_tokens,false",
            "qwen3.8-flash,,,,,max_tokens,true",
            "qwen3.7-plus,0,,enable_thinking,false,max_tokens,false",
            "gemini-4-argon,,low,,,max_tokens,true",
            "gemini-3.6-flash,,medium,,,max_tokens,true",
            "gemini-3.5-flash-lite,,minimal,,,max_tokens,true",
            "glm-5.3-flashx-legal,,high,,,max_tokens,true",
            "kimi-k2.6,,,,,max_tokens,true",
            "kimi-k2.6,1,,thinking,enabled,max_tokens,true",
            "kimi-k2.6,0,,thinking,disabled,max_tokens,false",
            "kimi-k2.7-code,,,,,max_tokens,true",
            "kimi-k2.7-code-highspeed,,,,,max_tokens,true",
            "kimi-k3,,high,,,max_completion_tokens,true",
            "kimi-k3.1-pro,,max,,,max_completion_tokens,true",
            "kimi-k3.1-pro-highspeed,,low,,,max_completion_tokens,true"
    })
    void currentModelsUseDocumentedParametersAndPreserveReasoning(String model, Integer thinking, String effort,
            String parameter, String expectedValue, String outputParameter, boolean reasoning) throws Exception {
        AtomicReference<JSONObject> body = new AtomicReference<>();
        String baseUrl = serve("/v1/chat/completions", exchange -> {
            body.set(JSON.parseObject(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            respond(exchange, "application/json", "{\"id\":\"test\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\","
                    + "\"content\":\"OK\"},\"finish_reason\":\"stop\"}]}");
        });
        AiModelConfig config = config(baseUrl, "chat_completions");
        config.setModel(model);
        config.setThinkingEnabled(thinking);
        config.setReasoningEffort(effort);
        config.setMaxOutputTokens(256);
        config.setTemperature(0.7);
        AiModelConfigService configs = mock(AiModelConfigService.class);
        when(configs.capabilitiesForModel(config)).thenReturn(reasoningCaps());
        var runtime = provider(configs).buildRuntime(config);
        assertEquals(reasoning, runtime.doReasoning());
        runtime.chatModel().chat(ChatRequest.builder().messages(
                UserMessage.from("First"), AiMessage.builder().text("Previous").thinking("previous reasoning").build(),
                UserMessage.from("Next")).build());
        JSONObject sent = body.get();
        ModelRequestPlan.Preview preview = ModelRequestPlan.preview(config, reasoningCaps());
        assertTrue(preview.valid());
        preview.requestParameters().forEach((key, value) -> assertEquals(JSON.toJSONString(value),
                JSON.toJSONString(sent.get(key)), "Preview and inference differ at " + key));
        if (parameter == null) {
            assertFalse(sent.containsKey("thinking"));
            assertFalse(sent.containsKey("enable_thinking"));
        } else {
            assertEquals(expectedValue, parameter.equals("thinking")
                    ? sent.getJSONObject(parameter).getString("type") : sent.getString(parameter));
        }
        if (model.startsWith("kimi-")) assertFalse(sent.containsKey("temperature"));
        assertEquals(256, sent.getIntValue(outputParameter));
        assertFalse(sent.containsKey(outputParameter.equals("max_tokens") ? "max_completion_tokens" : "max_tokens"));
        assertEquals(effort, sent.getString("reasoning_effort"));
        // Gemini uses its own thought-signature contract, not reasoning_content replay.
        assertEquals(reasoning && !model.startsWith("gemini-") ? "previous reasoning" : null,
                sent.getJSONArray("messages").getJSONObject(1).getString("reasoning_content"));
    }

    @ParameterizedTest
    @CsvSource({
            "glm-5.3,low", "glm-5.3-flash,low", "gemini-3.8-flash,low", "gemini-4-argon,low",
            "gpt-6-astra,low", "gpt-6.1-sol,low",
            "kimi-k3,low", "kimi-k3.1-pro,low", "kimi-k3.1-pro-highspeed,low",
            "kimi-k2.7-code,", "kimi-k2.7-code-highspeed,"})
    void mandatoryThinkingModelsRejectDisableAndProbeWithSupportedEffort(String model, String expectedProbeEffort) {
        AiModelConfig config = config("https://example.test/v1", "chat_completions");
        config.setModel(model);
        config.setThinkingEnabled(0);
        AiModelConfigService configs = mock(AiModelConfigService.class);
        when(configs.capabilitiesForModel(config)).thenReturn(reasoningCaps());
        DynamicModelProvider provider = provider(configs);
        assertThrows(IllegalArgumentException.class, () -> provider.buildRuntime(config));
        var probe = provider.buildProbeRuntime(config, false);
        assertTrue(probe.doReasoning());
        assertEquals(expectedProbeEffort, probe.reasoningEffort());
        assertEquals(1024, probe.maxTokens());
    }

    @ParameterizedTest
    @CsvSource({"kimi-k3,medium", "kimi-k3.1-pro,xhigh", "kimi-k2.6,high", "kimi-k2.7-code,low"})
    void kimiRejectsUnsupportedReasoningEfforts(String model, String effort) {
        AiModelConfig config = config("https://example.test/v1", "chat_completions");
        config.setModel(model);
        config.setReasoningEffort(effort);
        AiModelConfigService configs = mock(AiModelConfigService.class);
        when(configs.capabilitiesForModel(config)).thenReturn(reasoningCaps());

        assertThrows(IllegalArgumentException.class, () -> provider(configs).buildRuntime(config));
    }

    @ParameterizedTest
    @ValueSource(strings = {"kimi-k2.6", "kimi-k3.1-pro"})
    void kimiStreamingPreservesReasoningInBothDirections(String model) throws Exception {
        AtomicReference<JSONObject> body = new AtomicReference<>();
        String baseUrl = serve("/v1/chat/completions", exchange -> {
            body.set(JSON.parseObject(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            String sse = "data: {\"id\":\"test\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"reasoning_content\":\"reasoned\"}}]}\n\n"
                    + "data: {\"id\":\"test\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"OK\"}}]}\n\n"
                    + "data: {\"id\":\"test\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n"
                    + "data: [DONE]\n\n";
            respond(exchange, "text/event-stream", sse);
        });
        AiModelConfig config = config(baseUrl, "chat_completions");
        config.setProviderKey("kimi");
        config.setModel(model);
        AiModelConfigService configs = mock(AiModelConfigService.class);
        when(configs.capabilitiesForModel(config)).thenReturn(reasoningCaps());
        CompletableFuture<ChatResponse> result = new CompletableFuture<>();
        provider(configs).buildRuntime(config).streamingModel().chat(ChatRequest.builder().messages(
                UserMessage.from("First"), AiMessage.builder().text("Previous").thinking("previous reasoning").build(),
                UserMessage.from("Next")).build(), new StreamingChatResponseHandler() {
                    public void onCompleteResponse(ChatResponse response) { result.complete(response); }
                    public void onError(Throwable error) { result.completeExceptionally(error); }
                });

        ChatResponse response = result.get(5, TimeUnit.SECONDS);
        assertEquals("OK", response.aiMessage().text());
        assertEquals("reasoned", response.aiMessage().thinking());
        assertEquals("previous reasoning", body.get().getJSONArray("messages").getJSONObject(1).getString("reasoning_content"));
        assertFalse(body.get().containsKey("thinking"));
    }

    private static ProviderCapabilities reasoningCaps() {
        return new ProviderCapabilities(true, "recognized", "system", 1_000_000, 128_000,
                true, true, true, true, true, false, true);
    }

    @ParameterizedTest
    @CsvSource({"gpt-6-astra,1,max", "gpt-6.1-sol,1,high", "gpt-6.1-terra,0,none", "gpt-6-luna,0,none"})
    void openAiCurrentModelsUseResponsesAndRespectInputAndSamplingConstraints(String model, int thinking, String effort) throws Exception {
        AtomicReference<JSONObject> body = new AtomicReference<>();
        String baseUrl = serve("/v1/responses", exchange -> {
            body.set(JSON.parseObject(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            respond(exchange, "application/json", "{\"id\":\"resp_test\",\"object\":\"response\",\"model\":\"" + model
                    + "\",\"status\":\"completed\",\"output\":[{\"type\":\"message\",\"role\":\"assistant\",\"content\":[{\"type\":\"output_text\",\"text\":\"OK\"}]}]}");
        });
        AiModelConfig config = config(baseUrl, "responses");
        config.setProviderKey("openai");
        config.setModel(model);
        config.setThinkingEnabled(thinking);
        config.setReasoningEffort(thinking == 0 ? "auto" : effort);
        config.setTemperature(0.7);
        AiModelConfigService configs = mock(AiModelConfigService.class);
        when(configs.capabilitiesForModel(config)).thenReturn(reasoningCaps());
        var runtime = provider(configs).buildRuntime(config);
        assertEquals(922_000, runtime.contextWindowTokens());
        assertEquals("OK", runtime.chatModel().chat(ChatRequest.builder().messages(UserMessage.from("OK")).build()).aiMessage().text());
        assertEquals(effort, body.get().getJSONObject("reasoning").getString("effort"));
        assertEquals(thinking == 0 ? 0.7 : null, body.get().getDouble("temperature"));
        assertTrue(body.get().containsKey("max_output_tokens"));
        config.setProtocol("chat_completions");
        config.setCompletionsPath("/chat/completions");
        if (model.equals("gpt-6-luna") && thinking == 0) assertNotNull(provider(configs).buildRuntime(config));
        else assertThrows(IllegalArgumentException.class, () -> provider(configs).buildRuntime(config));
    }

    @ParameterizedTest
    @CsvSource({"openrouter,deepseek/deepseek-flash,1,high", "openrouter,openai/gpt-6-astra,0,", "ollama,qwen3.8-max,0,"})
    void gatewayParametersDoNotInheritDirectVendorFields(String key, String model, int thinking, String effort) throws Exception {
        AtomicReference<JSONObject> body = new AtomicReference<>();
        String baseUrl = serve("/v1/chat/completions", exchange -> {
            body.set(JSON.parseObject(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            respond(exchange, "application/json", "{\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"OK\"},\"finish_reason\":\"stop\"}]}");
        });
        AiModelConfig config = config(baseUrl, "chat_completions");
        config.setProviderKey(key);
        config.setModel(model);
        config.setThinkingEnabled(thinking);
        config.setReasoningEffort(effort);
        AiModelConfigService configs = mock(AiModelConfigService.class);
        when(configs.capabilitiesForModel(config)).thenReturn(reasoningCaps());
        provider(configs).buildRuntime(config).chatModel().chat(ChatRequest.builder().messages(UserMessage.from("OK")).build());
        assertFalse(body.get().containsKey("thinking"));
        assertFalse(body.get().containsKey("enable_thinking"));
        if (key.equals("openrouter")) {
            assertFalse(body.get().containsKey("reasoning_effort"));
            assertEquals(effort == null ? Map.of("enabled", false) : Map.of("effort", effort), body.get().getJSONObject("reasoning"));
        } else assertEquals("none", body.get().getString("reasoning_effort"));
    }

    @Test
    void contextChangesInvalidateRuntimeAndSnapshotUsesTheSameBudget() {
        AiModelConfigService configs = mock(AiModelConfigService.class);
        AiModelConfig config = config("https://example.test/v1", "chat_completions");
        DynamicModelProvider provider = provider(configs);
        when(configs.capabilitiesForModel(config)).thenReturn(caps(65_536, true));
        String first = provider.plannedRuntimeCacheKey(config, null);
        when(configs.capabilitiesForModel(config)).thenReturn(caps(8_192, true));
        String second = provider.plannedRuntimeCacheKey(config, null);
        assertNotEquals(first, second);
        var runtime = provider.buildRuntime(config);
        assertEquals(8_192, runtime.contextWindowTokens());
        assertEquals(second, DynamicModelProvider.runtimeCacheKey(config, runtime));
    }

    @ParameterizedTest
    @ValueSource(strings = {"responses", "chat_completions"})
    void probeIgnoresNegativeCapabilitiesAndSendsHeadersWithBoundedOutput(String protocol) throws Exception {
        AtomicReference<String> path = new AtomicReference<>();
        AtomicReference<String> auth = new AtomicReference<>();
        AtomicReference<String> header = new AtomicReference<>();
        AtomicReference<JSONObject> body = new AtomicReference<>();
        String baseUrl = serve("/", exchange -> {
            path.set(exchange.getRequestURI().getPath());
            auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            header.set(exchange.getRequestHeaders().getFirst("X-Gateway-Key"));
            body.set(JSON.parseObject(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            String json = protocol.equals("responses")
                    ? "{\"id\":\"resp_test\",\"object\":\"response\",\"model\":\"test-model\",\"status\":\"completed\",\"output\":[{\"type\":\"message\",\"role\":\"assistant\",\"content\":[{\"type\":\"output_text\",\"text\":\"OK\"}]}]}"
                    : "{\"id\":\"test\",\"model\":\"test-model\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"OK\"},\"finish_reason\":\"stop\"}]}";
            respond(exchange, "application/json", json);
        });
        AiModelConfigService configs = mock(AiModelConfigService.class);
        AiModelConfig config = config(baseUrl, protocol);
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
    }

    @Test
    void responsesStreamingAlsoCarriesCustomHeaders() throws Exception {
        AtomicReference<String> header = new AtomicReference<>();
        String baseUrl = serve("/v1/responses", exchange -> {
            header.set(exchange.getRequestHeaders().getFirst("X-Gateway-Key"));
            exchange.getRequestBody().readAllBytes();
            String sse = "event: response.output_text.delta\ndata: {\"type\":\"response.output_text.delta\",\"delta\":\"OK\"}\n\n"
                    + "event: response.completed\ndata: {\"type\":\"response.completed\",\"response\":{\"id\":\"resp_test\",\"model\":\"test-model\",\"status\":\"completed\",\"output\":[{\"type\":\"message\",\"role\":\"assistant\",\"content\":[{\"type\":\"output_text\",\"text\":\"OK\"}]}]}}\n\n";
            respond(exchange, "text/event-stream", sse);
        });
        AiModelConfig config = config(baseUrl, "responses");
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
    }

    private String serve(String path, HttpHandler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(path, handler);
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    private static void respond(HttpExchange exchange, String contentType, String body) throws IOException {
        try (exchange) {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", contentType);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
        }
    }

    private static DynamicModelProvider provider(AiModelConfigService configs) {
        return new DynamicModelProvider(configs, new DelegatingChatModel());
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
