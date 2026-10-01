package org.leo.web.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.leo.core.entity.Puppet;
import org.leo.core.entity.User;
import org.leo.core.session.PuppetNodeSession;
import org.leo.service.PuppetService;
import org.leo.service.user.UserService;
import org.leo.web.exception.ApiException;
import org.springframework.stereotype.Service;

import java.util.Objects;

/**
 * Web 层权限入口，负责把纯规则和需要查库的链路判断组合起来。
 */
@Service
public class PermissionService {

    private static final int MAX_PARENT_DEPTH = 100;
    private static final String SESSION_ATTR_USER = "user";
    private static final String REQUEST_ATTR_VERIFIED_USER = PermissionService.class.getName() + ".verifiedUser";

    private record VerifiedUser(HttpSession session, User user, String userId) {}

    private final PuppetService puppetService;
    private final UserService userService;

    public PermissionService(PuppetService puppetService, UserService userService) {
        this.puppetService = puppetService;
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
                && snapshot.session() == session && snapshot.user() == user
                && Objects.equals(snapshot.userId(), user.getUserId())
                && Integer.valueOf(1).equals(user.getStatus())) {
            return user;
        }
        User fresh = userService.getUserById(user.getUserId());
        if (fresh == null || !Integer.valueOf(1).equals(fresh.getStatus())) {
            request.removeAttribute(REQUEST_ATTR_VERIFIED_USER);
            session.invalidate();
            return null;
        }
        session.setAttribute(SESSION_ATTR_USER, fresh);
        request.setAttribute(REQUEST_ATTR_VERIFIED_USER, new VerifiedUser(session, fresh, fresh.getUserId()));
        return fresh;
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

    public void requireSessionAccess(PuppetNodeSession session, User user, String sessionId) {
        if (!PermissionPolicy.canAccessSession(session, user)) {
            throw ApiException.forbidden("无权限访问此会话: " + sessionId);
        }
    }

    public Puppet requireAccessiblePuppetChain(String puppetId, User user) {
        String normalizedPuppetId = requireText(puppetId, "缺少 puppetId");
        Puppet puppet = puppetService.findPuppetById(normalizedPuppetId);
        if (puppet == null) {
            throw ApiException.notFound("Puppet不存在，puppetId: " + normalizedPuppetId);
        }
        if (!canAccessPuppetChain(puppet, user)) {
            throw ApiException.forbidden("无权限访问此Puppet");
        }
        return puppet;
    }

    public boolean canAccessPuppetChain(Puppet puppet, User user) {
        Puppet current = puppet;
        int depth = 0;
        while (current != null && depth < MAX_PARENT_DEPTH) {
            if (!canAccessPuppet(current, user)) {
                return false;
            }
            String parentId = current.getParentPuppetId();
            if (parentId == null || parentId.isBlank() || "root".equals(parentId)) {
                return true;
            }
            current = puppetService.findPuppetById(parentId);
            depth++;
        }
        return false;
    }

    private boolean canAccessPuppet(Puppet puppet, User user) {
        if (PermissionPolicy.canAccessPuppet(puppet, user)) {
            return true;
        }
        if (puppet == null || user == null || user.getTeamId() == null || user.getTeamId().isBlank()) {
            return false;
        }
        if (!PermissionPolicy.isTeamVisiblePermission(puppet.getPermission())) {
            return false;
        }
        if (puppet.getTeamId() != null && !puppet.getTeamId().isBlank()) {
            return false;
        }
        User owner = userService.getUserById(puppet.getCreateByUserId());
        return owner != null && user.getTeamId().equals(owner.getTeamId());
    }

    public static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw ApiException.badRequest(message);
        }
        return value.trim();
    }
}
