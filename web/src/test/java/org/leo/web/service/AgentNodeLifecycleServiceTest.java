package org.leo.web.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.leo.core.config.LeoConfig;
import org.leo.core.entity.Agent;
import org.leo.core.entity.User;
import org.leo.core.agent.AbstractAgentNode;
import org.leo.core.session.AgentNodeSession;
import org.leo.core.session.AgentNodeSessionContainer;
import org.leo.core.repository.session.AgentReconRepository;
import org.leo.service.AgentService;
import org.leo.service.agentnode.AgentNodeFactory;
import org.leo.web.dto.agentnode.AgentInitResponse;
import org.mockito.MockedStatic;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

class AgentNodeLifecycleServiceTest {

    @TempDir
    Path vfsRoot;

    private MockedStatic<LeoConfig> config;
    private final AgentNodeFactory nodeFactory = mock(AgentNodeFactory.class);
    private final AgentCacheService cacheService = mock(AgentCacheService.class);
    private final AgentNodeLifecycleService lifecycleService = new AgentNodeLifecycleService(
            mock(AgentService.class), nodeFactory, cacheService,
            mock(AgentReconRepository.class), new SessionLifecycleManager(
                    mock(NetworkProbeWorkflowService.class)));
    private final Agent agent = new Agent();
    private final User user = new User();

    @BeforeEach
    void setUp() {
        config = mockStatic(LeoConfig.class);
        config.when(LeoConfig::getVfsPath).thenReturn(vfsRoot.toString());
        agent.setAgentId("agent-1");
        user.setUserId("user-1");
    }

    @AfterEach
    void clearSessions() {
        try {
            AgentNodeSessionContainer.clearAllSessions();
        } finally {
            config.close();
        }
    }

    @Test
    void liveConnectionsDoNotCreateAiThreads() throws Exception {
        AbstractAgentNode node = mock(AbstractAgentNode.class);
        when(nodeFactory.createLiveNode(agent, user)).thenReturn(node);
        when(node.getAgent()).thenReturn(agent);
        when(node.testConnection()).thenReturn(Map.of("code", 200, "hostId", "host-1", "components", List.of()));

        AgentInitResponse first = lifecycleService.initLiveSession(agent, user, null, null);
        AgentInitResponse second = lifecycleService.initLiveSession(agent, user, null, null);

        assertNotEquals(first.sessionId(), second.sessionId());
        assertSame(node, assertSessionWithoutAiThreads(first, false).getAgentNode());
        assertSame(node, assertSessionWithoutAiThreads(second, false).getAgentNode());
    }

    @Test
    void rejectsOldNodeResponsesWithoutHostIdentityOrCurrentComponentList() throws Exception {
        AbstractAgentNode node = mock(AbstractAgentNode.class);
        when(nodeFactory.createLiveNode(agent, user)).thenReturn(node);
        for (Map<String, Object> response : List.of(
                Map.<String, Object>of("code", "200", "hostId", "host-1", "components", List.of()),
                Map.<String, Object>of("code", 200, "components", List.of()),
                Map.<String, Object>of("code", 200, "hostId", "host-1", "components", "old-format"))) {
            when(node.testConnection()).thenReturn(response);
            assertThrows(RuntimeException.class,
                    () -> lifecycleService.initLiveSession(agent, user, null, null));
        }
        assertTrue(AgentNodeSessionContainer.getAllSessions().isEmpty());
    }

    @Test
    void cacheConnectionsDoNotCreateAiThreads() {
        when(cacheService.requireSelectedHostId("user-1", "agent-1", null))
                .thenReturn("host-1");

        AgentInitResponse first = lifecycleService.initCacheSession(agent, user, null, null);
        AgentInitResponse second = lifecycleService.initCacheSession(agent, user, null, null);

        assertNotEquals(first.sessionId(), second.sessionId());
        assertSessionWithoutAiThreads(first, true);
        assertSessionWithoutAiThreads(second, true);
    }

    private AgentNodeSession assertSessionWithoutAiThreads(AgentInitResponse response, boolean cacheMode) {
        AgentNodeSession session = AgentNodeSessionContainer.getSession(response.sessionId());
        assertNotNull(session);
        assertEquals(cacheMode, response.cacheMode());
        assertEquals("user-1", session.getCreateByUser());
        assertEquals("agent-1", session.resolveAgentId());
        assertTrue(session.listAiThreads().isEmpty());
        assertNull(session.getActiveThreadId());
        return session;
    }
}
