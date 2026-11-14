package org.leo.web.application.agent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
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
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AgentNodeLifecycleServiceTest {

    @TempDir
    Path vfsRoot;

    private MockedStatic<LeoConfig> config;
    private final AgentNodeFactory nodeFactory = mock(AgentNodeFactory.class);
    private final AbstractAgentNode node = mock(AbstractAgentNode.class);
    private final AgentService agentService = mock(AgentService.class);
    private final AgentCacheService cacheService = mock(AgentCacheService.class);
    private final AgentReconRepository reconRepository = mock(AgentReconRepository.class);
    private final AgentNodeLifecycleService lifecycleService = new AgentNodeLifecycleService(
            agentService, nodeFactory, cacheService,
            reconRepository, new SessionLifecycleManager(
                    mock(NetworkProbeWorkflowService.class)));
    private final Agent agent = new Agent();
    private final User user = new User();

    @BeforeEach
    void setUp() throws Exception {
        config = mockStatic(LeoConfig.class);
        config.when(LeoConfig::getVfsPath).thenReturn(vfsRoot.toString());
        agent.setAgentId("agent-1");
        user.setUserId("user-1");
        when(nodeFactory.createLiveNode(agent, user)).thenReturn(node);
        when(node.getAgent()).thenReturn(agent);
        when(node.testConnection()).thenReturn(Map.of("code", 200, "hostId", "host-1", "components", List.of()));
        when(cacheService.requireSelectedHostId("user-1", "agent-1", null)).thenReturn("host-1");
    }

    @AfterEach
    void clearSessions() {
        try {
            AgentNodeSessionContainer.clearAllSessions();
        } finally {
            config.close();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void createsDistinctSessionsWithoutAiThreads(boolean cacheMode) throws Exception {
        SessionInfo first = initSession(cacheMode);
        SessionInfo second = initSession(cacheMode);

        assertNotEquals(first.sessionId(), second.sessionId());
        assertSame(cacheMode ? null : node, assertSessionWithoutAiThreads(first, cacheMode).getAgentNode());
        assertSame(cacheMode ? null : node, assertSessionWithoutAiThreads(second, cacheMode).getAgentNode());
        if (cacheMode) verifyNoInteractions(nodeFactory, node, agentService);
        else {
            verify(node, times(2)).testConnection();
            verify(agentService, times(2)).updateLastConnectedAt("agent-1");
        }
    }

    @ParameterizedTest
    @MethodSource("invalidPingResponses")
    void rejectsOldNodeResponsesWithoutPublishingSessions(Map<String, Object> response) throws Exception {
        when(node.testConnection()).thenReturn(response);

        assertThrows(RuntimeException.class, () -> initSession(false));

        verify(node).close();
        verifyNoInteractions(reconRepository, agentService);
        assertTrue(AgentNodeSessionContainer.getAllSessions().isEmpty());
    }

    private static List<Map<String, Object>> invalidPingResponses() {
        return List.of(
                Map.<String, Object>of("code", "200", "hostId", "host-1", "components", List.of()),
                Map.<String, Object>of("code", 200, "components", List.of()),
                Map.<String, Object>of("code", 200, "hostId", "host-1", "components", "old-format"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void failedConnectionsCloseNodesWithoutHidingTheOriginalError(boolean closeFails) throws Exception {
        IllegalStateException failure = new IllegalStateException("connection failed");
        when(node.testConnection()).thenThrow(failure);
        if (closeFails) doThrow(new IllegalStateException("close failed")).when(node).close();

        assertSame(failure, assertThrows(IllegalStateException.class, () -> initSession(false)));

        verify(node).close();
        verifyNoInteractions(reconRepository, agentService);
        assertTrue(AgentNodeSessionContainer.getAllSessions().isEmpty());
    }

    @Test
    void selectedHostRetriesCloseMismatchesAndKeepTheMatchingConnection() throws Exception {
        AbstractAgentNode matching = mock(AbstractAgentNode.class);
        when(nodeFactory.createLiveNode(agent, user)).thenReturn(node, matching);
        when(matching.getAgent()).thenReturn(agent);
        when(matching.testConnection()).thenReturn(Map.of("code", 200, "hostId", "host-2", "components", List.of()));

        SessionInfo result = lifecycleService.initLiveSession(agent, user, "project-1", "host-2");

        AgentNodeSession session = AgentNodeSessionContainer.getSession(result.sessionId());
        assertSame(matching, session.getAgentNode());
        assertEquals("host-2", session.getCurrentHostId());
        verify(nodeFactory, times(2)).createLiveNode(agent, user);
        verify(node).close();
        verify(matching, never()).close();
        verify(reconRepository).load("user-1", "agent-1", "host-2");
        verify(agentService).updateLastConnectedAt("agent-1");
    }

    @ParameterizedTest
    @CsvSource({"false,saved", "true,saved", "false,missing", "true,missing", "false,unavailable", "true,unavailable"})
    void restoresOnlyTheSelectedHostSummaryAndKeepsSessionsUsableOnReadFailure(
            boolean cacheMode, String state) throws Exception {
        if ("unavailable".equals(state)) {
            when(reconRepository.load("user-1", "agent-1", "host-1"))
                    .thenThrow(new IllegalStateException("read failed"));
        } else {
            when(reconRepository.load("user-1", "agent-1", "host-1"))
                    .thenReturn("saved".equals(state) ? "persisted summary" : null);
        }

        AgentNodeSession session = assertSessionWithoutAiThreads(initSession(cacheMode), cacheMode);

        assertEquals("saved".equals(state) ? "persisted summary" : null, session.getReconSummary());
        verify(reconRepository).load("user-1", "agent-1", "host-1");
    }

    private SessionInfo initSession(boolean cacheMode) throws Exception {
        return cacheMode
                ? lifecycleService.initCacheSession(agent, user, "project-1", null)
                : lifecycleService.initLiveSession(agent, user, "project-1", null);
    }

    private AgentNodeSession assertSessionWithoutAiThreads(SessionInfo response, boolean cacheMode) {
        AgentNodeSession session = AgentNodeSessionContainer.getSession(response.sessionId());
        assertNotNull(session);
        assertEquals(cacheMode, response.cacheMode());
        assertEquals("project-1", response.projectId());
        assertEquals("project-1", session.getProjectId());
        assertEquals("user-1", session.getCreateByUser());
        assertEquals("agent-1", session.resolveAgentId());
        assertEquals("host-1", session.getCurrentHostId());
        assertTrue(session.listAiThreads().isEmpty());
        assertNull(session.getActiveThreadId());
        return session;
    }
}
