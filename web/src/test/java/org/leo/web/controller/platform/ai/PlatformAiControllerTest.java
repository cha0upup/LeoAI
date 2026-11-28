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
import org.leo.web.dto.ai.AiThreadRequests.CreateThreadRequest;
import org.leo.web.dto.ai.AiThreadRequests.ThreadRequest;
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
import static org.mockito.Mockito.verifyNoMoreInteractions;
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
                new PlatformAiController.InitializeThreadRequest(99), request));
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
        controller.createThread(new CreateThreadRequest(null, "new", 7), request);
        assertEquals("new", session.getAttribute("platformAiStateId"));

        when(threads.activateThread(user, "created")).thenReturn(created);
        controller.activateThread(new ThreadRequest(null, "created"), request);
        controller.deleteThread(new ThreadRequest(null, "other"), request);
        assertEquals("created", session.getAttribute("platformAiStateId"));
        controller.deleteThread(new ThreadRequest(null, "created"), request);
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
        ProviderCapabilities caps = new ProviderCapabilities(true, "recognized", "system", 1_000_000, 128_000,
                true, true, true, true, true, false, true);
        when(configs.listAvailableModels()).thenReturn(models.stream()
                .map(model -> new AiModelConfigService.CatalogEntry(model, null, model.getModel(), caps))
                .toList());
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
        verify(configs).listAvailableModels();
        verifyNoInteractions(threads, queries, queue);
    }

    @ParameterizedTest
    @ValueSource(strings = {"models", "availability"})
    void catalogAndAvailabilityUseTheSameServiceQuery(String endpoint) {
        AiModelConfig usable = config(1, "usable");
        when(configs.listAvailableModels()).thenReturn(List.of(new AiModelConfigService.CatalogEntry(
                usable, null, "usable", ProviderCapabilities.conservativeDefault())));

        if ("models".equals(endpoint)) {
            List<?> items = (List<?>) controller.models().get("data");
            assertEquals(1, items.size());
            assertEquals(usable.getId(), ((Map<?, ?>) items.get(0)).get("id"));
        } else {
            assertEquals(Map.of("available", true, "enabledCount", 1), controller.availability().get("data"));
        }
        verify(configs).listAvailableModels();
        verifyNoMoreInteractions(configs);
    }

    @Test
    void platformHistoryCursorStillBindsWithoutANodeSessionId() throws Exception {
        User user = new User();
        user.setUserId("owner");
        when(threads.messages(user, "thread", 2, 10, 7L))
                .thenReturn(Map.of("messages", List.of(), "hasMore", false));
        MockMvcBuilders.standaloneSetup(controller).build()
                .perform(post("/platform/ai/messages").sessionAttr("user", user)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"threadId":"thread","offset":2,"limit":10,"beforeSequence":7}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.hasMore").value(false));
        verify(threads).messages(user, "thread", 2, 10, 7L);
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
