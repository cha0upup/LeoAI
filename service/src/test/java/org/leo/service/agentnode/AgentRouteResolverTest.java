package org.leo.service.agentnode;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.leo.core.entity.Agent;
import org.leo.core.net.Communication;
import org.leo.core.net.impl.HttpCommunication;
import org.leo.core.net.layer.TlsFingerprintStrategy;
import org.leo.service.AgentService;
import org.mockito.ArgumentCaptor;

import java.net.Proxy;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
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

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void bothConnectionPathsUseParentTransportAndRequestedTlsStrategy(boolean usePlan) throws Exception {
        AgentService service = mock(AgentService.class);
        Agent child = agent("child", "transport");
        child.setTlsFingerprintStrategy("{\"enabled\":true,\"profile\":\"FIREFOX_MODERN\"}");
        Agent transport = agent("transport", "root");
        transport.setTlsFingerprintStrategy("{\"enabled\":true,\"profile\":\"CHROME_MODERN\"}");
        when(service.findAgentById("transport")).thenReturn(transport);
        AgentNodeFactory factory = spy(new AgentNodeFactory(service, List.of()));
        HttpCommunication communication = mock(HttpCommunication.class);
        doReturn(communication).when(factory).getCommunication(transport, Proxy.NO_PROXY);

        Communication actual = usePlan
                ? factory.createConnectionPlan(child).getCommunication()
                : factory.createCommunication(child);

        assertSame(communication, actual);
        verify(service, times(1)).findAgentById("transport");
        verify(factory, times(1)).getCommunication(transport, Proxy.NO_PROXY);
        ArgumentCaptor<TlsFingerprintStrategy> strategy = ArgumentCaptor.forClass(TlsFingerprintStrategy.class);
        verify(communication).setTlsFingerprintStrategy(strategy.capture());
        assertEquals(TlsFingerprintStrategy.BrowserProfile.FIREFOX_MODERN, strategy.getValue().getProfile());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void bothConnectionPathsRejectUnsupportedTransport(boolean usePlan) {
        Agent config = agent("node", "root");
        config.setProtocol("unknown-transport");
        config.setReqDisguiseId("missing-disguise");
        AgentNodeFactory factory = new AgentNodeFactory(null, List.of());

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> {
            if (usePlan) factory.createConnectionPlan(config);
            else factory.createCommunication(config);
        });

        assertTrue(error.getMessage().contains("unknown-transport"));
    }

    private Agent agent(String id, String parentId) {
        Agent agent = new Agent();
        agent.setAgentId(id);
        agent.setParentAgentId(parentId);
        return agent;
    }
}
