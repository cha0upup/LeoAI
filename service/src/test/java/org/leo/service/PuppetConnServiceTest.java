package org.leo.service;

import org.junit.jupiter.api.Test;
import org.leo.core.entity.Puppet;
import org.leo.core.puppet.AbstractPuppetNode;
import org.leo.service.puppetnode.PuppetNodeFactory;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PuppetConnServiceTest {

    @Test
    void testsUnsavedConfigurationWithoutUpdatingHeartbeat() throws Exception {
        PuppetService puppetService = mock(PuppetService.class);
        PuppetNodeFactory factory = mock(PuppetNodeFactory.class);
        AbstractPuppetNode node = mock(AbstractPuppetNode.class);
        Puppet config = new Puppet();
        config.setPuppetId("existing-id");

        when(factory.createLiveNode(config, null)).thenReturn(node);
        when(node.testConnection()).thenReturn(Map.of(
                "code", 200,
                "hostId", "host-1",
                "components", List.of()));

        Map<String, Object> result = new PuppetConnService(puppetService, factory)
                .testConnection(config);

        assertTrue((Boolean) result.get("success"));
        assertEquals("host-1", result.get("hostId"));
        verify(puppetService, never()).updateLastHeartbeat("existing-id");
    }
}
