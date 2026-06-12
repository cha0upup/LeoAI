package org.leo.web.security;

import org.leo.core.entity.Puppet;
import org.leo.core.entity.User;
import org.leo.core.session.PuppetNodeSession;

/**
 * 纯权限规则，不依赖 Spring Bean。
 *
 * <p>控制器、拦截器、工具方法都应复用这里的判断，避免权限语义在不同入口漂移。
 */
public final class PermissionPolicy {

    public static final String PRIVILEGE_ADMIN = "admin";
    private static final String PERMISSION_PUBLIC = "public";
    private static final String PERMISSION_PROTECTED = "protected";

    private PermissionPolicy() {
    }

    public static boolean isAdmin(User user) {
        return user != null && PRIVILEGE_ADMIN.equals(user.getPrivilege());
    }

    public static boolean canAccessSession(PuppetNodeSession session, User user) {
        if (session == null || user == null || user.getUserId() == null) {
            return false;
        }
        if (isAdmin(user)) {
            return true;
        }
        String owner = session.getCreateByUser();
        return owner != null && owner.equals(user.getUserId());
    }

    public static boolean canAccessPuppet(Puppet puppet, User user) {
        if (puppet == null || user == null || user.getUserId() == null) {
            return false;
        }
        if (isAdmin(user)) {
            return true;
        }
        if (user.getUserId().equals(puppet.getCreateByUserId())) {
            return true;
        }
        if (PERMISSION_PUBLIC.equals(puppet.getPermission())) {
            return true;
        }
        return PERMISSION_PROTECTED.equals(puppet.getPermission())
                && user.getTeamId() != null
                && user.getTeamId().equals(puppet.getTeamId());
    }
}
