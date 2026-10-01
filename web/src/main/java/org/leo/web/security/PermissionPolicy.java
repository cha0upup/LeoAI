package org.leo.web.security;

import org.leo.core.entity.Agent;
import org.leo.core.entity.User;
import org.leo.core.security.AccessPolicy;
import org.leo.core.session.AgentNodeSession;

/**
 * 纯权限规则，不依赖 Spring Bean。
 *
 * <p>控制器、拦截器、工具方法都应复用这里的判断，避免权限语义在不同入口漂移。
 */
public final class PermissionPolicy {

    public static final String PRIVILEGE_ADMIN = AccessPolicy.PRIVILEGE_ADMIN;
    public static final String PRIVILEGE_LEADER = AccessPolicy.PRIVILEGE_LEADER;
    public static final String PERMISSION_PRIVATE = AccessPolicy.PERMISSION_PRIVATE;
    public static final String PERMISSION_TEAM = AccessPolicy.PERMISSION_TEAM;

    private PermissionPolicy() {
    }

    public static boolean isAdmin(User user) {
        return AccessPolicy.isAdmin(user);
    }

    public static boolean isLeader(User user) {
        return AccessPolicy.isLeader(user);
    }

    public static boolean isTeamVisiblePermission(String permission) {
        return AccessPolicy.isTeamVisiblePermission(permission);
    }

    public static boolean canAccessSession(AgentNodeSession session, User user) {
        return AccessPolicy.canAccessSession(session, user);
    }

    public static boolean canAccessAgent(Agent agent, User user) {
        return AccessPolicy.canAccessAgent(agent, user);
    }
}
