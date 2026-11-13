package org.leo.web.application.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.leo.core.infrastructure.persistence.session.AgentHostCacheRepository;
import org.leo.core.infrastructure.persistence.session.AgentHostCacheRepository.CacheSnapshot;
import org.leo.web.application.agent.AgentCacheService.Status;
import org.leo.web.exception.ApiException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class AgentCacheServiceTest {

    private final AgentHostCacheRepository repository = mock(AgentHostCacheRepository.class);
    private final AgentCacheService service = new AgentCacheService(repository);

    @ParameterizedTest
    @CsvSource(value = {"true,time", "true,null", "false,time", "false,null"}, nullValues = "null")
    void readsStatusOnceAndHidesSaveTimeWithoutCachedHosts(boolean available, String saveTime) {
        when(repository.readCache("user", "agent"))
                .thenReturn(new CacheSnapshot(available ? List.of("host-a") : List.of(), saveTime));

        assertEquals(new Status(available, available ? saveTime : null), service.status("user", "agent"));
        verify(repository).readCache("user", "agent");
        verifyNoMoreInteractions(repository);
    }

    @Test
    void automaticallySelectsTheOnlyCachedHost() {
        when(repository.readCache("user", "agent")).thenReturn(new CacheSnapshot(List.of("host-a"), null));
        assertEquals("host-a", service.requireSelectedHostId("user", "agent", null));
    }

    @Test
    void requiresSelectionWhenMultipleHostsAreCached() {
        when(repository.readCache("user", "agent")).thenReturn(new CacheSnapshot(List.of("host-a", "host-b"), null));
        assertThrows(ApiException.class,
                () -> service.requireSelectedHostId("user", "agent", null));
        assertEquals("host-b", service.requireSelectedHostId("user", "agent", "host-b"));
    }
}
