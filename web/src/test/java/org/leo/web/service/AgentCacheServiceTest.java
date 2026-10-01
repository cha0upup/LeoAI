package org.leo.web.service;

import org.junit.jupiter.api.Test;
import org.leo.core.repository.session.AgentHostCacheRepository;
import org.leo.core.repository.session.AtomicFileStore;
import org.leo.web.exception.ApiException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AgentCacheServiceTest {

    @Test
    void automaticallySelectsTheOnlyCachedHost() {
        AgentCacheService service = serviceWithHosts(List.of("host-a"));
        assertEquals("host-a", service.requireSelectedHostId("user", "agent", null));
    }

    @Test
    void requiresSelectionWhenMultipleHostsAreCached() {
        AgentCacheService service = serviceWithHosts(List.of("host-a", "host-b"));
        assertThrows(ApiException.class,
                () -> service.requireSelectedHostId("user", "agent", null));
        assertEquals("host-b", service.requireSelectedHostId("user", "agent", "host-b"));
    }

    private AgentCacheService serviceWithHosts(List<String> hosts) {
        return new AgentCacheService(new AgentHostCacheRepository(new AtomicFileStore())) {
            @Override
            public List<String> hostIds(String userId, String agentId) {
                return hosts;
            }
        };
    }
}
