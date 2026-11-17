package org.leo.web.application.agent;

import org.junit.jupiter.api.Test;
import org.leo.core.agent.AbstractAgentNode;
import org.leo.core.entity.Agent;
import org.leo.core.infrastructure.persistence.session.AgentHostCacheRepository;
import org.leo.core.infrastructure.persistence.session.AgentHostCacheRepository.DiscoverySnapshot;
import org.leo.service.agentnode.AgentNodeFactory;

import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AgentHostDiscoveryServiceTest {

    private final AgentNodeFactory factory = mock(AgentNodeFactory.class);
    private final AgentHostCacheRepository repository = mock(AgentHostCacheRepository.class);
    private final AgentHostDiscoveryService service = new AgentHostDiscoveryService(factory, repository);

    @Test
    void reusesPersistedHostsWithoutProbingOrRewritingCache() throws Exception {
        Agent agent = new Agent();
        agent.setAgentId("agent-cached");
        agent.setConnLink("http://127.0.0.1/entry");
        DiscoverySnapshot cached = new DiscoverySnapshot(List.of("host-a", "host-b"), "2026-11-16T00:00:00Z");
        when(repository.loadHostDiscovery(any(), eq("agent-cached"), any())).thenReturn(cached);

        assertEquals(new AgentHostDiscoveryService.DiscoveryResult(cached.hostIds(), cached.discoveredAt(), true),
                service.discover(agent, null, false));

        verifyNoInteractions(factory);
        verify(repository).loadHostDiscovery(isNull(), eq("agent-cached"),
                eq("c839ebc228c24bc980bf9ceeaea588287f205dccf8df2170f67786b655ebaf3b"));
        verify(repository, never()).saveHostDiscovery(any(), any(), any(), any());
    }

    @Test
    void failedProbeDoesNotDiscardHostIdsFromOtherBackends() throws Exception {
        Agent agent = new Agent();
        agent.setAgentId("agent-1");
        agent.setConnLink("http://127.0.0.1/entry");

        when(repository.loadHostDiscovery(any(), eq("agent-1"), any()))
                .thenReturn(new DiscoverySnapshot(List.of("old-host"), "saved-at"));

        AbstractAgentNode first = probe(Map.of("code", 200, "hostId", "host-a", "components", List.of()));
        AbstractAgentNode failed = mock(AbstractAgentNode.class);
        when(failed.testConnection()).thenThrow(new IllegalStateException("401"));
        doNothing().when(failed).close();
        AbstractAgentNode third = probe(Map.of("code", 200, "hostId", "host-b", "components", List.of()));
        AbstractAgentNode unavailable = probe(Map.of("code", 401));
        Queue<AbstractAgentNode> nodes = new ConcurrentLinkedQueue<>(
                List.of(first, failed, third, unavailable, unavailable, unavailable, unavailable, unavailable));
        when(factory.createLiveNode(eq(agent), isNull())).thenAnswer(ignored -> nodes.remove());

        AgentHostDiscoveryService.DiscoveryResult result = service.discover(agent, null, true);

        assertEquals(Set.of("host-a", "host-b"), Set.copyOf(result.hostIds()));
        assertEquals("saved-at", result.discoveredAt());
        assertFalse(result.reused());
        verify(repository).loadHostDiscovery(any(), eq("agent-1"), any());
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

        AbstractAgentNode slow = mock(AbstractAgentNode.class);
        when(slow.testConnection()).thenAnswer(ignored -> {
            Thread.sleep(AgentHostDiscoveryService.DEFAULT_PROBE_TIMEOUT_MILLIS * 3);
            return Map.of("code", 200, "hostId", "too-late", "components", List.of());
        });
        doNothing().when(slow).close();
        AbstractAgentNode available = probe(Map.of("code", 200, "hostId", "host-fast", "components", List.of()));
        AbstractAgentNode unavailable = probe(Map.of("code", 401));
        Queue<AbstractAgentNode> nodes = new ConcurrentLinkedQueue<>(List.of(
                slow, available, unavailable, unavailable, unavailable, unavailable, unavailable, unavailable));
        when(factory.createLiveNode(eq(agent), isNull())).thenAnswer(ignored -> nodes.remove());

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
}
