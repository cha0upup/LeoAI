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
import org.leo.core.entity.ProviderCapabilities;
import org.leo.web.config.ApiResponseStatusAdvice;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;

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
    private final AiModelConfigController controller = new AiModelConfigController(configs, runtime,
            mock(AiErrorClassifier.class), mock(AiModelFailoverService.class),
            mock(AiModelCapabilityProbeService.class), mock(AiModelDiscoveryService.class));

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

    @ParameterizedTest
    @CsvSource({
            "openai,https://api.openai.com/v1,responses,gpt-6-astra",
            "deepseek,https://api.deepseek.com,chat_completions,deepseek-flash",
            "kimi,https://api.moonshot.cn/v1,chat_completions,kimi-k3.1-pro",
            "qwen,https://dashscope.aliyuncs.com/compatible-mode/v1,chat_completions,qwen3.8-flash",
            "zhipu,https://open.bigmodel.cn/api/paas/v4,chat_completions,glm-5.3-flashx-legal",
            "gemini,https://generativelanguage.googleapis.com/v1beta/openai,chat_completions,gemini-4-argon",
            "mimo,https://api.xiaomimimo.com/v1,chat_completions,mimo-v2.6-pro",
            "openrouter,https://openrouter.ai/api/v1,chat_completions,",
            "ollama,http://localhost:11434/v1,chat_completions,",
            "custom,'',chat_completions,"
    })
    void allPresetsUseOneApiRootAndProviderScopedRecommendations(String key, String base, String protocol, String model) {
        when(configs.listModelCapabilities()).thenReturn(List.of("gpt-6-astra", "deepseek-flash", "kimi-k3.1-pro",
                "qwen3.8-flash", "glm-5.3-flashx-legal", "gemini-4-argon", "mimo-v2.6-pro")
                .stream().map(AiModelConfigControllerTest::capability).toList());
        List<?> presets = (List<?>) controller.providers().get("data");
        Map<?, ?> preset = (Map<?, ?>) presets.stream()
                .filter(row -> key.equals(((Map<?, ?>) row).get("key"))).findFirst().orElseThrow();
        assertEquals(base, preset.get("baseUrl"));
        assertEquals(protocol, preset.get("protocol"));
        assertEquals(protocol.equals("responses") ? "/responses" : "/chat/completions", preset.get("completionsPath"));
        assertEquals(model == null ? List.of() : List.of(model), preset.get("popularModels"));
    }

    @Test
    void kimiPresetUsesTheOfficialEndpointAndOnlyItsCatalogModels() {
        when(configs.listModelCapabilities()).thenReturn(List.of(
                capability("kimi-k3.1-pro"), capability("deepseek-flash"), capability("kimi-k2.6")));

        Map<String, Object> response = controller.providers();
        assertEquals(200, response.get("code"));
        List<?> presets = (List<?>) response.get("data");
        List<?> kimi = presets.stream().filter(row -> "kimi".equals(((Map<?, ?>) row).get("key"))).toList();
        assertEquals(List.of(Map.of(
                "label", "Kimi (Moonshot)", "key", "kimi", "baseUrl", "https://api.moonshot.cn/v1",
                "protocol", "chat_completions", "completionsPath", "/chat/completions",
                "popularModels", List.of("kimi-k3.1-pro", "kimi-k2.6"))), kimi);
    }

    @ParameterizedTest
    @CsvSource(value = {"null,null", "8,Fallback", "99,null"}, nullValues = "null")
    void listUsesItsSnapshotForFallbackNamesAndMatchesTheSingleModelView(Integer fallbackId, String fallbackName) {
        AiModelConfig primary = model(7, "Primary");
        AiModelConfig fallback = model(8, "Fallback");
        primary.setFallbackModelId(fallbackId);
        when(configs.listAll()).thenReturn(List.of(primary, fallback));
        when(configs.capabilitiesForModel(any(AiModelConfig.class)))
                .thenReturn(ProviderCapabilities.conservativeDefault());

        var response = controller.list();
        assertEquals(200, response.get("code"));
        List<?> views = (List<?>) response.get("data");
        assertEquals(List.of(7, 8), views.stream().map(view -> ((Map<?, ?>) view).get("id")).toList());
        Map<?, ?> primaryView = (Map<?, ?>) views.get(0);
        assertEquals(fallbackId, primaryView.get("fallbackModelId"));
        assertEquals(fallbackName, primaryView.get("fallbackModelName"));
        verify(configs, never()).findById(any());

        when(configs.findById(7)).thenReturn(primary);
        if (fallbackId != null) when(configs.findById(fallbackId)).thenReturn(fallbackId == 8 ? fallback : null);
        assertEquals(primaryView, controller.get(7).get("data"));
        verifyNoInteractions(runtime);
    }

    @ParameterizedTest
    @CsvSource({"PUT,true", "PUT,false", "DELETE,true", "DELETE,false"})
    void writesUseTheServiceOutcomeAndPreserveHttpStatusAndSecretRedaction(String method, boolean found) throws Exception {
        AiModelConfig saved = model(7, "Saved");
        if ("PUT".equals(method)) {
            when(configs.update(eq(7), any())).thenReturn(found ? saved : null);
            when(configs.capabilitiesForModel(any(AiModelConfig.class)))
                    .thenReturn(ProviderCapabilities.conservativeDefault());
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
