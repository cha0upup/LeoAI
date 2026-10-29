package org.leo.web.init;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.leo.service.user.SystemInitializationService;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.*;

class DataInitializerTest {
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void startupOnlyAnnouncesSetupWithoutWritingAccounts(boolean required) {
        var setup = mock(SystemInitializationService.class);
        when(setup.isInitializationRequired()).thenReturn(required);
        assertDoesNotThrow(() -> new DataInitializer(setup).init());
        verify(setup).isInitializationRequired();
        verifyNoMoreInteractions(setup);
    }
}
