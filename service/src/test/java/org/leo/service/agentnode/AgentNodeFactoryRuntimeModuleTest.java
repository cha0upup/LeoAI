package org.leo.service.agentnode;

import org.junit.jupiter.api.Test;
import org.leo.core.entity.Agent;
import org.leo.core.entity.User;
import org.leo.core.agent.AbstractAgentNode;
import org.leo.core.runtime.AgentNodeCreationContext;
import org.leo.core.runtime.AgentRuntime;
import org.leo.core.runtime.AgentRuntimeModule;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AgentNodeFactoryRuntimeModuleTest {

    @Test
    void selectsJavaAndPhpThroughTheSameRuntimeModuleSpi() throws Exception {
        AgentNodeFactory factory = new AgentNodeFactory(null, List.of(
                module(AgentRuntime.JAVA, true),
                module(AgentRuntime.PHP, true)));

        assertEquals(AgentRuntime.JAVA, factory.createLiveNode(agent("java"), null).getRuntime());
        assertEquals(AgentRuntime.PHP, factory.createLiveNode(agent("php"), null).getRuntime());
    }

    @Test
    void rejectsDuplicateOrUnavailableRuntimeModules() {
        assertThrows(IllegalStateException.class, () -> new AgentNodeFactory(null, List.of(
                module(AgentRuntime.JAVA, true),
                module(AgentRuntime.JAVA, true))));

        AgentNodeFactory factory = new AgentNodeFactory(null, List.of(
                module(AgentRuntime.JAVA, true),
                module(AgentRuntime.PHP, false)));
        assertThrows(IllegalArgumentException.class,
                () -> factory.createLiveNode(agent("php"), null));
    }

    @Test
    void appendsEncodedWebSocketGuardHeadersWithoutLosingExistingQuery() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("X Gate", "token&role=admin");
        headers.put("昵称", "测试 用户");

        String result = AgentNodeFactory.appendHeaderQuery(
                "wss://example.test/leo?tenant=one", headers);

        assertEquals("wss://example.test/leo?tenant=one"
                        + "&X%20Gate=token%26role%3Dadmin"
                        + "&%E6%98%B5%E7%A7%B0=%E6%B5%8B%E8%AF%95%20%E7%94%A8%E6%88%B7",
                result);
    }

    private static Agent agent(String type) {
        Agent agent = new Agent();
        agent.setType(type);
        return agent;
    }

    private static AgentRuntimeModule module(AgentRuntime runtime, boolean ready) {
        return new AgentRuntimeModule() {
            @Override
            public AgentRuntime getRuntime() {
                return runtime;
            }

            @Override
            public boolean isReady() {
                return ready;
            }

            @Override
            public AbstractAgentNode createNode(Agent agent,
                                                 User user,
                                                 AgentNodeCreationContext context) {
                RuntimeNode node = new RuntimeNode();
                node.setAgent(agent);
                node.setUser(user);
                return node;
            }
        };
    }

    private static final class RuntimeNode extends AbstractAgentNode {
        @Override
        public Set<String> getLoadedComponents() {
            return Set.of();
        }

        @Override
        public Map<String, Object> invokeComponent(String componentId, Map<String, Object> params) {
            return Map.of();
        }

        @Override
        public Map<String, Object> testConnection() {
            return Map.of();
        }

        @Override
        public void unloadComponent(String componentId) {
        }
    }
}
