package org.leo.service.user;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.leo.service.config.SystemConfigService;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PasswordPolicyTest {

    @Test
    void enforcesTheEffectiveConfiguredMinimum() {
        PasswordPolicy policy = policy(12);
        assertThrows(IllegalArgumentException.class, () -> policy.validate("a".repeat(11)));
        assertDoesNotThrow(() -> policy.validate("a".repeat(12)));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = " \t\n")
    void rejectsBlankPasswords(String password) {
        assertThrows(IllegalArgumentException.class, () -> policy(8).validate(password));
    }

    @Test
    void enforcesTheAbsoluteMaximum() {
        PasswordPolicy policy = policy(8);
        assertDoesNotThrow(() -> policy.validate("a".repeat(256)));
        assertThrows(IllegalArgumentException.class, () -> policy.validate("a".repeat(257)));
    }

    private PasswordPolicy policy(int minimum) {
        SystemConfigService config = mock(SystemConfigService.class);
        when(config.getInt("security.password.min.length", 8, 6, 64)).thenReturn(minimum);
        return new PasswordPolicy(config);
    }
}
