package org.leo.web.controller.agentnode.ai;

import org.junit.jupiter.api.Test;
import org.leo.core.session.AgentNodeSession;
import org.leo.web.adapter.ai.AiEventSubscriptionService;
import org.leo.web.application.ai.AiThreadQueryService;
import org.leo.web.application.ai.AiTurnProtocolService;
import org.leo.web.application.ai.AiTurnQueueService;
import org.leo.web.application.ai.NodeAiThreadService;
import org.leo.web.config.GlobalExceptionHandler;
import org.leo.web.util.ControllerUtil;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class NodeAiControllerTest {
    private final NodeAiThreadService threads = mock(NodeAiThreadService.class);
    private final NodeAiController controller = new NodeAiController(threads, mock(AiThreadQueryService.class),
            mock(AiEventSubscriptionService.class), mock(AiTurnProtocolService.class), mock(AiTurnQueueService.class));

    @Test
    void nodeHistoryStillRequiresAnExplicitSessionId() throws Exception {
        MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new GlobalExceptionHandler()).build()
                .perform(post("/agent-node/ai/thread/messages").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"threadId\":\"thread\",\"beforeSequence\":7}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.msg").value("缺少 sessionId"));
        verifyNoInteractions(threads);
    }

    @Test
    void nodeHistoryStillBindsTheSessionAndCursor() throws Exception {
        AgentNodeSession session = new AgentNodeSession();
        when(threads.threadMessages(session, "thread", 2, 10, 7L))
                .thenReturn(Map.of("messages", List.of(), "hasMore", false));
        try (var utilities = mockStatic(ControllerUtil.class)) {
            utilities.when(() -> ControllerUtil.requireText("session", "缺少 sessionId")).thenReturn("session");
            utilities.when(() -> ControllerUtil.requireText("thread", "缺少 threadId")).thenReturn("thread");
            utilities.when(() -> ControllerUtil.getAgentNodeSession("session")).thenReturn(session);
            MockMvcBuilders.standaloneSetup(controller).build()
                    .perform(post("/agent-node/ai/thread/messages").contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"sessionId":"session","threadId":"thread","offset":2,"limit":10,"beforeSequence":7}
                                    """))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.hasMore").value(false));
        }
        verify(threads).threadMessages(session, "thread", 2, 10, 7L);
    }
}
