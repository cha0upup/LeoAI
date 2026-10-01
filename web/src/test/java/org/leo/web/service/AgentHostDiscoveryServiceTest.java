package org.leo.web.service;

import org.junit.jupiter.api.Test;
import org.leo.core.entity.Agent;
import org.leo.core.repository.session.AgentHostCacheRepository;
import org.leo.core.agent.AbstractAgentNode;
import org.leo.core.session.AgentNodeSession;
import org.leo.service.agentnode.AgentNodeFactory;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentHostDiscoveryServiceTest {

    @Test
    void doesNotProbeDuringSessionCreationFlow() {
        ProbeNode node = new ProbeNode(List.of(Map.of("code", 200)));
        AgentNodeSession session = session("session-1", node);
        session.bindHostId("host-a");

        assertEquals("host-a", session.getCurrentHostId());
        assertEquals(0, node.probes);
    }

    @Test
    void cacheSessionAlsoReturnsSnapshotWithoutProbing() {
        ProbeNode node = new ProbeNode(List.of(Map.of("code", 500, "msg", "offline")));
        AgentNodeSession session = session("session-2", node);
        session.bindHostId("host-a");

        session.setCacheMode(true);
        assertEquals(0, node.probes);
    }

    @Test
    void failedProbeDoesNotDiscardHostIdsFromOtherBackends() throws Exception {
        Agent agent = new Agent();
        agent.setAgentId("agent-1");
        agent.setConnLink("http://127.0.0.1/entry");

        AgentNodeFactory factory = mock(AgentNodeFactory.class);
        AgentHostCacheRepository repository = mock(AgentHostCacheRepository.class);
        when(repository.loadHostDiscovery(any(), eq("agent-1"), any())).thenReturn(null);

        AbstractAgentNode first = probe(Map.of("code", 200, "hostId", "host-a", "components", List.of()));
        AbstractAgentNode failed = mock(AbstractAgentNode.class);
        when(failed.testConnection()).thenThrow(new IllegalStateException("401"));
        doNothing().when(failed).close();
        AbstractAgentNode third = probe(Map.of("code", 200, "hostId", "host-b", "components", List.of()));
        AbstractAgentNode unavailable = probe(Map.of("code", 401));
        ArrayDeque<AbstractAgentNode> nodes = new ArrayDeque<>(
                List.of(first, failed, third, unavailable, unavailable, unavailable, unavailable, unavailable));
        when(factory.createLiveNode(eq(agent), isNull())).thenAnswer(ignored -> nodes.removeFirst());

        AgentHostDiscoveryService service = new AgentHostDiscoveryService(factory, repository);
        AgentHostDiscoveryService.DiscoveryResult result = service.discover(agent, null, true);

        assertEquals(Set.of("host-a", "host-b"), Set.copyOf(result.hostIds()));
        verify(factory, times(AgentHostDiscoveryService.DEFAULT_PROBE_COUNT))
                .createLiveNode(eq(agent), isNull());
        verify(repository).saveHostDiscovery(any(), eq("agent-1"), any(),
                argThat(ids -> Set.copyOf(ids).equals(Set.of("host-a", "host-b"))));
    }

    @Test
    void timedOutProbeDoesNotBlockLaterProbe() throws Exception {
        Agent agent = new Agent();
        agent.setAgentId("agent-timeout");
        agent.setConnLink("http://127.0.0.1/entry");

        AgentNodeFactory factory = mock(AgentNodeFactory.class);
        AgentHostCacheRepository repository = mock(AgentHostCacheRepository.class);
        when(repository.loadHostDiscovery(any(), eq("agent-timeout"), any())).thenReturn(null);

        AbstractAgentNode slow = mock(AbstractAgentNode.class);
        when(slow.testConnection()).thenAnswer(ignored -> {
            Thread.sleep(AgentHostDiscoveryService.DEFAULT_PROBE_TIMEOUT_MILLIS * 3);
            return Map.of("code", 200, "hostId", "too-late", "components", List.of());
        });
        doNothing().when(slow).close();
        AbstractAgentNode available = probe(Map.of("code", 200, "hostId", "host-fast", "components", List.of()));
        AbstractAgentNode unavailable = probe(Map.of("code", 401));
        ArrayDeque<AbstractAgentNode> nodes = new ArrayDeque<>(List.of(
                slow, available, unavailable, unavailable, unavailable, unavailable, unavailable, unavailable));
        when(factory.createLiveNode(eq(agent), isNull())).thenAnswer(ignored -> nodes.removeFirst());

        AgentHostDiscoveryService service = new AgentHostDiscoveryService(factory, repository);
        long started = System.nanoTime();
        AgentHostDiscoveryService.DiscoveryResult result = service.discover(agent, null, true);
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        assertEquals(List.of("host-fast"), result.hostIds());
        assertTrue(elapsedMillis < AgentHostDiscoveryService.DEFAULT_PROBE_TIMEOUT_MILLIS * 2,
                "slow probe should be bounded, elapsedMs=" + elapsedMillis);
    }

    private static AbstractAgentNode probe(Map<String, Object> response) throws Exception {
        AbstractAgentNode node = mock(AbstractAgentNode.class);
        when(node.testConnection()).thenReturn(response);
        doNothing().when(node).close();
        return node;
    }

    private static AgentNodeSession session(String sessionId, ProbeNode node) {
        AgentNodeSession session = new AgentNodeSession();
        session.setSessionId(sessionId);
        session.setAgentNode(node);
        return session;
    }

    private static final class ProbeNode extends AbstractAgentNode {
        private final ArrayDeque<Map<String, Object>> responses;
        private int probes;

        private ProbeNode(List<Map<String, Object>> responses) {
            this.responses = new ArrayDeque<>(responses);
        }

        @Override
        public Map<String, Object> testConnection() {
            probes++;
            return responses.isEmpty() ? Map.of("code", 500) : responses.removeFirst();
        }

        @Override
        public Set<String> getLoadedComponents() { return Set.of(); }

        @Override
        public Map<String, Object> invokeComponent(String componentId, Map<String, Object> params) {
            return Map.of();
        }

        @Override
        public void unloadComponent(String componentId) { }

    }
}
