package org.leo.service.user;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.leo.dao.mapper.SystemConfigMapper;
import org.leo.service.config.SystemConfigService;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PasswordPolicyTest {

    @Test
    void enforcesConfiguredLengthOnBackend() {
        PasswordPolicy policy = policy("10");

        assertThrows(IllegalArgumentException.class, () -> policy.validate("short"));
        assertDoesNotThrow(() -> policy.validate("long-enough"));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"invalid", "2147483648"})
    void invalidConfigurationKeepsTheDefaultMinimum(String configured) {
        PasswordPolicy policy = policy(configured);
        assertThrows(IllegalArgumentException.class, () -> policy.validate("1234567"));
        assertDoesNotThrow(() -> policy.validate("12345678"));
    }

    @Test
    void boundsConfiguredMinimumAndAbsoluteMaximum() {
        PasswordPolicy lower = policy("1");
        assertThrows(IllegalArgumentException.class, () -> lower.validate("12345"));
        assertDoesNotThrow(() -> lower.validate("123456"));
        PasswordPolicy upper = policy("1000");
        assertThrows(IllegalArgumentException.class, () -> upper.validate("a".repeat(63)));
        assertDoesNotThrow(() -> upper.validate("a".repeat(64)));
        assertDoesNotThrow(() -> upper.validate("a".repeat(256)));
        assertThrows(IllegalArgumentException.class, () -> upper.validate("a".repeat(257)));
    }

    private PasswordPolicy policy(String configured) {
        SystemConfigMapper mapper = mock(SystemConfigMapper.class);
        when(mapper.findValueByKey("security.password.min.length")).thenReturn(configured);
        return new PasswordPolicy(new SystemConfigService(mapper));
    }
}
