package org.leo.core.session;

import org.junit.jupiter.api.Test;
import org.leo.core.agent.AbstractAgentNode;
import org.leo.core.agent.capability.HostScopedCapable;

import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AgentNodeSessionHostIdTest {

    @Test
    void bindsOnceAndRejectsNodeRebind() {
        HostScopedNode node = new HostScopedNode();
        AgentNodeSession session = new AgentNodeSession();
        session.setSessionId("session-1");
        session.setAgentNode(node);
        session.bindHostId("host-1");
        session.setBasicInfo("host-1", Map.of("hostname", "old"));
        session.putAiContextValue("basic-info", Map.of("hostname", "old"));

        assertThrows(IllegalStateException.class, () -> node.setHostId("host-2"));

        assertEquals("host-1", session.getCurrentHostId());
        assertEquals(Map.of("hostname", "old"), session.getBasicInfo("host-1"));
        assertEquals(Map.of("hostname", "old"), session.getAiContextValue("basic-info"));
    }

    @Test
    void rejectsRebindingToAnotherHostId() {
        AgentNodeSession session = new AgentNodeSession();
        session.bindHostId("host-1");
        session.bindHostId("host-1");
        assertThrows(IllegalStateException.class, () -> session.bindHostId("host-2"));
    }

    private static final class HostScopedNode extends AbstractAgentNode implements HostScopedCapable {
        private String hostId;
        private Consumer<String> listener = ignored -> { };

        @Override
        public String getHostId() {
            return hostId;
        }

        @Override
        public void setHostId(String hostId) {
            this.hostId = hostId;
            listener.accept(hostId);
        }

        @Override
        public void setHostIdChangeListener(Consumer<String> listener) {
            this.listener = listener == null ? ignored -> { } : listener;
        }

        @Override
        public Set<String> getLoadedComponents() {
            return Set.of();
        }

        @Override
        public Map<String, Object> invokeComponent(
                String componentId, Map<String, Object> params) {
            return Map.of();
        }

        @Override
        public Map<String, Object> testConnection() {
            return Map.of("code", 200, "hostId", hostId);
        }

        @Override
        public void unloadComponent(String componentId) {
        }
    }
}
