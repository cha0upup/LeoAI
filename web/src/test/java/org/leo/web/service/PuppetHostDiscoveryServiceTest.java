package org.leo.web.service;

import org.junit.jupiter.api.Test;
import org.leo.core.entity.Puppet;
import org.leo.core.repository.session.PuppetHostCacheRepository;
import org.leo.core.puppet.AbstractPuppetNode;
import org.leo.core.session.PuppetNodeSession;
import org.leo.service.puppetnode.PuppetNodeFactory;

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

class PuppetHostDiscoveryServiceTest {

    @Test
    void doesNotProbeDuringSessionCreationFlow() {
        ProbeNode node = new ProbeNode(List.of(Map.of("code", 200)));
        PuppetNodeSession session = session("session-1", node);
        session.bindHostId("host-a");

        assertEquals("host-a", session.getCurrentHostId());
        assertEquals(0, node.probes);
    }

    @Test
    void cacheSessionAlsoReturnsSnapshotWithoutProbing() {
        ProbeNode node = new ProbeNode(List.of(Map.of("code", 500, "msg", "offline")));
        PuppetNodeSession session = session("session-2", node);
        session.bindHostId("host-a");

        session.setCacheMode(true);
        assertEquals(0, node.probes);
    }

    @Test
    void failedProbeDoesNotDiscardHostIdsFromOtherBackends() throws Exception {
        Puppet puppet = new Puppet();
        puppet.setPuppetId("puppet-1");
        puppet.setConnLink("http://127.0.0.1/entry");

        PuppetNodeFactory factory = mock(PuppetNodeFactory.class);
        PuppetHostCacheRepository repository = mock(PuppetHostCacheRepository.class);
        when(repository.loadHostDiscovery(any(), eq("puppet-1"), any())).thenReturn(null);

        AbstractPuppetNode first = probe(Map.of("code", 200, "hostId", "host-a", "components", List.of()));
        AbstractPuppetNode failed = mock(AbstractPuppetNode.class);
        when(failed.testConnection()).thenThrow(new IllegalStateException("401"));
        doNothing().when(failed).close();
        AbstractPuppetNode third = probe(Map.of("code", 200, "hostId", "host-b", "components", List.of()));
        AbstractPuppetNode unavailable = probe(Map.of("code", 401));
        ArrayDeque<AbstractPuppetNode> nodes = new ArrayDeque<>(
                List.of(first, failed, third, unavailable, unavailable, unavailable, unavailable, unavailable));
        when(factory.createLiveNode(eq(puppet), isNull())).thenAnswer(ignored -> nodes.removeFirst());

        PuppetHostDiscoveryService service = new PuppetHostDiscoveryService(factory, repository);
        PuppetHostDiscoveryService.DiscoveryResult result = service.discover(puppet, null, true);

        assertEquals(Set.of("host-a", "host-b"), Set.copyOf(result.hostIds()));
        verify(factory, times(PuppetHostDiscoveryService.DEFAULT_PROBE_COUNT))
                .createLiveNode(eq(puppet), isNull());
        verify(repository).saveHostDiscovery(any(), eq("puppet-1"), any(),
                argThat(ids -> Set.copyOf(ids).equals(Set.of("host-a", "host-b"))));
    }

    @Test
    void timedOutProbeDoesNotBlockLaterProbe() throws Exception {
        Puppet puppet = new Puppet();
        puppet.setPuppetId("puppet-timeout");
        puppet.setConnLink("http://127.0.0.1/entry");

        PuppetNodeFactory factory = mock(PuppetNodeFactory.class);
        PuppetHostCacheRepository repository = mock(PuppetHostCacheRepository.class);
        when(repository.loadHostDiscovery(any(), eq("puppet-timeout"), any())).thenReturn(null);

        AbstractPuppetNode slow = mock(AbstractPuppetNode.class);
        when(slow.testConnection()).thenAnswer(ignored -> {
            Thread.sleep(PuppetHostDiscoveryService.DEFAULT_PROBE_TIMEOUT_MILLIS * 3);
            return Map.of("code", 200, "hostId", "too-late", "components", List.of());
        });
        doNothing().when(slow).close();
        AbstractPuppetNode available = probe(Map.of("code", 200, "hostId", "host-fast", "components", List.of()));
        AbstractPuppetNode unavailable = probe(Map.of("code", 401));
        ArrayDeque<AbstractPuppetNode> nodes = new ArrayDeque<>(List.of(
                slow, available, unavailable, unavailable, unavailable, unavailable, unavailable, unavailable));
        when(factory.createLiveNode(eq(puppet), isNull())).thenAnswer(ignored -> nodes.removeFirst());

        PuppetHostDiscoveryService service = new PuppetHostDiscoveryService(factory, repository);
        long started = System.nanoTime();
        PuppetHostDiscoveryService.DiscoveryResult result = service.discover(puppet, null, true);
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        assertEquals(List.of("host-fast"), result.hostIds());
        assertTrue(elapsedMillis < PuppetHostDiscoveryService.DEFAULT_PROBE_TIMEOUT_MILLIS * 2,
                "slow probe should be bounded, elapsedMs=" + elapsedMillis);
    }

    private static AbstractPuppetNode probe(Map<String, Object> response) throws Exception {
        AbstractPuppetNode node = mock(AbstractPuppetNode.class);
        when(node.testConnection()).thenReturn(response);
        doNothing().when(node).close();
        return node;
    }

    private static PuppetNodeSession session(String sessionId, ProbeNode node) {
        PuppetNodeSession session = new PuppetNodeSession();
        session.setSessionId(sessionId);
        session.setPuppetNode(node);
        return session;
    }

    private static final class ProbeNode extends AbstractPuppetNode {
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
