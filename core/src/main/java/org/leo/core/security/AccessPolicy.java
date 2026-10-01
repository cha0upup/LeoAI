package org.leo.core.security;

import org.leo.core.entity.Agent;
import org.leo.core.entity.User;
import org.leo.core.session.AgentNodeSession;

/**
 * 跨入口复用的纯权限规则。
 *
 * <p>这里只处理不需要查库的判断；需要解析 Agent 祖先链、补全团队归属等
 * 规则时，由上层权限服务组合这些基础判断。
 */
public final class AccessPolicy {

    public static final String PRIVILEGE_ADMIN = "admin";
    public static final String PRIVILEGE_LEADER = "leader";
    public static final String PERMISSION_PRIVATE = "private";
    public static final String PERMISSION_TEAM = "team";
    public static final String PERMISSION_PUBLIC = "public";

    private AccessPolicy() {
    }

    public static boolean isAdmin(User user) {
        return user != null && PRIVILEGE_ADMIN.equals(user.getPrivilege());
    }

    public static boolean isLeader(User user) {
        return user != null && PRIVILEGE_LEADER.equals(user.getPrivilege());
    }

    public static boolean isTeamVisiblePermission(String permission) {
        return PERMISSION_TEAM.equals(permission);
    }

    public static boolean canAccessSession(AgentNodeSession session, User user) {
        if (session == null || user == null || user.getUserId() == null) {
            return false;
        }
        if (isAdmin(user)) {
            return true;
        }
        String owner = session.getCreateByUser();
        return owner != null && owner.equals(user.getUserId());
    }

    public static boolean canAccessAgent(Agent agent, User user) {
        if (agent == null || user == null || user.getUserId() == null) {
            return false;
        }
        if (isAdmin(user)) {
            return true;
        }
        if (user.getUserId().equals(agent.getCreateByUserId())) {
            return true;
        }
        if (PERMISSION_PUBLIC.equals(agent.getPermission())) {
            return true;
        }
        return isTeamVisiblePermission(agent.getPermission())
                && user.getTeamId() != null
                && user.getTeamId().equals(agent.getTeamId());
    }

    public static boolean canModifyAgent(Agent agent, User user) {
        if (agent == null || user == null || user.getUserId() == null) {
            return false;
        }
        if (isAdmin(user)) {
            return true;
        }
        if (PERMISSION_PUBLIC.equals(agent.getPermission())) {
            return false;
        }
        if (user.getUserId().equals(agent.getCreateByUserId())) {
            return true;
        }
        return isLeader(user)
                && isTeamVisiblePermission(agent.getPermission())
                && user.getTeamId() != null
                && user.getTeamId().equals(agent.getTeamId());
    }
}
