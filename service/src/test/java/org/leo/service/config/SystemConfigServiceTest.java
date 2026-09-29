package org.leo.service.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.leo.dao.mapper.SystemConfigMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class SystemConfigServiceTest {

    private final SystemConfigMapper mapper = mock(SystemConfigMapper.class);
    private final SystemConfigService service = new SystemConfigService(mapper);

    @ParameterizedTest
    @CsvSource({"10, 10", "0, 6", "100, 64", "-2147483648, 6", "2147483647, 64"})
    void boundsParsedIntegers(int value, int expected) {
        when(mapper.findValueByKey("key")).thenReturn("  " + value + "  ");
        assertEquals(expected, service.getInt(" key ", 8, 6, 64));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "invalid", "1.5", "2147483648"})
    void usesDefaultForMissingOrMalformedConfiguration(String value) {
        when(mapper.findValueByKey("key")).thenReturn(value);
        assertEquals(8, service.getInt("key", 8, 6, 64));
    }

    @Test
    void usesDefaultWhenStoreIsUnavailable() {
        when(mapper.findValueByKey("key")).thenThrow(new IllegalStateException("offline"));
        assertEquals(8, service.getInt("key", 8, 6, 64));
    }

    @Test
    void rejectsInvalidBoundsBeforeReadingConfiguration() {
        assertThrows(IllegalArgumentException.class, () -> service.getInt("key", 8, 9, 6));
        assertThrows(IllegalArgumentException.class, () -> service.getInt("key", 5, 6, 64));
        assertThrows(IllegalArgumentException.class, () -> service.getInt("key", 65, 6, 64));
        verifyNoInteractions(mapper);
    }

    @Test
    void blankKeysDoNotReachTheDatabase() {
        assertEquals(8, service.getInt(null, 8, 6, 64));
        assertEquals(8, service.getInt(" ", 8, 6, 64));
        verifyNoInteractions(mapper);
    }
}
