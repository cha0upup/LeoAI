package org.leo.web.controller.platform.admin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.leo.ai.channel.AiModelCapabilityProbeService;
import org.leo.ai.channel.AiModelConfigService;
import org.leo.ai.channel.AiModelDiscoveryService;
import org.leo.ai.channel.AiModelFailoverService;
import org.leo.ai.channel.DynamicModelProvider;
import org.leo.ai.channel.ModelRequestPlan;
import org.leo.ai.service.AiErrorClassifier;
import org.leo.core.entity.AiModelCapability;
import org.leo.core.entity.AiModelConfig;
import org.leo.core.entity.AiProvider;
import org.leo.core.entity.ProviderCapabilities;
import org.leo.web.config.ApiResponseStatusAdvice;
import org.leo.web.config.GlobalExceptionHandler;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AiModelConfigControllerTest {
    private final AiModelConfigService configs = mock(AiModelConfigService.class);
    private final DynamicModelProvider runtime = mock(DynamicModelProvider.class);
    private final AiModelDiscoveryService discovery = mock(AiModelDiscoveryService.class);
    private final AiModelConfigController controller = new AiModelConfigController(configs, runtime,
            mock(AiErrorClassifier.class), mock(AiModelFailoverService.class),
            mock(AiModelCapabilityProbeService.class), discovery);

    @Test
    void draftPreviewReturnsTypedFieldErrorsWithoutBuildingOrCallingARuntime() throws Exception {
        AiModelConfig draft = new AiModelConfig();
        draft.setModel("mimo-v2.6-pro");
        draft.setThinkingEnabled(2);
        ProviderCapabilities caps = new ProviderCapabilities(true, "recognized", "system", 1_000_000, 128_000,
                true, true, true, true, true, true, true);
        when(configs.previewRequest(any())).thenReturn(ModelRequestPlan.preview(draft, caps));
        MockMvcBuilders.standaloneSetup(controller).build()
                .perform(post("/platform/admin/ai-models/request-preview").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"providerId\":1,\"model\":\"mimo-v2.6-pro\",\"thinkingEnabled\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.valid").value(false))
                .andExpect(jsonPath("$.data.errors[0].field").value("thinkingEnabled"))
                .andExpect(jsonPath("$.data.contract.parameters.temperature.maximum").value(1.5))
                .andExpect(jsonPath("$.data.requestParameters").isEmpty());
        verifyNoInteractions(runtime);
    }

    @Test
    void capabilityEndpointSerializesTheRequestContractUsedByTheUi() throws Exception {
        when(configs.listModelCapabilities()).thenReturn(List.of(capability("kimi-k2.7-code"), capability("gpt-6.1-sol")));
        MockMvcBuilders.standaloneSetup(controller).build()
                .perform(get("/platform/admin/ai-models/capabilities"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].requestOptions.thinkingRequired").value(true))
                .andExpect(jsonPath("$.data[0].requestOptions.reasoningEfforts").isEmpty())
                .andExpect(jsonPath("$.data[0].requestOptions.temperatureConfigurable").value(false))
                .andExpect(jsonPath("$.data[1].requestOptions.responsesRequiredForTools").value(true))
                .andExpect(jsonPath("$.data[1].requestOptions.reasoningEfforts[4]").value("max"));
    }

    @Test
    void presetsUseOneApiRootAndOnlyRecommendModelsFromTheirCatalog() {
        when(configs.listModelCapabilities()).thenReturn(List.of("gpt-6-astra", "deepseek-flash", "kimi-k3.1-pro", "kimi-k2.6",
                "qwen3.8-flash", "glm-5.3-flashx-legal", "gemini-4-argon", "mimo-v2.6-pro")
                .stream().map(AiModelConfigControllerTest::capability).toList());
        var response = controller.providers();
        assertEquals(200, response.get("code"));
        List<?> presets = (List<?>) response.get("data");
        assertEquals(10, presets.size());

        String chat = "chat_completions";
        assertPreset(presets, "openai", "https://api.openai.com/v1", "responses", "gpt-6-astra");
        assertPreset(presets, "deepseek", "https://api.deepseek.com", chat, "deepseek-flash");
        assertPreset(presets, "kimi", "https://api.moonshot.cn/v1", chat, "kimi-k3.1-pro", "kimi-k2.6");
        assertPreset(presets, "qwen", "https://dashscope.aliyuncs.com/compatible-mode/v1", chat, "qwen3.8-flash");
        assertPreset(presets, "zhipu", "https://open.bigmodel.cn/api/paas/v4", chat, "glm-5.3-flashx-legal");
        assertPreset(presets, "gemini", "https://generativelanguage.googleapis.com/v1beta/openai", chat, "gemini-4-argon");
        assertPreset(presets, "mimo", "https://api.xiaomimimo.com/v1", chat, "mimo-v2.6-pro");
        assertPreset(presets, "openrouter", "https://openrouter.ai/api/v1", chat);
        assertPreset(presets, "ollama", "http://localhost:11434/v1", chat);
        assertPreset(presets, "custom", "", chat);
    }

    private static void assertPreset(List<?> presets, String key, String base, String protocol, String... models) {
        Map<?, ?> preset = (Map<?, ?>) presets.stream()
                .filter(row -> key.equals(((Map<?, ?>) row).get("key"))).findFirst().orElseThrow(() -> new AssertionError(key));
        assertEquals(base, preset.get("baseUrl"), key);
        assertEquals(protocol, preset.get("protocol"), key);
        assertEquals(protocol.equals("responses") ? "/responses" : "/chat/completions", preset.get("completionsPath"), key);
        assertEquals(List.of(models), preset.get("popularModels"), key);
    }

    @Test
    void listSerializesTheCatalogWithoutPerRowReadsOrCredentials() throws Exception {
        AiModelConfig primary = model(7, "Primary");
        AiModelConfig fallback = model(8, "Fallback");
        primary.setFallbackModelId(8);
        ProviderCapabilities capabilities = ProviderCapabilities.conservativeDefault();
        when(configs.listCatalog()).thenReturn(List.of(
                new AiModelConfigService.CatalogEntry(primary, "Fallback", "resolved-model", capabilities),
                new AiModelConfigService.CatalogEntry(fallback, null, "fallback-model", capabilities)));

        MockMvcBuilders.standaloneSetup(controller).build()
                .perform(get("/platform/admin/ai-models"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(7))
                .andExpect(jsonPath("$.data[1].id").value(8))
                .andExpect(jsonPath("$.data[0].fallbackModelId").value(8))
                .andExpect(jsonPath("$.data[0].fallbackModelName").value("Fallback"))
                .andExpect(jsonPath("$.data[0].capabilityModelName").value("resolved-model"))
                .andExpect(jsonPath("$.data[0].capabilitySource").value("conservative_default"))
                .andExpect(jsonPath("$.data[0].apiKeyConfigured").value(true))
                .andExpect(jsonPath("$.data[0].headersConfigured").value(true))
                .andExpect(jsonPath("$.data[*].apiKey").doesNotHaveJsonPath())
                .andExpect(jsonPath("$.data[*].headersJson").doesNotHaveJsonPath());
        verify(configs, never()).findById(any());
        verify(configs, never()).capabilitiesForModel(any(AiModelConfig.class));
        verifyNoInteractions(runtime);
    }

    @Test
    void removedDetailGetReturnsMethodNotAllowedAndPreservesAllowedMethods() throws Exception {
        MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler(), new ApiResponseStatusAdvice()).build()
                .perform(get("/platform/admin/ai-models/7"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value(405))
                .andExpect(result -> assertEquals(Set.of("PUT", "DELETE"),
                        Set.of(result.getResponse().getHeader(HttpHeaders.ALLOW).split(",\\s*"))));
        verifyNoInteractions(configs, runtime);
    }

    @ParameterizedTest
    @CsvSource({"PUT,true", "PUT,false", "DELETE,true", "DELETE,false"})
    void writesUseTheServiceOutcomeAndPreserveHttpStatusAndSecretRedaction(String method, boolean found) throws Exception {
        AiModelConfig saved = model(7, "Saved");
        if ("PUT".equals(method)) {
            when(configs.update(eq(7), any())).thenReturn(found ? saved : null);
            if (found) {
                when(configs.describeModel(saved)).thenReturn(new AiModelConfigService.CatalogEntry(
                        saved, null, saved.getModel(), ProviderCapabilities.conservativeDefault()));
            }
        } else {
            when(configs.deleteById(7)).thenReturn(found);
        }

        int expected = found ? 200 : 404;
        var response = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ApiResponseStatusAdvice()).build()
                .perform(request(HttpMethod.valueOf(method), "/platform/admin/ai-models/7")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().is(expected))
                .andExpect(jsonPath("$.code").value(expected))
                .andExpect(jsonPath("$.msg").value(found ? "success" : "模型配置不存在，id: 7"));
        if ("PUT".equals(method)) {
            verify(configs).update(eq(7), any());
            if (found) verify(configs).describeModel(saved);
            if (found) response.andExpect(jsonPath("$.data.id").value(7))
                    .andExpect(jsonPath("$.data.apiKeyConfigured").value(true))
                    .andExpect(jsonPath("$.data.headersConfigured").value(true))
                    .andExpect(jsonPath("$.data.apiKey").doesNotExist())
                    .andExpect(jsonPath("$.data.headersJson").doesNotExist());
        } else {
            verify(configs).deleteById(7);
        }
        verify(configs, never()).findById(7);
        verifyNoInteractions(runtime);
    }

    @Test
    void draftDiscoveryBindsTheSameConnectionFieldsWithoutEchoingThem() throws Exception {
        when(discovery.fetch(any(AiProvider.class))).thenReturn(List.of("fixture-model"));
        MockMvcBuilders.standaloneSetup(controller).build()
                .perform(post("/platform/admin/ai-models/fetch-models")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "baseUrl":"https://example.test/v1",
                                  "apiKey":"fixture-key",
                                  "providerKey":"custom",
                                  "protocol":"chat_completions",
                                  "completionsPath":"/chat/completions",
                                  "headersJson":"{}"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0]").value("fixture-model"));

        ArgumentCaptor<AiProvider> provider = ArgumentCaptor.forClass(AiProvider.class);
        verify(discovery).fetch(provider.capture());
        assertEquals("https://example.test/v1", provider.getValue().getBaseUrl());
        assertEquals("fixture-key", provider.getValue().getApiKey());
        assertEquals("custom", provider.getValue().getProviderKey());
        assertEquals("chat_completions", provider.getValue().getProtocol());
        assertEquals("/chat/completions", provider.getValue().getCompletionsPath());
        assertEquals("{}", provider.getValue().getHeadersJson());
        verifyNoInteractions(configs, runtime);
    }

    private static AiModelConfig model(int id, String name) {
        AiModelConfig row = new AiModelConfig();
        row.setId(id);
        row.setName(name);
        row.setModel("test-model");
        row.setProviderKey("custom");
        row.setProtocol("chat_completions");
        row.setApiKey("fixture-secret");
        row.setHeadersJson("{\"X-Test\":\"fixture-secret\"}");
        return row;
    }

    private static AiModelCapability capability(String name) {
        AiModelCapability row = new AiModelCapability();
        row.setModelName(name);
        return row;
    }
}
