package org.leo.service.user;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.leo.core.entity.User;

import static org.junit.jupiter.api.Assertions.*;

class UserAccountPolicyTest {

    @ParameterizedTest
    @CsvSource({"admin, admin", "leader, leader", "normal, normal", "invalid, normal", "ADMIN, normal", ", normal"})
    void normalizesRolesWithoutPromotingUnknownValues(String input, String expected) {
        assertEquals(expected, UserAccountPolicy.normalizePrivilege(input));
    }

    @Test
    void preservesStatusConventionsAcrossWebAndAiInputs() {
        for (Object disabled : new Object[]{0, 0L, false, "0", "inactive", " DISABLED ", "disable", "false"}) {
            assertEquals(0, UserAccountPolicy.normalizeStatus(disabled, 1), String.valueOf(disabled));
        }
        for (Object enabled : new Object[]{1, 2, -1, true, "1", "enabled", "active"}) {
            assertEquals(1, UserAccountPolicy.normalizeStatus(enabled, 0), String.valueOf(enabled));
        }
        assertEquals(0, UserAccountPolicy.normalizeStatus(null, 0));
        assertEquals(0, UserAccountPolicy.normalizeStatus("  ", 0));
        assertEquals(1, UserAccountPolicy.normalizeStatus(null, null));
    }

    @Test
    void protectsTheBuiltInAccountByIdOrName() {
        User user = new User();
        assertFalse(UserAccountPolicy.isBuiltInAdmin(user));
        assertFalse(UserAccountPolicy.isBuiltInAdmin(null));
        user.setUserId("admin");
        assertTrue(UserAccountPolicy.isBuiltInAdmin(user));
        user.setUserId("another-id");
        user.setUserName("admin");
        assertTrue(UserAccountPolicy.isBuiltInAdmin(user));
        user.setUserName("alice");
        user.setPrivilege("admin");
        assertFalse(UserAccountPolicy.isBuiltInAdmin(user));
    }
}
