package org.leo.web.controller.platform.ai;

import org.junit.jupiter.api.Test;
import org.leo.ai.channel.AiModelConfigService;
import org.leo.ai.runtime.AiTurnTelemetryRegistry;
import org.leo.core.entity.AiModelConfig;
import org.leo.core.entity.ProviderCapabilities;
import org.leo.core.entity.User;
import org.leo.web.interceptor.LoginInterceptor;
import org.leo.web.security.PermissionService;
import org.leo.web.service.AiTurnProtocolService;
import org.leo.web.service.AiTurnQueueService;
import org.leo.web.service.PlatformAiThreadService;
import org.leo.web.util.AiEventSubscriptionService;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class PlatformAiControllerTest {
    @Test
    void ordinaryChatCatalogIncludesTheSameEffortContractAndModeAsAdminWithoutConnectionSecrets() throws Exception {
        AiModelConfigService configs = mock(AiModelConfigService.class);
        AiModelConfig glm = config(1, "glm-5.3");
        glm.setReasoningEffort("high");
        AiModelConfig kimi = config(2, "kimi-k2.7-code");
        AiModelConfig gemini = config(3, "gemini-4-argon");
        AiModelConfig disabledThinking = config(4, "deepseek-flash");
        disabledThinking.setThinkingEnabled(0);
        when(configs.listEnabled()).thenReturn(List.of(glm, kimi, gemini, disabledThinking));
        when(configs.capabilitiesForModel(any())).thenReturn(new ProviderCapabilities(true, "recognized", "system",
                1_000_000, 128_000, true, true, true, true, true, false, true));
        PlatformAiThreadService threads = mock(PlatformAiThreadService.class);
        AiTurnQueueService queue = mock(AiTurnQueueService.class);
        PlatformAiController controller = new PlatformAiController(threads, configs,
                mock(AiTurnTelemetryRegistry.class), mock(AiEventSubscriptionService.class),
                mock(AiTurnProtocolService.class), queue);
        PermissionService permissions = mock(PermissionService.class);
        User user = new User();
        user.setPrivilege("normal");
        when(permissions.getCurrentUser(any())).thenReturn(user);

        MockMvcBuilders.standaloneSetup(controller).addInterceptors(new LoginInterceptor(permissions)).build()
                .perform(get("/platform/ai/models"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(4))
                .andExpect(jsonPath("$.data[0].requestOptions.reasoningEfforts", contains("low", "high", "max")))
                .andExpect(jsonPath("$.data[0].requestOptions.source").value("official"))
                .andExpect(jsonPath("$.data[0].reasoningEffort").value("high"))
                .andExpect(jsonPath("$.data[1].supportsReasoning").value(true))
                .andExpect(jsonPath("$.data[1].requestOptions.reasoningEfforts").isEmpty())
                .andExpect(jsonPath("$.data[2].requestOptions.reasoningEfforts", contains("minimal", "low", "medium", "high")))
                .andExpect(jsonPath("$.data[3].thinkingEnabled").value(0))
                .andExpect(jsonPath("$.data[0].apiKey").doesNotExist())
                .andExpect(jsonPath("$.data[0].baseUrl").doesNotExist())
                .andExpect(jsonPath("$.data[0].headersJson").doesNotExist())
                .andExpect(content().string(not(containsString("private-fixture"))));
        verifyNoInteractions(threads, queue);
    }

    private static AiModelConfig config(int id, String model) {
        AiModelConfig config = new AiModelConfig();
        config.setId(id);
        config.setName(model);
        config.setModel(model);
        config.setProviderKey("custom");
        config.setProtocol("chat_completions");
        config.setEnabled(1);
        config.setApiKey("private-fixture-token");
        config.setBaseUrl("https://private-fixture.test/v1");
        config.setHeadersJson("{\"X-Private\":\"private-fixture-header\"}");
        return config;
    }
}
