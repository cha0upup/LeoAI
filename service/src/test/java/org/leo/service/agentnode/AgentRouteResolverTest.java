package org.leo.service.agentnode;

import org.junit.jupiter.api.Test;
import org.leo.core.entity.Agent;
import org.leo.service.AgentService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentRouteResolverTest {

    @Test
    void resolvesOneImmutableChildToTransportRoute() {
        AgentService service = mock(AgentService.class);
        Agent child = agent("child", "middle");
        Agent middle = agent("middle", "transport");
        Agent transport = agent("transport", "root");
        when(service.findAgentById("middle")).thenReturn(middle);
        when(service.findAgentById("transport")).thenReturn(transport);

        AgentRouteResolver.Route route = new AgentRouteResolver(service).resolve(child);

        assertSame(child, route.requested());
        assertSame(transport, route.transport());
        assertEquals(java.util.List.of(child, middle, transport), route.chain());
        assertThrows(UnsupportedOperationException.class, () -> route.chain().clear());
    }

    @Test
    void rejectsMissingParentAndParentCycle() {
        AgentService service = mock(AgentService.class);
        Agent child = agent("child", "missing");
        assertThrows(IllegalArgumentException.class,
                () -> new AgentRouteResolver(service).resolve(child));

        Agent first = agent("first", "second");
        Agent second = agent("second", "first");
        when(service.findAgentById("second")).thenReturn(second);
        when(service.findAgentById("first")).thenReturn(first);
        assertThrows(IllegalArgumentException.class,
                () -> new AgentRouteResolver(service).resolve(first));
    }

    @Test
    void connectionPlanResolvesTheParentChainOnlyOnce() throws Exception {
        AgentService service = mock(AgentService.class);
        Agent child = agent("child", "transport");
        Agent transport = agent("transport", "root");
        transport.setProtocol("http");
        transport.setConnLink("http://127.0.0.1/runtime");
        when(service.findAgentById("transport")).thenReturn(transport);

        AgentNodeFactory factory = new AgentNodeFactory(service, java.util.List.of());
        var plan = factory.createConnectionPlan(child);

        assertEquals(0, plan.getTransportLayers().getRequestLayers().size());
        assertEquals(0, plan.getTransportLayers().getResponseLayers().size());
        verify(service, times(1)).findAgentById("transport");
    }

    private Agent agent(String id, String parentId) {
        Agent agent = new Agent();
        agent.setAgentId(id);
        agent.setParentAgentId(parentId);
        return agent;
    }
}
