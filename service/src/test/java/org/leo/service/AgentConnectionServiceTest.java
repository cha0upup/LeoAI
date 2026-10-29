package org.leo.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.leo.core.entity.Agent;
import org.leo.core.agent.AbstractAgentNode;
import org.leo.service.agentnode.AgentNodeFactory;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AgentConnectionServiceTest {

    private AgentService agentService;
    private AgentNodeFactory factory;
    private AbstractAgentNode node;
    private Agent config;
    private AgentConnectionService service;

    @BeforeEach
    void setUp() throws Exception {
        agentService = mock(AgentService.class);
        factory = mock(AgentNodeFactory.class);
        node = mock(AbstractAgentNode.class);
        config = new Agent();
        config.setAgentId("existing-id");
        service = new AgentConnectionService(agentService, factory);
        when(agentService.findAgentById(config.getAgentId())).thenReturn(config);
        when(factory.createLiveNode(config, null)).thenReturn(node);
        when(node.testConnection()).thenReturn(Map.of(
                "code", 200,
                "hostId", "host-1",
                "components", List.of("TestConnComponent")));
    }

    @Test
    void testsUnsavedConfigurationWithoutUpdatingHeartbeat() throws Exception {
        Map<String, Object> result = service.testConnection(config);

        assertTrue((Boolean) result.get("success"));
        assertEquals("host-1", result.get("hostId"));
        assertEquals(List.of("TestConnComponent"), result.get("components"));
        assertTrue(result.get("latencyMs") instanceof Long);
        verifyNoInteractions(agentService);
    }

    @Test
    void recordsHeartbeatOnlyForSuccessfulSavedConfiguration() {
        Map<String, Object> result = service.testConnection(config.getAgentId());

        assertEquals(true, result.get("success"));
        verify(agentService).updateLastConnectedAt(config.getAgentId());
    }

    @Test
    void reportsMissingAgentWithoutCreatingNode() {
        assertEquals(Map.of("success", false, "message", "Agent 不存在，agentId: missing"),
                service.testConnection("missing"));
        verifyNoInteractions(factory, node);
    }

    @Test
    void reportsEmptyConfigurationWithoutCreatingNode() {
        assertEquals(Map.of("success", false, "message", "Agent 配置为空"),
                service.testConnection((Agent) null));
        verifyNoInteractions(factory, node, agentService);
    }

    @ParameterizedTest
    @MethodSource("invalidPingResults")
    void preservesFailureMessageAndLatencyWithoutRecordingHeartbeat(
            Map<String, Object> pingResult, String message) throws Exception {
        when(node.testConnection()).thenReturn(pingResult);

        Map<String, Object> result = service.testConnection(config.getAgentId());

        assertEquals(false, result.get("success"));
        assertEquals(message, result.get("message"));
        assertEquals(3, result.size());
        assertTrue(result.get("latencyMs") instanceof Long);
        verify(agentService, never()).updateLastConnectedAt(config.getAgentId());
    }

    private static Stream<Arguments> invalidPingResults() {
        return Stream.of(
                Arguments.of(null, "无响应"),
                Arguments.of(Map.of("code", 500, "msg", "connection failed"), "connection failed"),
                Arguments.of(Map.of("msg", 42), "42"),
                Arguments.of(Map.of("code", 200, "hostId", "host-1"), "节点响应格式不符合当前协议"));
    }

    @Test
    void reportsNodeCreationExceptionWithoutLatencyOrHeartbeat() throws Exception {
        when(factory.createLiveNode(config, null)).thenThrow(new IllegalArgumentException("invalid config"));

        assertEquals(Map.of("success", false, "message", "invalid config"),
                service.testConnection(config.getAgentId()));
        verifyNoInteractions(node);
        verify(agentService, never()).updateLastConnectedAt(config.getAgentId());
    }

    @Test
    void usesExceptionClassWhenTheConnectionExceptionHasNoMessage() throws Exception {
        when(node.testConnection()).thenThrow(new IllegalStateException());

        Map<String, Object> result = service.testConnection(config);

        assertEquals(Map.of("success", false, "message", "IllegalStateException"), result);
        assertFalse(result.containsKey("latencyMs"));
        verifyNoInteractions(agentService);
    }
}
