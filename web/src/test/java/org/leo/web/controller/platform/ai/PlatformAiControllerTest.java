package org.leo.web.controller.platform.ai;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.leo.ai.channel.AiModelConfigService;
import org.leo.ai.platform.PlatformAiState;
import org.leo.ai.runtime.AiTurnTelemetryRegistry;
import org.leo.core.entity.AiModelConfig;
import org.leo.core.entity.ProviderCapabilities;
import org.leo.core.entity.User;
import org.leo.web.adapter.ai.AiEventSubscriptionService;
import org.leo.web.application.UseCaseException;
import org.leo.web.application.ai.AiThreadQueryService;
import org.leo.web.application.ai.AiTurnProtocolService;
import org.leo.web.application.ai.AiTurnQueueService;
import org.leo.web.application.ai.PlatformAiThreadService;
import org.leo.web.application.ai.PlatformAiThreadService.CreatedThread;
import org.leo.web.dto.platform.ai.PlatformAiDtos;
import org.leo.web.interceptor.LoginInterceptor;
import org.leo.web.security.PermissionService;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class PlatformAiControllerTest {
    private final PlatformAiThreadService threads = mock(PlatformAiThreadService.class);
    private final AiThreadQueryService queries = mock(AiThreadQueryService.class);
    private final AiModelConfigService configs = mock(AiModelConfigService.class);
    private final AiTurnQueueService queue = mock(AiTurnQueueService.class);
    private final PlatformAiController controller = new PlatformAiController(threads, queries, configs,
            mock(AiTurnTelemetryRegistry.class), mock(AiEventSubscriptionService.class),
            mock(AiTurnProtocolService.class), queue);

    @Test
    void ownsHttpSessionBindingAndPreservesItWhenTheUseCaseFails() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        var session = new MockHttpSession();
        request.setSession(session);
        User user = new User();
        session.setAttribute("user", user);
        session.setAttribute("platformAiStateId", "previous");

        when(threads.initializeThread(session.getId(), "previous", user, 99))
                .thenThrow(new UseCaseException(UseCaseException.Kind.NOT_FOUND, "模型已删除"));
        assertThrows(UseCaseException.class, () -> controller.initializeThread(
                new PlatformAiDtos.InitializeThreadRequest(99), request));
        assertEquals("previous", session.getAttribute("platformAiStateId"));

        PlatformAiState created = new PlatformAiState("created");
        when(threads.initializeThread(session.getId(), "previous", user, 7))
                .thenReturn(new CreatedThread("created", "平台 AI", 7));
        var mvc = MockMvcBuilders.standaloneSetup(controller).build();
        mvc.perform(post("/platform/ai/thread/initialize").session(session)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"configId\":7}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.threadId").value("created"))
                .andExpect(jsonPath("$.data.title").value("平台 AI"))
                .andExpect(jsonPath("$.data.configId").value(7));
        assertEquals("created", session.getAttribute("platformAiStateId"));
        mvc.perform(post("/platform/ai/createAgent").session(session)).andExpect(status().isNotFound());

        when(threads.createThread(session.getId(), user, "new", 7))
                .thenReturn(new CreatedThread("new", "new", 7));
        controller.createThread(new PlatformAiDtos.CreateThreadRequest("new", 7), request);
        assertEquals("new", session.getAttribute("platformAiStateId"));

        when(threads.activateThread(user, "created")).thenReturn(created);
        controller.activateThread(new PlatformAiDtos.ThreadIdRequest("created"), request);
        controller.deleteThread(new PlatformAiDtos.ThreadIdRequest("other"), request);
        assertEquals("created", session.getAttribute("platformAiStateId"));
        controller.deleteThread(new PlatformAiDtos.ThreadIdRequest("created"), request);
        assertNull(session.getAttribute("platformAiStateId"));
    }

    @Test
    void threadListRequiresLoginAndQueriesOnlyTheCurrentUser() throws Exception {
        PermissionService permissions = mock(PermissionService.class);
        var mvc = MockMvcBuilders.standaloneSetup(controller)
                .addInterceptors(new LoginInterceptor(permissions)).build();

        mvc.perform(post("/platform/ai/threads")).andExpect(status().isUnauthorized());
        verifyNoInteractions(queries, threads, queue);

        User user = new User();
        user.setUserId("user-1");
        user.setPrivilege("normal");
        when(permissions.getCurrentUser(any())).thenReturn(user);
        when(queries.listPlatformThreads("user-1")).thenReturn(List.of(Map.of("threadId", "owned")));

        mvc.perform(post("/platform/ai/threads").sessionAttr("user", user))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].threadId").value("owned"));
        verify(queries).listPlatformThreads("user-1");
        verifyNoInteractions(threads, queue);
    }

    @Test
    void ordinaryChatCatalogIncludesTheSameEffortContractAndModeAsAdminWithoutConnectionSecrets() throws Exception {
        AiModelConfig glm = config(1, "glm-5.3");
        glm.setReasoningEffort("high");
        glm.setContextWindowTokens(65_536);
        AiModelConfig kimi = config(2, "kimi-k2.7-code");
        kimi.setMaxOutputTokens(4096);
        AiModelConfig gemini = config(3, "gemini-4-argon");
        AiModelConfig disabledThinking = config(4, "deepseek-flash");
        disabledThinking.setThinkingEnabled(0);
        List<AiModelConfig> models = List.of(glm, kimi, gemini, disabledThinking);
        when(configs.listEnabled()).thenReturn(models);
        when(configs.capabilitiesForModel(any())).thenReturn(capabilities(true, true, 128_000));
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
                .andExpect(jsonPath("$.data[0].contextWindowTokens").value(65_536))
                .andExpect(jsonPath("$.data[0].maxOutputTokens").value(128_000))
                .andExpect(jsonPath("$.data[1].supportsReasoning").value(true))
                .andExpect(jsonPath("$.data[1].contextWindowTokens").value(1_000_000))
                .andExpect(jsonPath("$.data[1].maxOutputTokens").value(4096))
                .andExpect(jsonPath("$.data[1].requestOptions.reasoningEfforts").isEmpty())
                .andExpect(jsonPath("$.data[2].requestOptions.reasoningEfforts", contains("minimal", "low", "medium", "high")))
                .andExpect(jsonPath("$.data[3].thinkingEnabled").value(0))
                .andExpect(jsonPath("$.data[0].apiKey").doesNotExist())
                .andExpect(jsonPath("$.data[0].baseUrl").doesNotExist())
                .andExpect(jsonPath("$.data[0].headersJson").doesNotExist())
                .andExpect(content().string(not(containsString("private-fixture"))));
        for (AiModelConfig model : models) verify(configs).capabilitiesForModel(model);
        verifyNoInteractions(threads, queries, queue);
    }

    @ParameterizedTest
    @ValueSource(strings = {"models", "availability"})
    void catalogAndAvailabilityUseTheSameEligibilityRulesAndReadEachCapabilityOnce(String endpoint) {
        AiModelConfig usable = config(1, "usable");
        AiModelConfig noText = config(2, "no-text");
        AiModelConfig noStream = config(3, "no-stream");
        AiModelConfig noOutput = config(4, "no-output");
        List<AiModelConfig> models = List.of(usable, noText, noStream, noOutput);
        when(configs.listEnabled()).thenReturn(models);
        when(configs.capabilitiesForModel(usable)).thenReturn(capabilities(true, true, 4096));
        when(configs.capabilitiesForModel(noText)).thenReturn(capabilities(false, true, 4096));
        when(configs.capabilitiesForModel(noStream)).thenReturn(capabilities(true, false, 4096));
        when(configs.capabilitiesForModel(noOutput)).thenReturn(capabilities(true, true, 0));

        if ("models".equals(endpoint)) {
            List<?> items = (List<?>) controller.models().get("data");
            assertEquals(1, items.size());
            assertEquals(usable.getId(), ((Map<?, ?>) items.get(0)).get("id"));
        } else {
            assertEquals(Map.of("available", true, "enabledCount", 1), controller.availability().get("data"));
        }
        for (AiModelConfig model : models) verify(configs).capabilitiesForModel(model);
    }

    private static ProviderCapabilities capabilities(boolean text, boolean streaming, int outputLimit) {
        return new ProviderCapabilities(true, "recognized", "system", 1_000_000, outputLimit,
                text, true, streaming, true, true, false, true);
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
