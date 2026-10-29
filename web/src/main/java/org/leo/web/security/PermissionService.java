package org.leo.web.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.leo.core.entity.Agent;
import org.leo.core.entity.User;
import org.leo.core.session.AgentNodeSession;
import org.leo.service.AgentService;
import org.leo.service.security.AgentAccessService;
import org.leo.service.user.UserService;
import org.leo.web.exception.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Objects;

/**
 * Web 层权限入口，负责把纯规则和需要查库的链路判断组合起来。
 */
@Service
public class PermissionService {

    private static final String SESSION_ATTR_USER = "user";
    private static final String REQUEST_ATTR_VERIFIED_USER = PermissionService.class.getName() + ".verifiedUser";

    private record VerifiedUser(HttpSession session, User user, String userId, PermissionService verifier) {}

    private final AgentAccessService agentAccess;
    private final AgentService agentService;
    private final UserService userService;

    public PermissionService(AgentService agentService, UserService userService) {
        this(agentService, userService, new AgentAccessService(agentService, userService));
    }

    @Autowired
    public PermissionService(AgentService agentService, UserService userService, AgentAccessService agentAccess) {
        this.agentAccess = agentAccess;
        this.agentService = agentService;
        this.userService = userService;
    }

    public User getCurrentUser(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        HttpSession session = request.getSession(false);
        if (session == null) return null;
        Object cached = session.getAttribute(SESSION_ATTR_USER);
        if (!(cached instanceof User user) || user.getUserId() == null) return null;
        // 只复用当前请求已校验的对象；Session 替换、登出或身份变更后重新查库。
        Object verified = request.getAttribute(REQUEST_ATTR_VERIFIED_USER);
        if (verified instanceof VerifiedUser snapshot
                && snapshot.verifier() == this
                && snapshot.session() == session && snapshot.user() == user
                && Objects.equals(snapshot.userId(), user.getUserId())
                && Integer.valueOf(1).equals(user.getStatus())) {
            return user;
        }
        User fresh = userService.getUserById(user.getUserId());
        if (fresh == null || !Integer.valueOf(1).equals(fresh.getStatus())
                || !Objects.equals(user.getPassword(), fresh.getPassword())) {
            request.removeAttribute(REQUEST_ATTR_VERIFIED_USER);
            session.invalidate();
            return null;
        }
        session.setAttribute(SESSION_ATTR_USER, fresh);
        request.setAttribute(REQUEST_ATTR_VERIFIED_USER, new VerifiedUser(session, fresh, fresh.getUserId(), this));
        return fresh;
    }

    /** Only a successfully verified request may resolve the static controller facade. */
    public static PermissionService forVerifiedRequest(HttpServletRequest request) {
        if (request == null) return null;
        HttpSession session = request.getSession(false);
        Object verified = request.getAttribute(REQUEST_ATTR_VERIFIED_USER);
        if (session != null && verified instanceof VerifiedUser snapshot
                && snapshot.session() == session && snapshot.user() == session.getAttribute(SESSION_ATTR_USER)
                && Objects.equals(snapshot.userId(), snapshot.user().getUserId())) {
            return snapshot.verifier();
        }
        return null;
    }

    public User requireLogin(HttpServletRequest request) {
        User user = getCurrentUser(request);
        if (user == null) {
            throw ApiException.unauthorized("用户未登录");
        }
        return user;
    }

    public void requireAdmin(User user) {
        if (!PermissionPolicy.isAdmin(user)) {
            throw ApiException.forbidden("管理员权限不足");
        }
    }

    public void requireSessionAccess(AgentNodeSession session, User user, String sessionId) {
        if (!agentAccess.canAccessSession(session, user)) {
            throw ApiException.forbidden("无权限访问此会话: " + sessionId);
        }
    }

    public Agent requireAccessibleAgentChain(String agentId, User user) {
        String normalizedAgentId = requireText(agentId, "缺少 agentId");
        Agent agent = agentService.findAgentById(normalizedAgentId);
        if (agent == null) {
            throw ApiException.notFound("Agent不存在，agentId: " + normalizedAgentId);
        }
        if (!canAccessAgentChain(agent, user)) {
            throw ApiException.forbidden("无权限访问此Agent");
        }
        return agent;
    }

    public boolean canAccessAgentChain(Agent agent, User user) {
        return agentAccess.canAccessAgentChain(agent, user);
    }

    public static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw ApiException.badRequest(message);
        }
        return value.trim();
    }
}
