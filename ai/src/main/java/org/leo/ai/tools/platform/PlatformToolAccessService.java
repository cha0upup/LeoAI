package org.leo.ai.tools.platform;

import org.leo.ai.agent.AiToolContext;
import org.leo.core.entity.AiExecutionPolicy;
import org.leo.core.entity.Agent;
import org.leo.core.entity.User;
import org.leo.core.security.AccessPolicy;
import org.leo.service.user.UserService;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/** 平台 Agent 工具对用户身份和 Agent 资源范围的二次校验。 */
@Component
public class PlatformToolAccessService {

    private final UserService userService;

    public PlatformToolAccessService(UserService userService) {
        this.userService = userService;
    }

    public User requireCurrentUser() {
        AiExecutionPolicy policy = AiToolContext.requireExecutionPolicy();
        User user = userService.getUserById(policy.getUserId());
        if (user == null || !Integer.valueOf(1).equals(user.getStatus())) {
            throw new SecurityException("当前用户不存在或已禁用");
        }
        return user;
    }

    public List<Agent> filterVisible(List<Agent> agents) {
        User user = requireCurrentUser();
        List<Agent> visible = new ArrayList<>();
        if (agents == null) return visible;
        for (Agent agent : agents) {
            if (canView(agent, user)) visible.add(agent);
        }
        return visible;
    }

    public Agent requireVisible(Agent agent) {
        if (agent == null) throw new IllegalArgumentException("Agent不存在");
        if (!canView(agent, requireCurrentUser())) {
            throw new SecurityException("无权限访问此 Agent");
        }
        return agent;
    }

    public Agent requireModifiable(Agent agent) {
        if (agent == null) throw new IllegalArgumentException("Agent不存在");
        if (!canModify(agent, requireCurrentUser())) {
            throw new SecurityException("无权限修改此 Agent");
        }
        return agent;
    }

    public boolean canView(Agent agent, User user) {
        return AccessPolicy.canAccessAgent(agent, user);
    }

    public boolean canModify(Agent agent, User user) {
        return AccessPolicy.canModifyAgent(agent, user);
    }

    public String normalizePermission(String permission) {
        if (AccessPolicy.PERMISSION_PUBLIC.equals(permission)) {
            return AccessPolicy.PERMISSION_PUBLIC;
        }
        if (AccessPolicy.PERMISSION_TEAM.equals(permission)) {
            return AccessPolicy.PERMISSION_TEAM;
        }
        return AccessPolicy.PERMISSION_PRIVATE;
    }
}
