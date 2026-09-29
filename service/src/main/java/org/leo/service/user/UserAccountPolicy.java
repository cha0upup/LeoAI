package org.leo.service.user;

import org.leo.core.entity.User;

import java.util.Locale;
import java.util.Set;

import static org.leo.service.user.UserService.PRIVILEGE_ADMIN;
import static org.leo.service.user.UserService.PRIVILEGE_LEADER;
import static org.leo.service.user.UserService.PRIVILEGE_NORMAL;

/** Shared account-field conventions for web and AI administration. */
public final class UserAccountPolicy {

    private static final Set<String> DISABLED_STATUSES =
            Set.of("0", "inactive", "disabled", "disable", "false");

    private UserAccountPolicy() {
    }

    public static String normalizePrivilege(String privilege) {
        if (PRIVILEGE_ADMIN.equals(privilege)) return PRIVILEGE_ADMIN;
        if (PRIVILEGE_LEADER.equals(privilege)) return PRIVILEGE_LEADER;
        return PRIVILEGE_NORMAL;
    }

    public static Integer normalizeStatus(Object status, Integer fallback) {
        if (status == null) return fallback != null ? fallback : 1;
        if (status instanceof Number number) return number.intValue() == 0 ? 0 : 1;
        if (status instanceof Boolean bool) return bool ? 1 : 0;
        String value = status.toString().trim().toLowerCase(Locale.ROOT);
        if (value.isEmpty()) return fallback != null ? fallback : 1;
        return DISABLED_STATUSES.contains(value) ? 0 : 1;
    }

    public static boolean isBuiltInAdmin(User user) {
        return user != null && ("admin".equals(user.getUserId()) || "admin".equals(user.getUserName()));
    }
}
