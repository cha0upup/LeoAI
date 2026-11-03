package org.leo.web.controller.platform.admin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.leo.ai.channel.AiModelCapabilityProbeService;
import org.leo.ai.channel.AiModelConfigService;
import org.leo.ai.channel.AiModelDiscoveryService;
import org.leo.ai.channel.AiModelFailoverService;
import org.leo.ai.channel.DynamicModelProvider;
import org.leo.ai.service.AiErrorClassifier;
import org.leo.core.entity.AiModelCapability;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AiModelConfigControllerTest {
    @Test
    void capabilityEndpointSerializesTheRequestContractUsedByTheUi() throws Exception {
        AiModelConfigService configs = mock(AiModelConfigService.class);
        when(configs.listModelCapabilities()).thenReturn(List.of(capability("kimi-k2.7-code"), capability("gpt-6.1-sol")));
        AiModelConfigController controller = new AiModelConfigController(configs,
                mock(DynamicModelProvider.class), mock(AiErrorClassifier.class), mock(AiModelFailoverService.class),
                mock(AiModelCapabilityProbeService.class), mock(AiModelDiscoveryService.class));
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
        AiModelConfigService configs = mock(AiModelConfigService.class);
        when(configs.listModelCapabilities()).thenReturn(List.of("gpt-6-astra", "deepseek-flash", "kimi-k3.1-pro",
                "qwen3.8-flash", "glm-5.3-flashx-legal", "gemini-4-argon", "mimo-v2.6-pro")
                .stream().map(AiModelConfigControllerTest::capability).toList());
        AiModelConfigController controller = new AiModelConfigController(configs,
                mock(DynamicModelProvider.class), mock(AiErrorClassifier.class), mock(AiModelFailoverService.class),
                mock(AiModelCapabilityProbeService.class), mock(AiModelDiscoveryService.class));
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
        AiModelConfigService configs = mock(AiModelConfigService.class);
        when(configs.listModelCapabilities()).thenReturn(List.of(
                capability("kimi-k3.1-pro"), capability("deepseek-flash"), capability("kimi-k2.6")));
        AiModelConfigController controller = new AiModelConfigController(configs,
                mock(DynamicModelProvider.class), mock(AiErrorClassifier.class), mock(AiModelFailoverService.class),
                mock(AiModelCapabilityProbeService.class), mock(AiModelDiscoveryService.class));

        Map<String, Object> response = controller.providers();
        assertEquals(200, response.get("code"));
        List<?> presets = (List<?>) response.get("data");
        List<?> kimi = presets.stream().filter(row -> "kimi".equals(((Map<?, ?>) row).get("key"))).toList();
        assertEquals(List.of(Map.of(
                "label", "Kimi (Moonshot)", "key", "kimi", "baseUrl", "https://api.moonshot.cn/v1",
                "protocol", "chat_completions", "completionsPath", "/chat/completions",
                "popularModels", List.of("kimi-k3.1-pro", "kimi-k2.6"))), kimi);
    }

    private static AiModelCapability capability(String name) {
        AiModelCapability row = new AiModelCapability();
        row.setModelName(name);
        return row;
    }
}
