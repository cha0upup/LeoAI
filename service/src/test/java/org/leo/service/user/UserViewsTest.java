package org.leo.service.user;

import org.junit.jupiter.api.Test;
import org.leo.core.entity.User;
import org.leo.core.util.json.JsonUtil;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

class UserViewsTest {

    @Test
    void safeCopyPreservesPublicFieldsWithoutModifyingTheSource() {
        User source = user();
        User copy = UserViews.withoutPassword(source);

        assertNotSame(source, copy);
        assertThat(copy).usingRecursiveComparison().ignoringFields("password").isEqualTo(source);
        assertEquals("", copy.getPassword());
        assertEquals("stored-password-hash", source.getPassword());
        assertFalse(JsonUtil.toJsonString(copy).contains("stored-password-hash"));
        copy.setUserName("changed");
        assertEquals("alice", source.getUserName());
        assertNull(UserViews.withoutPassword(null));
    }

    private User user() {
        User user = new User("user-1", "alice", "stored-password-hash", "normal", "2026-09-01 12:00:00");
        user.setEmail("alice@example.test");
        user.setPhone("12345678");
        user.setStatus(0);
        user.setLastLoginTime("2026-09-02 12:00:00");
        user.setLoginCount(3);
        user.setPasswordChangeRequired(1);
        user.setUpdateTime("2026-09-03 12:00:00");
        user.setTeamId("team-1");
        user.setRemark("example");
        return user;
    }
}
