package org.leo.core.agent.impl;

import org.junit.jupiter.api.Test;
import org.leo.core.agent.service.ComponentLoadRegistry;
import org.leo.core.agent.service.ComponentService;

import java.util.ArrayList;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaAgentServiceRegistryTest {

    @Test
    void broadcastsConfigurationAndLoadedState() {
        ComponentLoadRegistry loadRegistry = new ComponentLoadRegistry();
        ComponentService first = service();
        ComponentService second = service();
        JavaAgentServiceRegistry registry = new JavaAgentServiceRegistry();

        registry.replace(loadRegistry, first, second);
        registry.setHostId("host-1");
        registry.setMaxReqCount(7);
        registry.seedLoadedComponents("host-1", Set.of("ComponentA"));

        assertEquals(2, registry.size());
        assertEquals("host-1", first.getHostId());
        assertEquals("host-1", second.getHostId());
        assertEquals(7, first.getMaxReqCount());
        assertEquals(7, second.getMaxReqCount());
        assertEquals(Set.of("ComponentA"), registry.loadedComponents("host-1"));

        registry.clear();
        assertEquals(0, registry.size());
        assertTrue(first.getLoadedComponentNames("host-1").isEmpty());
        assertTrue(second.getLoadedComponentNames("host-1").isEmpty());
    }

    @Test
    void replaceKeepsRegistryBoundedToCurrentServices() {
        JavaAgentServiceRegistry registry = new JavaAgentServiceRegistry();
        ComponentLoadRegistry loadRegistry = new ComponentLoadRegistry();

        registry.replace(loadRegistry, service(), service());
        registry.replace(loadRegistry, service());

        assertEquals(1, registry.size());
    }

    private ComponentService service() {
        return new ComponentService(null, new ArrayList<>(), new ArrayList<>());
    }
}
