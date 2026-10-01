package org.leo.service;

import org.junit.jupiter.api.Test;
import org.leo.core.entity.Agent;
import org.leo.core.agent.AbstractAgentNode;
import org.leo.service.agentnode.AgentNodeFactory;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentConnectionServiceTest {

    @Test
    void testsUnsavedConfigurationWithoutUpdatingHeartbeat() throws Exception {
        AgentService agentService = mock(AgentService.class);
        AgentNodeFactory factory = mock(AgentNodeFactory.class);
        AbstractAgentNode node = mock(AbstractAgentNode.class);
        Agent config = new Agent();
        config.setAgentId("existing-id");

        when(factory.createLiveNode(config, null)).thenReturn(node);
        when(node.testConnection()).thenReturn(Map.of(
                "code", 200,
                "hostId", "host-1",
                "components", List.of()));

        Map<String, Object> result = new AgentConnectionService(agentService, factory)
                .testConnection(config);

        assertTrue((Boolean) result.get("success"));
        assertEquals("host-1", result.get("hostId"));
        verify(agentService, never()).updateLastHeartbeat("existing-id");
    }
}
