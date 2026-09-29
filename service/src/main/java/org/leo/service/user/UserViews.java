package org.leo.service.user;

import org.leo.core.entity.User;

import java.util.LinkedHashMap;
import java.util.Map;

/** Explicit user projections that never modify authenticated or persistence-backed user objects. */
public final class UserViews {

    private UserViews() {
    }

    public static Map<String, Object> authentication(User user) {
        Map<String, Object> view = identity(user);
        view.put("passwordChangeRequired", user.requiresPasswordChange());
        return view;
    }

    public static Map<String, Object> profile(User user) {
        Map<String, Object> view = identity(user);
        view.put("email", user.getEmail());
        view.put("phone", user.getPhone());
        view.put("status", user.getStatus());
        view.put("lastLoginTime", user.getLastLoginTime());
        view.put("loginCount", user.getLoginCount());
        view.put("createTime", user.getCreateTime());
        view.put("updateTime", user.getUpdateTime());
        view.put("remark", user.getRemark());
        return view;
    }

    /** Keeps the AI tool's existing User-shaped output while excluding the password hash. */
    public static User withoutPassword(User user) {
        if (user == null) return null;
        User view = new User();
        view.setUserId(user.getUserId());
        view.setUserName(user.getUserName());
        view.setPassword("");
        view.setPrivilege(user.getPrivilege());
        view.setEmail(user.getEmail());
        view.setPhone(user.getPhone());
        view.setStatus(user.getStatus());
        view.setLastLoginTime(user.getLastLoginTime());
        view.setLoginCount(user.getLoginCount());
        view.setPasswordChangeRequired(user.getPasswordChangeRequired());
        view.setCreateTime(user.getCreateTime());
        view.setUpdateTime(user.getUpdateTime());
        view.setTeamId(user.getTeamId());
        view.setRemark(user.getRemark());
        return view;
    }

    private static Map<String, Object> identity(User user) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("userId", user.getUserId());
        view.put("userName", user.getUserName());
        view.put("privilege", user.getPrivilege());
        view.put("teamId", user.getTeamId());
        return view;
    }
}
