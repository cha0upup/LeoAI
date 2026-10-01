package org.leo.core.runtime;

import org.junit.jupiter.api.Test;
import org.leo.core.component.runtime.ComponentArtifact;
import org.leo.core.component.runtime.ComponentDeliveryMode;
import org.leo.core.entity.Agent;
import org.leo.core.agent.AbstractAgentNode;
import org.leo.core.agent.capability.BasicInfoCapable;
import org.leo.core.agent.capability.AgentNodeCapabilityRegistry;
import org.leo.core.rpc.AgentOperation;
import org.leo.core.rpc.AgentRpcRequest;
import org.leo.core.session.AgentNodeSession;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeFoundationTest {

    @Test
    void requiresAnExplicitRuntimeAndRecognizesPhp() {
        assertEquals(AgentRuntime.UNKNOWN, AgentRuntime.from(null));
        assertEquals(AgentRuntime.UNKNOWN, AgentRuntime.from("  "));
        assertEquals(AgentRuntime.PHP, AgentRuntime.from(" PHP "));
        assertEquals(AgentRuntime.UNKNOWN, AgentRuntime.from("ruby"));
    }

    @Test
    void dynamicCapabilityCanDisableAStructurallySupportedInterface() {
        TrackingNode node = new TrackingNode();
        Agent agent = new Agent();
        agent.setType("php");
        node.setAgent(agent);
        node.setRuntimeProfile(RuntimeProfile.builder(AgentRuntime.PHP)
                .version("8.2")
                .sapi("fpm-fcgi")
                .capabilities(new CapabilitySet(List.of(
                        CapabilityStatus.unavailable("basicInfo", "component disabled"))))
                .build());

        assertEquals(AgentRuntime.PHP, node.getRuntime());
        assertFalse(AgentNodeCapabilityRegistry.supports(node, BasicInfoCapable.class));
        assertFalse(AgentNodeCapabilityRegistry.listSupported(node).contains("basicInfo"));
        assertEquals("component disabled",
                AgentNodeCapabilityRegistry.getStatus(node, "basicInfo").getReason());
    }

    @Test
    void protectsRpcParams() {
        AgentRpcRequest request = new AgentRpcRequest(
                "request-1",
                AgentOperation.PING,
                null,
                null,
                null,
                Map.of("key", "value"));

        assertEquals("value", request.params().get("key"));
        assertThrows(UnsupportedOperationException.class,
                () -> request.params().put("other", "value"));
    }

    @Test
    void componentArtifactsAndNodeLifecycleDoNotLeakMutableOrJavaSpecificState() {
        byte[] source = new byte[]{1, 2, 3};
        ComponentArtifact artifact = new ComponentArtifact(
                "system.basic-info", "1.0.0", "sha256",
                AgentRuntime.PHP, ComponentDeliveryMode.BUNDLED, source);
        source[0] = 9;
        byte[] exposed = artifact.getContent();
        exposed[1] = 9;
        assertArrayEquals(new byte[]{1, 2, 3}, artifact.getContent());

        TrackingNode node = new TrackingNode();
        AgentNodeSession session = new AgentNodeSession();
        session.setAgentNode(node);
        session.close();
        assertTrue(node.closed);
    }

    private static final class TrackingNode extends AbstractAgentNode implements BasicInfoCapable {
        private boolean closed;

        @Override
        public Set<String> getLoadedComponents() {
            return Set.of();
        }

        @Override
        public Map<String, Object> invokeComponent(String componentId, Map<String, Object> params) {
            return Map.of("code", 200);
        }

        @Override
        public Map<String, Object> testConnection() {
            return Map.of("code", 200);
        }

        @Override
        public void unloadComponent(String componentId) {
        }

        @Override
        public Map<String, Object> getBasicInfo() {
            return Map.of("code", 200);
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}
