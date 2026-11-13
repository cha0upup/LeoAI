package org.leo.web.application.agent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.leo.core.agent.AbstractAgentNode;
import org.leo.core.config.LeoConfig;
import org.leo.core.entity.Agent;
import org.leo.core.entity.User;
import org.leo.core.infrastructure.persistence.session.AgentReconRepository;
import org.leo.core.session.AgentNodeSession;
import org.leo.core.session.AgentNodeSessionContainer;
import org.leo.service.agentnode.AgentNodeFactory;
import org.leo.service.agentnode.AgentService;
import org.leo.service.discovery.NetworkProbeWorkflowService;
import org.leo.web.application.agent.AgentNodeLifecycleService.SessionInfo;
import org.mockito.MockedStatic;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
    void liveConnectionsUseOnePingAndCreateNoAiThreads() throws Exception {
        AbstractAgentNode node = mock(AbstractAgentNode.class);
        when(nodeFactory.createLiveNode(agent, user)).thenReturn(node);
        when(node.getAgent()).thenReturn(agent);
        when(node.testConnection()).thenReturn(Map.of("code", 200, "hostId", "host-1", "components", List.of()));

        SessionInfo first = lifecycleService.initLiveSession(agent, user, null, null);
        verify(node).testConnection();
        SessionInfo second = lifecycleService.initLiveSession(agent, user, null, null);

        assertNotEquals(first.sessionId(), second.sessionId());
        assertSame(node, assertSessionWithoutAiThreads(first, false).getAgentNode());
        assertSame(node, assertSessionWithoutAiThreads(second, false).getAgentNode());
        verify(node, times(2)).testConnection();
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
    void cacheConnectionsAvoidLiveNodesAndAiThreads() {
        when(cacheService.requireSelectedHostId("user-1", "agent-1", null))
                .thenReturn("host-1");

        SessionInfo first = lifecycleService.initCacheSession(agent, user, null, null);
        SessionInfo second = lifecycleService.initCacheSession(agent, user, null, null);

        assertNotEquals(first.sessionId(), second.sessionId());
        assertSessionWithoutAiThreads(first, true);
        assertSessionWithoutAiThreads(second, true);
        verifyNoInteractions(nodeFactory);
    }

    private AgentNodeSession assertSessionWithoutAiThreads(SessionInfo response, boolean cacheMode) {
        AgentNodeSession session = AgentNodeSessionContainer.getSession(response.sessionId());
        assertNotNull(session);
        assertEquals(cacheMode, response.cacheMode());
        assertEquals("user-1", session.getCreateByUser());
        assertEquals("agent-1", session.resolveAgentId());
        assertEquals("host-1", session.getCurrentHostId());
        assertTrue(session.listAiThreads().isEmpty());
        assertNull(session.getActiveThreadId());
        return session;
    }
}
